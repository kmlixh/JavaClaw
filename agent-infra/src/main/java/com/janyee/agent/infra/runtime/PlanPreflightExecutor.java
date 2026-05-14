package com.janyee.agent.infra.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.domain.ChatAttachment;
import com.janyee.agent.infra.run.RunPlanPersister;
import com.janyee.agent.runtime.loop.CompletedToolSummary;
import com.janyee.agent.runtime.loop.PlanStatus;
import com.janyee.agent.runtime.loop.PlanStep;
import com.janyee.agent.runtime.loop.RunPlan;
import com.janyee.agent.runtime.loop.ToolCallDecision;
import com.janyee.agent.runtime.loop.ToolCallOutcome;
import com.janyee.agent.runtime.loop.ToolCallRequest;
import com.janyee.agent.runtime.loop.ToolExecutor;
import com.janyee.agent.runtime.loop.ToolLoopContext;
import com.janyee.agent.runtime.session.SessionTranscriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * L1 并行化 —— 在 LLM tool loop 开始前,把"输入已完全就绪、SQL 模板已物化、彼此独立"的
 * plan step 用 Reactor 扇出并发执行完,省掉 LLM 在数据获取阶段的无谓来回。
 *
 * <h3>判定 step 可 preflight 的条件(保守)</h3>
 * <ul>
 *   <li>step 状态仍是 PENDING;</li>
 *   <li>至少有一套 SQL 模板(geojson / noFilter)在用户上下文里可用 —— L1 不处理 admin filter,
 *       城市/区县名抽取留给 LLM(走 sqlTemplates 路径);</li>
 *   <li>toolHint 不是 artifact.* —— 合成性 step(report)必须 LLM 参与,不能程序执行。</li>
 * </ul>
 *
 * <h3>整体放弃 preflight 的场景</h3>
 * <p>用户消息含中文行政区后缀(市/区/县/州/盟/地区)时,preflight 整体退出(return 0),让
 * LLM 按 prompt 决策树走 sqlTemplates(admin filter)。否则 preflight 会强行用 NoFilter 把
 * 上游 step 跑成全省口径,跟用户意图不符,LLM 接手后无法挽救(weak_grid 等下游 step 拒绝
 * COMPLETED,run 整体失败)。</p>
 *
 * <p>成功 preflight 的 step 直接标 COMPLETED 并挂上 CompletedToolSummary,LLM 从 wave 1 起手
 * 时看到的就是"已有数据的 plan",不需要再发 db.query 请求。失败的 step 保持 PENDING,LLM
 * 会按原来的路径 fallback 处理。</p>
 */
@Component
public class PlanPreflightExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanPreflightExecutor.class);
    private static final int DEFAULT_CONCURRENCY = 3;

    /**
     * 用户消息含中文行政区后缀(市/区/县/州/盟/地区)就视为"区域意图",preflight 整体放弃 ——
     * 让 LLM 按 prompt 决策树走 sqlTemplates(admin filter)。否则 preflight 会强行用
     * sqlTemplatesNoFilter 把上游 step 跑成全省口径,跟用户意图不符,LLM 接手后无法挽救。
     *
     * <p>误判方向偏保守:常见词组 "小区"/"超市"/"市场"/"区域" 也会命中这个 pattern,
     * 但代价仅是 preflight 不预跑(LLM 自己跑慢一点),不会损失正确性。漏判才会出现
     * "用户问盘龙区,数据却是全云南"的事故,所以宁可多跳过也不要错过。</p>
     */
    private static final Pattern ADMIN_SCOPE_PATTERN = Pattern.compile(
            "[\\u4e00-\\u9fa5]{1,6}(市|区|县|州|盟|地区)"
    );

    private final ToolExecutor toolExecutor;
    private final SessionTranscriptService transcriptService;
    private final RunPlanPersister planPersister;
    private final ObjectMapper objectMapper;

    public PlanPreflightExecutor(
            ToolExecutor toolExecutor,
            SessionTranscriptService transcriptService,
            RunPlanPersister planPersister,
            ObjectMapper objectMapper
    ) {
        this.toolExecutor = toolExecutor;
        this.transcriptService = transcriptService;
        this.planPersister = planPersister;
        this.objectMapper = objectMapper;
    }

    /** Returns the number of steps successfully preflighted across all waves. */
    public int runWave0(ToolLoopContext context, List<ChatAttachment> attachments, String userMessage) {
        RunPlan plan = context.runPlan();
        if (plan == null || plan.isEmpty()) {
            return 0;
        }
        String geoJson = extractGeoJsonFromAttachments(attachments);
        // 决策顺序:GeoJSON > admin filter 意图 > 全省 NoFilter。
        // GeoJSON 优先是因为它是几何过滤,精度高于行政区名,即使 message 里带 "盘龙区" 也以几何为准。
        // 没有 GeoJSON 但 message 含行政区名 → preflight 放弃,LLM 自己按 sqlTemplates(admin filter)
        // 路径跑;否则才走 NoFilter preflight 加速。
        if (geoJson == null && hasAdminScopeIntent(userMessage)) {
            log.info("plan.preflight.skipped_admin_scope runId={}, reason=user_message_has_admin_scope, snippet={}",
                    context.runId(), snippet(userMessage));
            return 0;
        }
        Variant variant = geoJson != null ? Variant.GEOJSON : Variant.NO_FILTER;

        // Multi-wave 推进:每轮挑出"PENDING + 模板可用 + 所有 dependsOn 都已 COMPLETED/SKIPPED"的
        // step 一起并发跑;跑完一轮后再用最新 plan 状态选下一轮。直到没有可推进的为止。
        // 之前是一刀切单轮,序列化串行 skill (默认 dependsOn=前一步) 在 preflight 阶段只能跑掉
        // 第一个 step,后面的全都被 dep 卡住。multi-wave 让先 COMPLETED 的 step 解锁它的下游,
        // 整条数据采集链都能在 LLM 接管之前跑完。
        // 加 16 轮硬上限防御无意义死循环 —— 正常 skill plan 不超过 8 个 step,16 已经留足余量。
        final int maxWaves = 16;
        AtomicInteger successCount = new AtomicInteger(0);
        int totalDispatched = 0;

        for (int wave = 0; wave < maxWaves; wave++) {
            List<PlanStep> candidates = new ArrayList<>();
            for (PlanStep step : plan.steps()) {
                if (isEligible(step, variant) && plan.isReady(step)) {
                    candidates.add(step);
                }
            }
            if (candidates.isEmpty()) {
                if (wave == 0) {
                    log.info("plan.preflight.no_candidates runId={}, variant={}", context.runId(), variant);
                }
                break;
            }
            totalDispatched += candidates.size();
            log.info("plan.preflight.wave_start runId={}, wave={}, variant={}, candidateCount={}, stepIds={}",
                    context.runId(), wave, variant, candidates.size(),
                    candidates.stream().map(PlanStep::id).toList());
            context.emitEvent(
                    AgentEventType.RUN_STATUS,
                    "phase=PREFLIGHT wave=%d 并行预执行 %d 个 step: %s".formatted(
                            wave, candidates.size(),
                            candidates.stream().map(PlanStep::id).toList())
            );

            AtomicInteger waveSuccess = new AtomicInteger(0);
            // Reactor 扇出:maxConcurrency=3 覆盖典型一波 3 路独立查询。太高抢光 DB 池;太低失去并行意义。
            Flux.fromIterable(candidates)
                    .flatMap(step -> Mono
                                    .fromCallable(() -> executeStepOffMainThread(context, step, variant, geoJson))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .doOnNext(ok -> {
                                        if (ok) {
                                            waveSuccess.incrementAndGet();
                                            successCount.incrementAndGet();
                                        }
                                    })
                                    .onErrorResume(error -> {
                                        log.warn("plan.preflight.step_errored runId={}, stepId={}, cause={}",
                                                context.runId(), step.id(), error.getMessage());
                                        return Mono.just(Boolean.FALSE);
                                    }),
                            DEFAULT_CONCURRENCY)
                    .blockLast();
            log.info("plan.preflight.wave_done runId={}, wave={}, succeeded={}, total={}",
                    context.runId(), wave, waveSuccess.get(), candidates.size());
            // 这一波有 step 失败 → 它的 dep 链下游不会被解锁,下一轮 candidates 可能为空提前退出;
            // 这是预期行为:preflight 不强制追完,失败 step 留 PENDING 让 LLM 接手。
            if (waveSuccess.get() == 0) {
                // 整波都失败 = 没有任何下游能解锁,提前结束防止下一轮空跑。
                break;
            }
        }

        if (totalDispatched == 0) {
            return 0;
        }
        planPersister.sync(context.runId(), plan);
        // Preflight 完成后把整张 plan 快照再推一次,前端面板直接看到 wave 0..N step 已 COMPLETED。
        try {
            context.emitEvent(
                    AgentEventType.PLAN_UPDATED,
                    objectMapper.writeValueAsString(plan.toSnapshot())
            );
        } catch (Exception serializationError) {
            log.warn("plan.preflight.snapshot_emit_failed runId={}, cause={}",
                    context.runId(), serializationError.getMessage());
        }
        context.emitEvent(
                AgentEventType.RUN_STATUS,
                "phase=PREFLIGHT 并行预执行完成: %d/%d step 成功"
                        .formatted(successCount.get(), totalDispatched)
        );
        log.info("plan.preflight.finish runId={}, succeeded={}, totalDispatched={}",
                context.runId(), successCount.get(), totalDispatched);
        return successCount.get();
    }

    // ---------------------------------------------------------------------------------------
    // eligibility

    private boolean isEligible(PlanStep step, Variant variant) {
        if (step.status() != PlanStatus.PENDING) {
            return false;
        }
        String hint = step.toolHint();
        if (hint != null && hint.toLowerCase().contains("artifact.")) {
            // report step 之类需要 LLM 合成,程序执行不了
            return false;
        }
        return templatesForVariant(step, variant).size() > 0;
    }

    private List<String> templatesForVariant(PlanStep step, Variant variant) {
        if (variant == Variant.GEOJSON) {
            return step.sqlTemplatesGeoJson() != null ? step.sqlTemplatesGeoJson() : List.of();
        }
        return step.sqlTemplatesNoFilter() != null ? step.sqlTemplatesNoFilter() : List.of();
    }

    // ---------------------------------------------------------------------------------------
    // execution

    /**
     * Execute all SQL templates for a step sequentially on the current thread (which is a
     * Reactor boundedElastic worker, not the main orchestrator thread). Returns true when
     * every SQL succeeded — partial success leaves step PENDING so LLM can handle the tail.
     */
    private boolean executeStepOffMainThread(ToolLoopContext context, PlanStep step, Variant variant, String geoJson) {
        List<String> templates = templatesForVariant(step, variant);
        long stepStartMs = System.currentTimeMillis();
        log.info("plan.preflight.step_start runId={}, stepId={}, variant={}, templateCount={}",
                context.runId(), step.id(), variant, templates.size());
        step.updateStatus(PlanStatus.IN_PROGRESS);
        boolean allOk = true;
        int rowCountSum = 0;
        int sqlIdx = 0;
        int failedAtSql = -1;
        for (String raw : templates) {
            sqlIdx += 1;
            long sqlStartMs = System.currentTimeMillis();
            String sql = substitute(raw, variant, geoJson);
            ToolCallOutcome outcome = runDbQuery(context, step, sql);
            long sqlDur = System.currentTimeMillis() - sqlStartMs;
            if (outcome == null || !outcome.executed() || !outcome.success()) {
                log.warn("plan.preflight.step_sql_failed runId={}, stepId={}, sqlIdx={}/{}, durationMs={}, error={}",
                        context.runId(), step.id(), sqlIdx, templates.size(), sqlDur,
                        outcome == null ? "null-outcome" : outcome.errorMessage());
                allOk = false;
                failedAtSql = sqlIdx;
                break;
            }
            int rowCount = parseRowCount(outcome);
            rowCountSum += rowCount;
            String firstRow = parseFirstRowJson(outcome);
            log.info("plan.preflight.step_sql_done runId={}, stepId={}, sqlIdx={}/{}, durationMs={}, rowCount={}",
                    context.runId(), step.id(), sqlIdx, templates.size(), sqlDur, rowCount);
            step.attachToolSummary(CompletedToolSummary.of(
                    "db.query",
                    sql,
                    outcome.toolResult() != null ? outcome.toolResult().summary() : "",
                    rowCountSum,
                    step.id(),
                    firstRow
            ));
        }
        long stepDur = System.currentTimeMillis() - stepStartMs;
        if (allOk) {
            // step 完成校验:不光看"所有 SQL 都没报错",还要检查每条 SQL 实际返回了行。
            // 业务上 preflight 用的是 COUNT(*) / SUM(...) 这类聚合,正常每条 SQL 必返 1 行,
            // 全 0 行通常意味着用户限定的 region(geojson / 县级 filter) 在表里没数据,
            // 这种"虚假完成"如果直接标 COMPLETED,LLM 看到 plan 全绿就直接调 artifact.markdown,
            // 报告里全是占位符。所以校验三档:
            //   sqlExecuted < templates.size  → 实际不应进 allOk 分支(防御性兜底)
            //   全部 SQL rowCount==0          → 标 COMPLETED 但 resultNote 加警告,
            //                                   LLM 在 prompt 里能看见,知道要重查
            //   有 SQL 返回行                  → 正常 COMPLETED
            int summaryCount = step.toolSummaries().size();
            int zeroRowSummaries = (int) step.toolSummaries().stream()
                    .filter(s -> s != null && s.rowCount() == 0)
                    .count();
            boolean allZeroRows = summaryCount > 0 && zeroRowSummaries == summaryCount;
            step.updateStatus(PlanStatus.COMPLETED);
            // scope 标签必须显式塞进 resultNote —— 这是 LLM 在 [Plan] 里能直接看到的字段。
            // NO_FILTER = 没有 region 过滤的全省级 SQL,数字不能当区县/城市答复;
            // GEOJSON   = 用户附件 GeoJSON 区域内的精确数字,可以直接采用。
            // 之前的 note 只写 "auto-executed N SQL, rowCountSum=X",LLM 看不出范围,
            // 把全省级数字直接当成"西山区"的答案塞进报告 —— 这条修复就是为了堵这个洞。
            String scopeTag = variant == Variant.GEOJSON
                    ? "scope=GEOJSON (用户附件区域内,数字可直接采用)"
                    : "scope=NO_FILTER (无 region 过滤,全表/全省统计 — 若用户问的是具体城市/区县/{地图N},这些数字不可直接写入报告,必须按用户 region 重新 db.query)";
            if (allZeroRows) {
                step.updateResultNote(scopeTag + " | preflight: " + templates.size()
                        + " SQL 全部返回 0 行 — 用户筛选条件下可能无数据,生成报告前请按 GeoJSON / 区县名重新核查");
                log.warn("plan.preflight.step_done runId={}, stepId={}, status=COMPLETED(but zero-rows), variant={}, durationMs={}, sqlExecuted={}, summaries={}",
                        context.runId(), step.id(), variant, stepDur, templates.size(), summaryCount);
            } else {
                step.updateResultNote(scopeTag + " | preflight: auto-executed " + templates.size()
                        + " SQL, rowCountSum=" + rowCountSum
                        + (zeroRowSummaries > 0 ? " (其中 " + zeroRowSummaries + "/" + summaryCount + " 条返回 0 行)" : ""));
                log.info("plan.preflight.step_done runId={}, stepId={}, status=COMPLETED, variant={}, durationMs={}, sqlExecuted={}, summaries={}, rowCountSum={}, zeroRowSummaries={}",
                        context.runId(), step.id(), variant, stepDur, templates.size(), summaryCount, rowCountSum, zeroRowSummaries);
            }
            return true;
        }
        step.updateStatus(PlanStatus.PENDING);
        step.updateResultNote("preflight partial/failed — LLM will continue normally");
        log.warn("plan.preflight.step_done runId={}, stepId={}, status=PENDING(partial), durationMs={}, failedAtSql={}/{}, rowCountSum={}",
                context.runId(), step.id(), stepDur, failedAtSql, templates.size(), rowCountSum);
        return false;
    }

    private ToolCallOutcome runDbQuery(ToolLoopContext context, PlanStep step, String sql) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sql", sql);
        if (!step.jdbcUrl().isBlank()) {
            args.put("jdbcUrl", step.jdbcUrl());
        }
        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args);
        } catch (Exception error) {
            log.warn("plan.preflight.args_serialize_failed runId={}, stepId={}, cause={}",
                    context.runId(), step.id(), error.getMessage());
            return null;
        }
        ToolCallRequest request = new ToolCallRequest(
                UUID.randomUUID().toString(),
                "db.query",
                argsJson,
                "[preflight:" + step.id() + "]"
        );
        ToolCallDecision decision = new ToolCallDecision(
                true,
                false,
                "preflight_allowed",
                "db.query",
                argsJson
        );
        // Emit TOOL_REQUESTED / TOOL_STARTED before execute so the frontend timeline records
        // the preflight activity. stepId prefix lets the UI group them under the correct step.
        context.emitEvent(
                AgentEventType.TOOL_REQUESTED,
                "step=preflight:" + step.id() + " tool=db.query args=" + argsJson
        );
        context.emitEvent(
                AgentEventType.TOOL_STARTED,
                "tool=db.query"
        );
        try {
            ToolCallOutcome outcome = toolExecutor.execute(context, request, decision);
            String summary = outcome.toolResult() != null ? outcome.toolResult().summary() : "";
            String dataJson = outcome.toolResult() != null ? outcome.toolResult().dataJson() : null;
            // 把 dataJson 也带进 TOOL_COMPLETED 的 content,复盘日志要看 preflight SQL 实际查到的行
            String content = "tool=db.query success=" + outcome.success() + " summary=" + summary;
            if (dataJson != null && !dataJson.isBlank() && !"{}".equals(dataJson.trim())) {
                content = content + System.lineSeparator() + dataJson;
            }
            context.emitEvent(AgentEventType.TOOL_COMPLETED, content);
            // Persist as a tool message in session_message so transcript view after refresh
            // still shows "this SQL was run in preflight" like any other tool call.
            try {
                transcriptService.appendToolMessage(
                        context.sessionId(),
                        context.runId(),
                        "db.query",
                        argsJson,
                        outcome.toolResult() != null ? outcome.toolResult().dataJson() : null,
                        summary
                );
            } catch (Exception persistError) {
                log.warn("plan.preflight.transcript_failed runId={}, cause={}",
                        context.runId(), persistError.getMessage());
            }
            return outcome;
        } catch (Exception error) {
            log.warn("plan.preflight.execute_failed runId={}, stepId={}, cause={}",
                    context.runId(), step.id(), error.getMessage());
            context.emitEvent(
                    AgentEventType.TOOL_COMPLETED,
                    "tool=db.query success=false error=" + error.getMessage()
            );
            return null;
        }
    }

    private int parseRowCount(ToolCallOutcome outcome) {
        if (outcome == null || outcome.toolResult() == null) return 0;
        String data = outcome.toolResult().dataJson();
        if (data == null || data.isBlank()) return 0;
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode rows = root.path("rows");
            return rows.isArray() ? rows.size() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** 抽 rows[0] 的 JSON 表达,给 PlanStepRuleEvaluator 做字段级 acceptance 校验。null=不可用。 */
    private String parseFirstRowJson(ToolCallOutcome outcome) {
        if (outcome == null || outcome.toolResult() == null) return null;
        String data = outcome.toolResult().dataJson();
        if (data == null || data.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode rows = root.path("rows");
            if (!rows.isArray() || rows.isEmpty()) return null;
            JsonNode first = rows.get(0);
            if (first == null || first.isMissingNode() || first.isNull()) return null;
            return objectMapper.writeValueAsString(first);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // substitution + geojson extraction

    private String substitute(String raw, Variant variant, String geoJson) {
        if (raw == null) return "";
        String out = raw;
        if (variant == Variant.GEOJSON && geoJson != null) {
            // LLM 写 SQL 时经常把 {{geometry_json}} 放单引号内,我们的模板也是单引号,直接替换即可
            out = out.replace("{{geometry_json}}", geoJson);
        }
        return out;
    }

    /**
     * Pull the first Polygon/MultiPolygon geometry from a map-regions attachment, serialize
     * it back to a compact JSON string suitable for ST_GeomFromGeoJSON. Returns null when no
     * usable geometry is attached.
     */
    private String extractGeoJsonFromAttachments(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        for (ChatAttachment att : attachments) {
            String ct = att.contentType() == null ? "" : att.contentType();
            if (!ct.contains("map-regions")) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(att.content());
                JsonNode regions = root.path("regions");
                if (!regions.isArray() || regions.isEmpty()) continue;
                for (JsonNode region : regions) {
                    JsonNode geom = region.path("geometry");
                    String type = geom.path("type").asText("");
                    if ("Polygon".equals(type) || "MultiPolygon".equals(type)) {
                        // 压成紧凑单行 JSON 方便直接塞进 SQL 字符串
                        return objectMapper.writeValueAsString(geom);
                    }
                }
            } catch (Exception ignored) {
                // next attachment
            }
        }
        return null;
    }

    private enum Variant { GEOJSON, NO_FILTER }

    /**
     * 用户消息含中文行政区后缀 → 视为有 admin filter 意图,preflight 应该让位给 LLM。
     * 见 {@link #ADMIN_SCOPE_PATTERN} 注释。
     */
    private static boolean hasAdminScopeIntent(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return ADMIN_SCOPE_PATTERN.matcher(userMessage).find();
    }

    /** 日志短摘:截前 60 字符,避免长 message 把 log 撑爆。 */
    private static String snippet(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 60) + "...";
    }
}
