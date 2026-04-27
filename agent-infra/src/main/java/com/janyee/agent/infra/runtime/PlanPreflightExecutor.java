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

/**
 * L1 并行化 —— 在 LLM tool loop 开始前,把"输入已完全就绪、SQL 模板已物化、彼此独立"的
 * plan step 用 Reactor 扇出并发执行完,省掉 LLM 在数据获取阶段的无谓来回。
 *
 * <h3>判定 step 可 preflight 的条件(保守)</h3>
 * <ul>
 *   <li>step 状态仍是 PENDING;</li>
 *   <li>至少有一套 SQL 模板(geojson / noFilter)在用户上下文里可用 —— L1 不处理 admin filter,
 *       城市/区县名抽取留给 L2;</li>
 *   <li>toolHint 不是 artifact.* —— 合成性 step(report)必须 LLM 参与,不能程序执行。</li>
 * </ul>
 *
 * <p>成功 preflight 的 step 直接标 COMPLETED 并挂上 CompletedToolSummary,LLM 从 wave 1 起手
 * 时看到的就是"已有数据的 plan",不需要再发 db.query 请求。失败的 step 保持 PENDING,LLM
 * 会按原来的路径 fallback 处理。</p>
 */
@Component
public class PlanPreflightExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanPreflightExecutor.class);
    private static final int DEFAULT_CONCURRENCY = 3;

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

    /** Returns the number of steps successfully preflighted. */
    public int runWave0(ToolLoopContext context, List<ChatAttachment> attachments) {
        RunPlan plan = context.runPlan();
        if (plan == null || plan.isEmpty()) {
            return 0;
        }
        String geoJson = extractGeoJsonFromAttachments(attachments);
        Variant variant = geoJson != null ? Variant.GEOJSON : Variant.NO_FILTER;

        List<PlanStep> candidates = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            if (isEligible(step, variant)) {
                candidates.add(step);
            }
        }
        if (candidates.isEmpty()) {
            return 0;
        }

        log.info("plan.preflight.start runId={}, variant={}, candidateCount={}, stepIds={}",
                context.runId(), variant, candidates.size(),
                candidates.stream().map(PlanStep::id).toList());
        context.emitEvent(
                AgentEventType.RUN_STATUS,
                "phase=PREFLIGHT 并行预执行 %d 个已就绪 step: %s".formatted(
                        candidates.size(),
                        candidates.stream().map(PlanStep::id).toList())
        );

        AtomicInteger successCount = new AtomicInteger(0);
        // Reactor 扇出:maxConcurrency=3 覆盖典型 wave 1 的 3 路独立查询(skill.coverage.analysis
        // 的 sector/weak_area/coverage)。太高会把后端 DB 连接池抢空;太低失去并行意义。
        Flux.fromIterable(candidates)
                .flatMap(step -> Mono
                                .fromCallable(() -> executeStepOffMainThread(context, step, variant, geoJson))
                                .subscribeOn(Schedulers.boundedElastic())
                                .doOnNext(ok -> { if (ok) successCount.incrementAndGet(); })
                                .onErrorResume(error -> {
                                    log.warn("plan.preflight.step_errored runId={}, stepId={}, cause={}",
                                            context.runId(), step.id(), error.getMessage());
                                    return Mono.just(Boolean.FALSE);
                                }),
                        DEFAULT_CONCURRENCY)
                .blockLast();

        planPersister.sync(context.runId(), plan);
        // Preflight 完成后把整张 plan 快照再推一次,前端面板直接看到 wave 0 step 已 COMPLETED。
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
                        .formatted(successCount.get(), candidates.size())
        );
        log.info("plan.preflight.finish runId={}, succeeded={}, total={}",
                context.runId(), successCount.get(), candidates.size());
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
        step.updateStatus(PlanStatus.IN_PROGRESS);
        boolean allOk = true;
        int rowCountSum = 0;
        for (String raw : templates) {
            String sql = substitute(raw, variant, geoJson);
            ToolCallOutcome outcome = runDbQuery(context, step, sql);
            if (outcome == null || !outcome.executed() || !outcome.success()) {
                allOk = false;
                break;
            }
            rowCountSum += parseRowCount(outcome);
            step.attachToolSummary(CompletedToolSummary.of(
                    "db.query",
                    sql,
                    outcome.toolResult() != null ? outcome.toolResult().summary() : "",
                    rowCountSum,
                    step.id()
            ));
        }
        if (allOk) {
            step.updateStatus(PlanStatus.COMPLETED);
            step.updateResultNote("preflight: auto-executed " + templates.size()
                    + " SQL, rowCountSum=" + rowCountSum);
            return true;
        }
        step.updateStatus(PlanStatus.PENDING);
        step.updateResultNote("preflight partial/failed — LLM will continue normally");
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
            context.emitEvent(
                    AgentEventType.TOOL_COMPLETED,
                    "tool=db.query success=" + outcome.success() + " summary=" + summary
            );
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
}
