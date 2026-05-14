package com.janyee.agent.runtime.loop;

import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.runtime.skill.SkillGuard;
import com.janyee.agent.security.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DefaultToolLoopOrchestrator implements ToolLoopOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolLoopOrchestrator.class);
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)\"(password|apiKey|api_key|token|accessToken|access_token)\"\\s*:\\s*\"([^\"]*)\""
    );
    private static final Pattern KV_SECRET_PATTERN = Pattern.compile(
            "(?i)(password|apiKey|api_key|token|accessToken|access_token)\\s*=\\s*([^,\\s\\]}]+)"
    );
    private static final Pattern JSON_FIELD_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DESCRIBE_PATTERN = Pattern.compile("(?is)^\\s*(?:describe|desc)\\s+([\\w.\\\"]+)");
    private static final Pattern SHOW_COLUMNS_PATTERN = Pattern.compile("(?is)^\\s*show\\s+columns\\s+(?:from|in)\\s+([\\w.\\\"]+)");
    private static final Pattern FAKE_ARTIFACT_CLAIM = Pattern.compile(
            "(?is)(报告已(?:生成|保存)|saved\\s+as|已保存为).{0,40}\\.(md|docx|pdf)"
    );
    private static final int MAX_RUN_END_GATE_NUDGES = 5;

    private final ModelTurnExecutor modelTurnExecutor;
    private final ToolCallDetector toolCallDetector;
    private final ToolLoopPolicy toolLoopPolicy;
    private final ToolExecutor toolExecutor;
    private final ToolResultAppender toolResultAppender;
    private final ToolAuditService toolAuditService;
    private final ApprovalService approvalService;

    public DefaultToolLoopOrchestrator(
            ModelTurnExecutor modelTurnExecutor,
            ToolCallDetector toolCallDetector,
            ToolLoopPolicy toolLoopPolicy,
            ToolExecutor toolExecutor,
            ToolResultAppender toolResultAppender,
            ToolAuditService toolAuditService,
            ApprovalService approvalService
    ) {
        this.modelTurnExecutor = modelTurnExecutor;
        this.toolCallDetector = toolCallDetector;
        this.toolLoopPolicy = toolLoopPolicy;
        this.toolExecutor = toolExecutor;
        this.toolResultAppender = toolResultAppender;
        this.toolAuditService = toolAuditService;
        this.approvalService = approvalService;
    }

    @Override
    public ToolLoopResult execute(ToolLoopContext context) {
        context.advanceState(ToolLoopState.INITIALIZING);
        context.validateInvariant();
        log.info("tool.loop.start runId={}, sessionId={}, agentId={}, maxIterations={}",
                context.runId(), context.sessionId(), context.agentId(), context.maxIterations());

        // 跨多轮 LLM 调用累加 token,run 结束时塞进 ToolLoopResult。
        // 供应商不返 usage → tokenSeen 始终 false → 三个值最终给 null,SimpleAgentRunner 落 NULL 列。
        long[] tokenSum = {0, 0, 0};
        boolean[] tokenSeen = {false};

        while (true) {
            if (context.isCancelRequested()) {
                String reason = context.cancelReason();
                log.warn("tool.loop.cancelled runId={}, iteration={}, reason={}",
                        context.runId(), context.iterationCount(), reason);
                context.advanceState(ToolLoopState.CANCELLED);
                return new ToolLoopResult(
                        false,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        reason
                );
            }
            validateIterationLimit(context);
            context.advanceState(context.iterationCount() == 0 ? ToolLoopState.MODEL_REQUESTING : ToolLoopState.MODEL_RESUMING);

            ModelTurnResult modelTurnResult = modelTurnExecutor.executeTurn(context);
            // 累加本轮 token (供应商不返 usage 时 totalTokens=null,跳过)
            if (modelTurnResult.totalTokens() != null) {
                tokenSum[0] += zeroIfNull(modelTurnResult.promptTokens());
                tokenSum[1] += zeroIfNull(modelTurnResult.completionTokens());
                tokenSum[2] += modelTurnResult.totalTokens();
                tokenSeen[0] = true;
            }
            // executeTurn 里可能因为 cancelSignal 被掐断提前返回空 ModelTurnResult;下一轮循环顶部
            // 会看到 cancel flag 并走正规的 CANCELLED 返回,但如果在执行工具前就先动手,会多耗一次
            // 工具执行时间。这里抢先 short-circuit 一次,让 terminate 响应更快。
            if (context.isCancelRequested()) {
                String reason = context.cancelReason();
                log.warn("tool.loop.cancelled_after_model runId={}, iteration={}, reason={}",
                        context.runId(), context.iterationCount(), reason);
                context.advanceState(ToolLoopState.CANCELLED);
                return new ToolLoopResult(
                        false,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        reason
                );
            }
            context.setLastModelRawOutput(modelTurnResult.fullText());
            context.advanceState(ToolLoopState.MODEL_STREAMING);
            context.emitEvent(AgentEventType.RUN_STATUS, renderModelStepSummary(context.iterationCount() + 1, modelTurnResult.fullText()));
            log.info("tool.loop.model_output runId={}, iteration={}, text={}",
                    context.runId(), context.iterationCount(), summarize(modelTurnResult.fullText()));

            Optional<ToolCallRequest> toolCallRequest = toolCallDetector.detect(modelTurnResult);
            if (toolCallRequest.isEmpty()) {
                String nudge = buildRunEndGateNudge(context, modelTurnResult.fullText());
                if (nudge != null) {
                    context.incrementRunEndGateNudgeCount();
                    log.warn("tool.loop.run_end_gated runId={}, iteration={}, nudgeCount={}, reason={}",
                            context.runId(), context.iterationCount(),
                            context.runEndGateNudgeCount(),
                            summarize(nudge));
                    context.setLastModelRawOutput(nudge);
                    context.incrementIteration();
                    context.advanceState(ToolLoopState.MODEL_RESUMING);
                    continue;
                }
                log.info("tool.loop.no_tool_call runId={}, iteration={}, assistantText={}",
                        context.runId(), context.iterationCount(), summarize(modelTurnResult.fullText()));
                context.appendAssistantText(modelTurnResult.fullText());
                context.advanceState(ToolLoopState.COMPLETED);
                return new ToolLoopResult(
                        true,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        null,
                        tokenSeen[0] ? (int) tokenSum[0] : null,
                        tokenSeen[0] ? (int) tokenSum[1] : null,
                        tokenSeen[0] ? (int) tokenSum[2] : null
                );
            }

            context.setPendingToolCall(toolCallRequest.get());
            context.advanceState(ToolLoopState.TOOL_CALL_DETECTED);
            context.emitEvent(
                    AgentEventType.TOOL_REQUESTED,
                    "step=%d tool=%s args=%s".formatted(
                            context.iterationCount() + 1,
                            toolCallRequest.get().toolName(),
                            redactSecrets(toolCallRequest.get().argumentsJson())
                    )
            );
            log.info("tool.loop.tool_detected runId={}, iteration={}, toolName={}, arguments={}",
                    context.runId(), context.iterationCount(),
                    toolCallRequest.get().toolName(),
                    redactSecrets(toolCallRequest.get().argumentsJson()));
            context.advanceState(ToolLoopState.TOOL_POLICY_CHECKING);
            ToolCallDecision decision = toolLoopPolicy.evaluate(context, toolCallRequest.get());
            toolAuditService.recordPolicyDecision(context, toolCallRequest.get(), decision);
            log.info("tool.loop.policy_decision runId={}, toolName={}, allowed={}, approvalRequired={}, normalizedToolName={}, reason={}",
                    context.runId(),
                    toolCallRequest.get().toolName(),
                    decision.allowed(),
                    decision.approvalRequired(),
                    decision.normalizedToolName(),
                    decision.reason());

            Optional<ToolCallOutcome> repeatedOutcome = findRepeatedOutcome(context, decision);
            if (repeatedOutcome.isPresent()) {
                ToolCallOutcome cachedOutcome = repeatedOutcome.get();
                log.warn("tool.loop.repeated_call_detected runId={}, toolName={}, arguments={}",
                        context.runId(), decision.normalizedToolName(), redactSecrets(decision.normalizedArgumentsJson()));
                boolean terminalRender = shouldTerminateAfterRender(context, cachedOutcome);
                context.recordIteration(new ToolLoopIteration(
                        context.iterationCount() + 1,
                        summarize(modelTurnResult.fullText()),
                        toolCallRequest.get(),
                        decision,
                        cachedOutcome,
                        ToolLoopState.TOOL_CALL_DETECTED,
                        terminalRender ? ToolLoopState.COMPLETED : ToolLoopState.MODEL_RESUMING
                ));
                if (terminalRender) {
                    String terminalSummary = renderTerminalSummary(cachedOutcome);
                    context.appendAssistantText(terminalSummary);
                    context.advanceState(ToolLoopState.COMPLETED);
                    return new ToolLoopResult(
                            true,
                            context.state(),
                            context.assistantText(),
                            context.toolOutcomes(),
                            context.iterations(),
                            null,
                            tokenSeen[0] ? (int) tokenSum[0] : null,
                            tokenSeen[0] ? (int) tokenSum[1] : null,
                            tokenSeen[0] ? (int) tokenSum[2] : null
                    );
                }
                context.setLastModelRawOutput("""
                        A repeated non-terminal tool call was detected and skipped.
                        Reuse the previous tool result below instead of calling the same tool with identical arguments.
                        Previous result:
                        %s
                        Cached schema context:
                        %s

                        Next action:
                        - If a chart is part of the final deliverable, embed it in artifact.markdown as a ```echarts {ECharts option JSON}``` fenced block (the frontend renders it). No separate chart tool exists.
                        - Do not call db.query again with the same SQL.
                        """.formatted(
                        renderRepeatedToolResult(decision, cachedOutcome),
                        summarize(safe(context.schemaContext()))
                ));
                context.incrementIteration();
                context.clearPendingToolCall();
                context.advanceState(ToolLoopState.MODEL_RESUMING);
                continue;
            }

            if (!decision.allowed()) {
                // 软拒 vs 硬拒:
                //   - recoverable=true  → 把 reason 当一条工具错误塞回 LLM context,让 LLM 下一轮
                //                        自己纠错(典型场景:plan-step 守卫"先 plan.update 再 db.query"、
                //                        wave barrier 依赖未完、artifact 占位符)。**不**终止 run。
                //   - recoverable=false → run 整体 FAIL,reason 写进 errorMessage。LLM 怎么调都没救的
                //                        系统性拒绝才会走这条。
                if (decision.recoverable()) {
                    log.warn("tool.loop.blocked_recoverable runId={}, toolName={}, reason={}",
                            context.runId(), toolCallRequest.get().toolName(), decision.reason());
                    // 把 policy reason 当一条工具错误结果塞回 LLM context。tool_info displayType
                    // 让前端把它当系统提示而不是工具产物渲染。errorMessage 也写一份,LLM 下一轮
                    // prompt 里能直接看见。
                    ToolResult nudgeResult = new ToolResult(
                            false,
                            decision.reason(),
                            "{\"displayType\":\"tool_info\",\"tool\":\""
                                    + jsonEscape(decision.normalizedToolName()) + "\","
                                    + "\"message\":\"" + jsonEscape(decision.reason()) + "\"}",
                            "[]",
                            decision.reason()
                    );
                    ToolCallOutcome nudgeOutcome = new ToolCallOutcome(
                            toolCallRequest.get(),
                            decision,
                            false,
                            false,
                            nudgeResult,
                            decision.reason(),
                            0L
                    );
                    context.emitEvent(AgentEventType.TOOL_COMPLETED, renderToolCompletionContent(nudgeOutcome));
                    toolAuditService.recordExecutionOutcome(context, nudgeOutcome);
                    context.addToolOutcome(nudgeOutcome);
                    context.advanceState(ToolLoopState.TOOL_RESULT_APPENDING);
                    toolResultAppender.append(context, nudgeOutcome);
                    context.incrementIteration();
                    context.recordIteration(new ToolLoopIteration(
                            context.iterationCount(),
                            summarize(modelTurnResult.fullText()),
                            toolCallRequest.get(),
                            decision,
                            nudgeOutcome,
                            ToolLoopState.TOOL_CALL_DETECTED,
                            ToolLoopState.MODEL_RESUMING
                    ));
                    context.clearPendingToolCall();
                    context.advanceState(ToolLoopState.MODEL_RESUMING);
                    continue;
                }
                log.warn("tool.loop.blocked runId={}, toolName={}, reason={}",
                        context.runId(), toolCallRequest.get().toolName(), decision.reason());
                context.recordIteration(new ToolLoopIteration(
                        context.iterationCount() + 1,
                        summarize(modelTurnResult.fullText()),
                        toolCallRequest.get(),
                        decision,
                        null,
                        ToolLoopState.TOOL_CALL_DETECTED,
                        ToolLoopState.FAILED
                ));
                context.advanceState(ToolLoopState.FAILED);
                return new ToolLoopResult(
                        false,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        decision.reason()
                );
            }

            if (decision.approvalRequired()) {
                String approvalRequestId = approvalService.createRequest(
                        context.runId(),
                        context.sessionId(),
                        context.agentId(),
                        decision.normalizedToolName(),
                        decision.normalizedArgumentsJson(),
                        decision.reason()
                );
                log.info("tool.loop.waiting_approval runId={}, toolName={}, approvalRequestId={}, reason={}",
                        context.runId(), decision.normalizedToolName(), approvalRequestId, decision.reason());
                context.recordIteration(new ToolLoopIteration(
                        context.iterationCount() + 1,
                        summarize(modelTurnResult.fullText()),
                        toolCallRequest.get(),
                        decision,
                        null,
                        ToolLoopState.TOOL_CALL_DETECTED,
                        ToolLoopState.WAITING_APPROVAL
                ));
                context.advanceState(ToolLoopState.WAITING_APPROVAL);
                return new ToolLoopResult(
                        false,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        decision.reason() + ": " + approvalRequestId
                );
            }

            if (isRedundantSchemaInspection(context, decision)) {
                String schemaKey = schemaInspectionKey(decision).orElse("unknown");
                String skipReason = "schema inspection already completed for " + schemaKey + "; reuse stored schema context";
                ToolResult skippedResult = new ToolResult(
                        true,
                        "schema inspection reused for " + schemaKey,
                        "{\"displayType\":\"tool_info\",\"tool\":\"db.query\","
                                + "\"message\":\"schema inspection already completed for this table; reuse existing schema context\","
                                + "\"schemaKey\":\"" + jsonEscape(schemaKey) + "\"}",
                        "[]",
                        null
                );
                ToolCallOutcome outcome = new ToolCallOutcome(
                        toolCallRequest.get(),
                        decision,
                        false,
                        true,
                        skippedResult,
                        null,
                        0L
                );
                context.emitEvent(AgentEventType.TOOL_COMPLETED, renderToolCompletionContent(outcome));
                toolAuditService.recordExecutionOutcome(context, outcome);
                context.addToolOutcome(outcome);
                log.warn("tool.loop.schema_probe_skipped runId={}, toolName={}, reason={}",
                        context.runId(), decision.normalizedToolName(), skipReason);
                context.advanceState(ToolLoopState.TOOL_RESULT_APPENDING);
                toolResultAppender.append(context, outcome);
                context.incrementIteration();
                context.recordIteration(new ToolLoopIteration(
                        context.iterationCount(),
                        summarize(modelTurnResult.fullText()),
                        toolCallRequest.get(),
                        decision,
                        outcome,
                        ToolLoopState.TOOL_CALL_DETECTED,
                        ToolLoopState.MODEL_RESUMING
                ));
                context.clearPendingToolCall();
                continue;
            }

            context.advanceState(ToolLoopState.TOOL_EXECUTING);
            context.emitEvent(
                    AgentEventType.TOOL_STARTED,
                    "tool=%s".formatted(decision.normalizedToolName())
            );
            log.info("tool.loop.executing runId={}, toolName={}, arguments={}",
                    context.runId(), decision.normalizedToolName(), redactSecrets(decision.normalizedArgumentsJson()));
            ToolCallOutcome outcome = toolExecutor.execute(context, toolCallRequest.get(), decision);
            context.emitEvent(AgentEventType.TOOL_COMPLETED, renderToolCompletionContent(outcome));
            toolAuditService.recordExecutionOutcome(context, outcome);
            context.addToolOutcome(outcome);
            // Reset the run-end-gate budget when the LLM made real progress (a successful
            // plan.update that advances a step to COMPLETED, or an artifact.* success).
            // Without this, 2 nudges burn the entire budget even though LLM obediently
            // continued the plan in between.
            if (outcome.success() && didAdvancePlan(decision, outcome)) {
                context.resetRunEndGateNudgeCount();
            }
            log.info("tool.loop.executed runId={}, toolName={}, success={}, durationMs={}, error={}, summary={}",
                    context.runId(),
                    decision.normalizedToolName(),
                    outcome.success(),
                    outcome.durationMillis(),
                    outcome.errorMessage(),
                    outcome.toolResult() != null ? summarize(outcome.toolResult().summary()) : "");
            if (shouldTerminateAfterRender(context, outcome)) {
                context.recordIteration(new ToolLoopIteration(
                        context.iterationCount() + 1,
                        summarize(modelTurnResult.fullText()),
                        toolCallRequest.get(),
                        decision,
                        outcome,
                        ToolLoopState.TOOL_CALL_DETECTED,
                        ToolLoopState.COMPLETED
                ));
                String terminalSummary = renderTerminalSummary(outcome);
                context.appendAssistantText(terminalSummary);
                context.advanceState(ToolLoopState.COMPLETED);
                log.info("tool.loop.render_terminated runId={}, toolName={}, summary={}",
                        context.runId(),
                        decision.normalizedToolName(),
                        summarize(terminalSummary));
                return new ToolLoopResult(
                        true,
                        context.state(),
                        context.assistantText(),
                        context.toolOutcomes(),
                        context.iterations(),
                        null,
                        tokenSeen[0] ? (int) tokenSum[0] : null,
                        tokenSeen[0] ? (int) tokenSum[1] : null,
                        tokenSeen[0] ? (int) tokenSum[2] : null
                );
            }
            context.advanceState(ToolLoopState.TOOL_RESULT_APPENDING);
            toolResultAppender.append(context, outcome);
            context.incrementIteration();
            context.recordIteration(new ToolLoopIteration(
                    context.iterationCount(),
                    summarize(modelTurnResult.fullText()),
                    toolCallRequest.get(),
                    decision,
                    outcome,
                    ToolLoopState.TOOL_CALL_DETECTED,
                    ToolLoopState.MODEL_RESUMING
            ));
            context.clearPendingToolCall();
        }
    }

    private void validateIterationLimit(ToolLoopContext context) {
        if (context.iterationCount() >= context.maxIterations()) {
            log.error("tool.loop.max_iterations_exceeded runId={}, sessionId={}, current={}, max={}, iterations=\n{}",
                    context.runId(),
                    context.sessionId(),
                    context.iterationCount(),
                    context.maxIterations(),
                    formatIterations(context.iterations()));
            context.advanceState(ToolLoopState.FAILED);
            throw new MaxToolIterationExceededException(context.iterationCount(), context.maxIterations());
        }
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160);
    }

    private String renderModelStepSummary(int stepNo, String text) {
        String summary = summarize(text == null ? "" : text.replace('\n', ' ').trim());
        if (summary.isBlank()) {
            summary = "模型正在分析当前请求";
        }
        // turn-end 事件统一标 MODEL_OUTPUT。之前用 detectPhase 按关键词猜 QUERYING/
        // SCHEMA/RENDERING,但 LLM 聊天里随便提一句"查询"就被误判成 QUERYING,横幅状态
        // 严重失真。实际要做什么后面 TOOL_REQUESTED 事件会准确表达,这里不需要抢答。
        return "step=%d phase=MODEL_OUTPUT %s".formatted(stepNo, summary);
    }

    private String renderToolCompletionContent(ToolCallOutcome outcome) {
        if (outcome == null) {
            return "";
        }
        String toolName = outcome.decision() != null
                ? safe(outcome.decision().normalizedToolName())
                : safe(outcome.request() != null ? outcome.request().toolName() : "");
        String summary = outcome.toolResult() != null
                ? safe(outcome.toolResult().summary())
                : safe(outcome.errorMessage());
        String dataJson = outcome.toolResult() != null ? safe(outcome.toolResult().dataJson()) : "";
        // 所有工具的返回数据都进 TOOL_COMPLETED content,复盘要看 SQL 查到的行、file.list 列出的文件...
        // 之前 shouldEmitRenderablePayload 只对 artifact.* 放行,db.query / file.list / web.fetch 等结果直接丢,
        // 复盘日志里看不到 AI 实际拿到了什么数据,等于没法 debug 它为啥做出某个判断。
        if (dataJson.isBlank() || "{}".equals(dataJson.trim())) {
            return "tool=%s success=%s summary=%s".formatted(toolName, outcome.success(), summary);
        }
        return "tool=%s success=%s summary=%s".formatted(toolName, outcome.success(), summary)
                + System.lineSeparator()
                + dataJson;
    }

    private Optional<ToolCallOutcome> findRepeatedOutcome(ToolLoopContext context, ToolCallDecision decision) {
        return context.toolOutcomes().stream()
                .filter(outcome -> outcome.decision() != null)
                .filter(outcome -> decision.normalizedToolName().equals(outcome.decision().normalizedToolName()))
                .filter(outcome -> safe(decision.normalizedArgumentsJson()).equals(safe(outcome.decision().normalizedArgumentsJson())))
                .reduce((first, second) -> second);
    }

    private String renderRepeatedToolResult(ToolCallDecision decision, ToolCallOutcome outcome) {
        if (outcome.toolResult() == null) {
            return "Tool " + decision.normalizedToolName() + " failed previously: " + safe(outcome.errorMessage());
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Tool ").append(decision.normalizedToolName()).append(" result").append(System.lineSeparator());
        builder.append(safe(outcome.toolResult().summary()));
        String dataJson = outcome.toolResult().dataJson();
        if (dataJson != null && !dataJson.isBlank() && !"{}".equals(dataJson.trim())) {
            builder.append(System.lineSeparator()).append(dataJson);
        }
        return builder.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String detectPhase(String summary) {
        if (summary == null || summary.isBlank()) {
            return "THINKING";
        }
        String lower = summary.toLowerCase();
        if (lower.contains("图表") || lower.contains("可视化") || lower.contains("echarts")
                || lower.contains("render") || lower.contains("绘制")) {
            return "RENDERING";
        }
        if (lower.contains("schema") || lower.contains("表结构")
                || lower.contains("columns") || lower.contains("字段")) {
            return "SCHEMA";
        }
        if (lower.contains("sql") || lower.contains("查询")
                || lower.contains("db.query") || lower.contains("统计")) {
            return "QUERYING";
        }
        return "THINKING";
    }

    private String redactSecrets(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = JSON_SECRET_PATTERN.matcher(value)
                .replaceAll("\"$1\":\"***\"");
        return KV_SECRET_PATTERN.matcher(redacted)
                .replaceAll("$1=***");
    }

    private boolean isRedundantSchemaInspection(ToolLoopContext context, ToolCallDecision decision) {
        if (context == null || decision == null) {
            return false;
        }
        return schemaInspectionKey(decision)
                .map(context::hasSchemaContext)
                .orElse(false);
    }

    private Optional<String> schemaInspectionKey(ToolCallDecision decision) {
        if (decision == null) {
            return Optional.empty();
        }
        String toolName = safe(decision.normalizedToolName());
        String args = safe(decision.normalizedArgumentsJson());
        if ("db.schema.inspect".equals(toolName)) {
            String table = jsonField(args, "table");
            if (table.isBlank()) {
                return Optional.empty();
            }
            String schema = jsonField(args, "schema");
            String jdbcUrl = jsonField(args, "jdbcUrl");
            String qualified = table.contains(".") || schema.isBlank() ? table : schema + "." + table;
            return Optional.of(schemaKey(jdbcUrl, qualified));
        }
        if (!"db.query".equals(toolName)) {
            return Optional.empty();
        }
        String sql = unescapeJsonString(jsonField(args, "sql"));
        if (sql.isBlank()) {
            return Optional.empty();
        }
        String table = firstMatch(DESCRIBE_PATTERN, sql);
        if (table.isBlank()) {
            table = firstMatch(SHOW_COLUMNS_PATTERN, sql);
        }
        if (table.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(schemaKey(jsonField(args, "jdbcUrl"), table));
    }

    private String jsonField(String json, String fieldName) {
        if (json == null || json.isBlank() || fieldName == null || fieldName.isBlank()) {
            return "";
        }
        Pattern pattern = Pattern.compile(JSON_FIELD_PATTERN.pattern().formatted(Pattern.quote(fieldName)), Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String firstMatch(Pattern pattern, String value) {
        java.util.regex.Matcher matcher = pattern.matcher(safe(value));
        return matcher.find() ? matcher.group(1) : "";
    }

    private String schemaKey(String jdbcUrl, String qualifiedName) {
        return (safe(jdbcUrl) + "|" + safe(qualifiedName).replace("\"", "")).trim().toLowerCase();
    }

    private String jsonEscape(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String unescapeJsonString(String value) {
        return safe(value)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /**
     * A successful plan.update that flips a step to COMPLETED, or any successful artifact.*
     * call, counts as forward progress — refresh the run-end-gate budget so the LLM doesn't
     * run out of nudges while actually obeying them.
     */
    private boolean didAdvancePlan(ToolCallDecision decision, ToolCallOutcome outcome) {
        if (decision == null) {
            return false;
        }
        String toolName = safe(decision.normalizedToolName());
        if (toolName.startsWith("artifact.")) {
            return true;
        }
        if (!"plan.update".equals(toolName)) {
            return false;
        }
        String args = safe(decision.normalizedArgumentsJson());
        return args.contains("\"status\"") && args.contains("COMPLETED");
    }

    /**
     * When the model stops emitting tool calls but the active skill's required plan steps
     * aren't all COMPLETED (or a required artifact deliverable was never actually produced),
     * we refuse to let the run end. Returns a nudge string to feed into the next turn, or
     * null if termination is legitimate.
     *
     * The gate fires at most {@link #MAX_RUN_END_GATE_NUDGES} times per run so a stuck model
     * doesn't spin indefinitely.
     */
    private String buildRunEndGateNudge(ToolLoopContext context, String finalText) {
        SkillGuard guard = context.guard();
        if (guard == null || guard.isEmpty() || !guard.hasPlanEnforcement()) {
            return null;
        }
        if (context.runEndGateNudgeCount() >= MAX_RUN_END_GATE_NUDGES) {
            return null;
        }
        RunPlan plan = context.runPlan();
        List<String> requiredIds = guard.requiredPlanStepIds();

        // An empty plan with required step IDs is worse than an incomplete plan — the LLM
        // skipped the plan contract entirely. Force a plan.create before allowing run-end.
        if (plan == null || plan.isEmpty()) {
            StringBuilder planMissingNudge = new StringBuilder();
            planMissingNudge.append("[run-end gate] DO NOT end the run yet.\n")
                    .append("The active skill requires you to register a plan via plan.create ")
                    .append("with EXACTLY these step IDs in order: ").append(requiredIds).append(".\n")
                    .append("Then plan.update each step through IN_PROGRESS → COMPLETED as you execute it, ")
                    .append("and finally call artifact.markdown before closing the 'report' step.\n")
                    .append("Do not emit a final answer until the full plan has run.");
            return planMissingNudge.toString();
        }

        List<String> unfinished = requiredIds.stream()
                .filter(id -> plan.find(id).map(step -> step.status() != PlanStatus.COMPLETED
                        && step.status() != PlanStatus.SKIPPED).orElse(true))
                .collect(Collectors.toList());
        boolean fabricatedArtifact = looksLikeFakeArtifactClaim(finalText, context);

        if (unfinished.isEmpty() && !fabricatedArtifact) {
            return null;
        }

        StringBuilder nudge = new StringBuilder();
        nudge.append("[run-end gate] DO NOT end the run yet.\n");
        if (!unfinished.isEmpty()) {
            nudge.append("The active skill requires every plan step in ")
                    .append(requiredIds)
                    .append(" to reach COMPLETED before you emit a final answer.\n")
                    .append("Still unfinished: ").append(unfinished).append(".\n")
                    .append("Resume by calling the tool that advances the next unfinished step ")
                    .append("(db.query for data steps, artifact.markdown for the report step), ")
                    .append("then plan.update to flip it to COMPLETED. ")
                    .append("Zero-row results are not a reason to stop — write '0' or '(数据暂缺)' and continue.\n");
        }
        if (fabricatedArtifact) {
            nudge.append("Your last text claims a .md/.docx/.pdf artifact was saved, but no successful ")
                    .append("artifact.* tool call exists in this run. Call artifact.markdown with the real ")
                    .append("content now — the orchestrator checks the tool audit, not the assistant text.\n");
        }
        nudge.append("Do not repeat this final-answer text. Resume tool calls until every required step is COMPLETED.");
        return nudge.toString();
    }

    private boolean looksLikeFakeArtifactClaim(String finalText, ToolLoopContext context) {
        if (finalText == null || finalText.isBlank()) {
            return false;
        }
        if (!FAKE_ARTIFACT_CLAIM.matcher(finalText).find()) {
            return false;
        }
        return context.toolOutcomes().stream().noneMatch(o ->
                o != null
                        && o.success()
                        && o.decision() != null
                        && safe(o.decision().normalizedToolName()).startsWith("artifact.")
        );
    }

    private boolean shouldTerminateAfterRender(ToolLoopContext context, ToolCallOutcome outcome) {
        // 不存在独立的图表/表格渲染工具 —— 图表以 ```echarts``` 代码块、表格以 GFM 表格形式
        // 直接写进 artifact.markdown。所以没有"渲染成功立即终止 loop"的触发点;一律返回 false,
        // LLM 下一轮不再调工具就会命中 tool.loop.no_tool_call 路径优雅结束。
        return false;
    }

    private String renderTerminalSummary(ToolCallOutcome outcome) {
        String summary = outcome.toolResult() != null ? safe(outcome.toolResult().summary()) : "";
        return summary.isBlank() ? "结果已生成。" : summary;
    }

    private String formatIterations(java.util.List<ToolLoopIteration> iterations) {
        if (iterations == null || iterations.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (ToolLoopIteration iteration : iterations) {
            builder.append("#").append(iteration.iterationNo())
                    .append(" start=").append(iteration.startState())
                    .append(" end=").append(iteration.endState())
                    .append(System.lineSeparator())
                    .append("  model=").append(safe(iteration.modelRequestSummary()))
                    .append(System.lineSeparator());
            if (iteration.toolCallRequest() != null) {
                builder.append("  tool=").append(iteration.toolCallRequest().toolName())
                        .append(System.lineSeparator())
                        .append("  args=").append(redactSecrets(safe(iteration.toolCallRequest().argumentsJson())))
                        .append(System.lineSeparator());
            }
            if (iteration.decision() != null) {
                builder.append("  decision allowed=").append(iteration.decision().allowed())
                        .append(", approvalRequired=").append(iteration.decision().approvalRequired())
                        .append(", normalizedTool=").append(safe(iteration.decision().normalizedToolName()))
                        .append(", reason=").append(safe(iteration.decision().reason()))
                        .append(System.lineSeparator());
            }
            if (iteration.outcome() != null) {
                builder.append("  outcome success=").append(iteration.outcome().success())
                        .append(", durationMs=").append(iteration.outcome().durationMillis())
                        .append(", error=").append(safe(iteration.outcome().errorMessage()))
                        .append(System.lineSeparator());
                if (iteration.outcome().toolResult() != null) {
                    builder.append("  summary=").append(safe(iteration.outcome().toolResult().summary()))
                            .append(System.lineSeparator());
                }
            }
        }
        return builder.toString().trim();
    }

    private static long zeroIfNull(Integer value) {
        return value == null ? 0L : value.longValue();
    }
}
