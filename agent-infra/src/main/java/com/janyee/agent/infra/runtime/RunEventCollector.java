package com.janyee.agent.infra.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-run 内存累计 + flush 到 run_record.event_log_json 的事件采集器。
 *
 * <p>用于事后完整复盘整个 run 流程,前端按时间线 / 流程图渲染。</p>
 *
 * <h3>记录范围(B2 策略 + LLM 输入输出)</h3>
 * <ul>
 *   <li>{@code RUN_STARTED} / {@code RUN_COMPLETED} / {@code RUN_FAILED} / {@code RUN_CANCELLED}</li>
 *   <li>{@code PROMPT_SENT} —— 每次 LLM 调用前发的完整 prompt + 模型 / configId / iteration 序号</li>
 *   <li>{@code LLM_RESPONSE} —— 每次 LLM 调用结束:fullText + finishReason + usage(若有) + thinking</li>
 *   <li>{@code TOOL_REQUESTED} / {@code TOOL_POLICY_DECISION} / {@code TOOL_EXECUTED}</li>
 *   <li>{@code APPROVAL_REQUIRED}</li>
 *   <li>{@code PLAN_UPDATED} —— plan snapshot</li>
 *   <li>{@code RUN_STATUS}(非 MODEL_OUTPUT 阶段,如 PREFLIGHT / MODEL_RETRY 等)</li>
 *   <li>{@code TOKEN_USAGE}</li>
 * </ul>
 *
 * <h3>不记录(去噪)</h3>
 * <ul>
 *   <li>{@code RUN_STATUS} 中 phase=MODEL_OUTPUT 的流式累积(700ms 一条,大量重复)</li>
 *   <li>{@code TOKEN_DELTA}(每 chunk)</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 一个 run 可能被多个线程写入(SSE emit / preflight 并发 / model turn / tool exec),
 * 用 {@link ConcurrentHashMap} + 同步块保证 ArrayNode 的 add 原子。
 */
@Component
public class RunEventCollector implements com.janyee.agent.runtime.run.RunEventSink {

    private static final Logger log = LoggerFactory.getLogger(RunEventCollector.class);

    // 不限事件数、不限字段长度 —— 复盘必须看到完整 prompt / response / 工具 args / 工具输出。
    // PostgreSQL TEXT 列上限 ~1GB,单 run 几 MB 完全 OK。
    // 之前 2000 条 + 16000 字符截断对长 run / 大 artifact 直接砍尾,等于没复盘。
    private static final int MAX_EVENTS_PER_RUN = Integer.MAX_VALUE;

    private final ObjectMapper objectMapper;
    private final com.janyee.agent.infra.persistence.repository.RunEventStepRepository stepRepository;
    private final ConcurrentHashMap<String, RunBuffer> running = new ConcurrentHashMap<>();

    public RunEventCollector(ObjectMapper objectMapper,
                             com.janyee.agent.infra.persistence.repository.RunEventStepRepository stepRepository) {
        this.objectMapper = objectMapper;
        this.stepRepository = stepRepository;
    }

    /** 起一个 run 的事件流。重复 start 不报错(直接接管)。 */
    public void start(String runId) {
        if (runId == null || runId.isBlank()) return;
        running.putIfAbsent(runId, new RunBuffer());
    }

    /**
     * 追加一条"去噪后"的事件。runId 不存在 buffer 时自动 start(兜底,避免漏 SSE)。
     * 这条事件同时进 {@code events}(用于 event_log_json + run_event_step 行表)和 {@code rawEvents}
     * (raw_log_text 全量日志)。
     */
    public void append(String runId, String type, Map<String, Object> data) {
        if (runId == null || runId.isBlank() || type == null) return;
        RunBuffer buf = running.computeIfAbsent(runId, k -> new RunBuffer());
        if (buf.events.size() >= MAX_EVENTS_PER_RUN) {
            return;
        }
        ObjectNode node = buildEventNode(buf, type, data);
        synchronized (buf.events) {
            buf.events.add(node);
        }
        synchronized (buf.rawEvents) {
            buf.rawEvents.add(node);
        }
    }

    /**
     * 只追加到 raw 缓冲,不进 curated 流。给 TOKEN_DELTA / streaming MODEL_OUTPUT 这种高频但
     * UI 复盘视图不需要的事件用 —— 我们仍然想在 raw_log_text 里看到完整流式过程,只是不让它
     * 把 event_log_json / run_event_step 撑爆。
     */
    public void appendRawOnly(String runId, String type, Map<String, Object> data) {
        if (runId == null || runId.isBlank() || type == null) return;
        RunBuffer buf = running.computeIfAbsent(runId, k -> new RunBuffer());
        ObjectNode node = buildEventNode(buf, type, data);
        synchronized (buf.rawEvents) {
            buf.rawEvents.add(node);
        }
    }

    private ObjectNode buildEventNode(RunBuffer buf, String type, Map<String, Object> data) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ts", Instant.now().toString());
        node.put("seq", buf.seq.incrementAndGet());
        node.put("type", type);
        ObjectNode dataNode = objectMapper.createObjectNode();
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    dataNode.putNull(entry.getKey());
                } else if (value instanceof Number num) {
                    dataNode.set(entry.getKey(), objectMapper.valueToTree(num));
                } else if (value instanceof Boolean bool) {
                    dataNode.put(entry.getKey(), bool);
                } else {
                    // 不再截断 —— 复盘看完整内容才有意义
                    dataNode.put(entry.getKey(), value.toString());
                }
            }
        }
        node.set("data", dataNode);
        return node;
    }

    /**
     * 序列化 + 行级落表 + 清理。run 结束调一次:
     *  1) 把"去噪 events"批量 insert 到 run_event_step(显式带 category / tool_name / iteration_no / summary)
     *  2) 返回 {@link FlushResult},含 curated JSON(写到 run_record.event_log_json)和 raw JSON
     *     (写到 run_record.raw_log_text,后者含 TOKEN_DELTA / streaming MODEL_OUTPUT 全量)
     *
     * 行级表是前端复盘的主路径;event_log_json 留作灾备;raw_log_text 给细粒度 debug 用。
     */
    public FlushResult flushAndPersist(String runId, String sessionId) {
        if (runId == null) return null;
        RunBuffer buf = running.remove(runId);
        if (buf == null) return null;

        // 1) 批量 insert rows(只从 curated 流出 —— rawEvents 太密会把行表撑爆)
        try {
            java.util.List<com.janyee.agent.infra.persistence.entity.RunEventStepEntity> rows
                    = new java.util.ArrayList<>(buf.events.size());
            for (com.fasterxml.jackson.databind.JsonNode evt : buf.events) {
                rows.add(toStepEntity(runId, sessionId, evt));
            }
            stepRepository.saveAll(rows);
        } catch (Exception persistErr) {
            log.warn("run.event_collector.step_persist_failed runId={}, count={}, cause={}",
                    runId, buf.events.size(), persistErr.getMessage());
            // 不抛 —— event_log_json / raw_log_text 兜底
        }

        // 2) 双路 JSON 备份
        String curatedJson = serializeOrNull(buf.events, runId, "curated");
        String rawJson = serializeOrNull(buf.rawEvents, runId, "raw");
        return new FlushResult(curatedJson, rawJson);
    }

    private String serializeOrNull(ArrayNode events, String runId, String kind) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception error) {
            log.warn("run.event_collector.flush_failed runId={}, kind={}, cause={}",
                    runId, kind, error.getMessage());
            return null;
        }
    }

    /** 老调用点兼容:不传 sessionId,只走 JSON 备份路径,行级表不写;返回 curated JSON。 */
    public String flush(String runId) {
        FlushResult r = flushAndPersist(runId, null);
        return r == null ? null : r.curatedJson();
    }

    /**
     * Flush 返回值:curated 进 event_log_json,raw 进 raw_log_text。
     * 任一字段为 null 表示序列化失败,调用方应跳过对应 attach。
     */
    public record FlushResult(String curatedJson, String rawJson) { }

    /**
     * 把单条 buffer 里的事件 node 转成行级 entity:
     *   - category   = 据 type / content 启发式判定(跟前端 chip 同口径)
     *   - tool_name  = 从 data.content 提取 "tool=xxx",或 data.toolName
     *   - summary    = 一句话简述(前端列表直接显示,不展开 payload 也能看明白)
     */
    private com.janyee.agent.infra.persistence.entity.RunEventStepEntity toStepEntity(
            String runId, String sessionId, com.fasterxml.jackson.databind.JsonNode evt) {
        com.janyee.agent.infra.persistence.entity.RunEventStepEntity row =
                new com.janyee.agent.infra.persistence.entity.RunEventStepEntity();
        row.setRunId(runId);
        row.setSessionId(sessionId);
        row.setSeq(evt.path("seq").asInt(0));
        String tsStr = evt.path("ts").asText("");
        try {
            row.setTs(tsStr.isBlank() ? Instant.now() : Instant.parse(tsStr));
        } catch (Exception ignored) {
            row.setTs(Instant.now());
        }
        String type = evt.path("type").asText("");
        row.setEventType(type);
        com.fasterxml.jackson.databind.JsonNode data = evt.path("data");
        String content = data.path("content").asText("");
        String toolFromContent = extractToolName(content);
        String toolFromField = data.path("toolName").asText("");
        String toolName = !toolFromContent.isEmpty() ? toolFromContent
                : (!toolFromField.isEmpty() ? toolFromField : null);
        row.setToolName(toolName);
        Integer iter = data.has("iteration") && !data.path("iteration").isNull()
                ? data.path("iteration").asInt(0) : null;
        row.setIterationNo(iter);
        row.setCategory(classify(type, content, toolName));
        row.setSummary(buildSummary(type, content, toolName, data));
        row.setPayloadJson(data.toString());
        // tool 类事件如果 data 里带 success / durationMs(后续可补埋点),抽出来
        if (data.has("success")) row.setSuccess(data.path("success").asBoolean(false));
        if (data.has("durationMs")) row.setDurationMs(data.path("durationMs").asLong(0));
        return row;
    }

    private String extractToolName(String content) {
        if (content == null || content.isEmpty()) return "";
        java.util.regex.Matcher m = TOOL_PATTERN.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    /** 跟前端 classifyReplayEvent 同口径,后端这里 once-for-all 写进 DB,前端不再二次分类。 */
    private String classify(String type, String content, String toolName) {
        if ("PROMPT_SENT".equals(type) || "LLM_RESPONSE".equals(type) || "LLM_ATTEMPT_FAILED".equals(type)) {
            return "ai";
        }
        if (type.startsWith("TOOL_") || "APPROVAL_REQUIRED".equals(type)) {
            if (toolName == null) return "tool-other";
            if (toolName.startsWith("db.") || "database.query".equals(toolName)) return "db";
            if (toolName.startsWith("file.") || toolName.startsWith("workspace.")
                    || "doc.normalize".equals(toolName)) return "file";
            if (toolName.startsWith("artifact.")) return "artifact";
            return "tool-other";
        }
        if ("PLAN_UPDATED".equals(type)) return "plan";
        if ("RUN_STATUS".equals(type)) {
            if (content != null && (content.contains("phase=MODEL_THINKING") || content.contains("phase=MODEL_RETRY"))) return "ai";
            return "lifecycle";
        }
        if ("RUN_STARTED".equals(type) || "RUN_COMPLETED".equals(type)
                || "RUN_FAILED".equals(type) || "RUN_CANCELLED".equals(type)
                || "TOKEN_USAGE".equals(type)) return "lifecycle";
        return "tool-other";
    }

    private String buildSummary(String type, String content, String toolName,
                                com.fasterxml.jackson.databind.JsonNode data) {
        // 工具事件:`tool=db.query summary=...` 这种直接用前面那段
        if (type.startsWith("TOOL_") && toolName != null) {
            int idx = content == null ? -1 : content.indexOf(System.lineSeparator());
            String head = idx > 0 ? content.substring(0, idx) : content;
            return head == null ? type + " " + toolName : head;
        }
        // AI 事件:把 prompt / response 的开头 120 字截一下,够标识
        if ("PROMPT_SENT".equals(type)) {
            String prompt = data.path("prompt").asText("");
            return "PROMPT iter=" + data.path("iteration").asInt(0) + " · " + previewText(prompt, 120);
        }
        if ("LLM_RESPONSE".equals(type)) {
            String reply = data.path("fullText").asText("");
            return "LLM_RESPONSE iter=" + data.path("iteration").asInt(0)
                    + " · finish=" + data.path("finishReason").asText("")
                    + " · " + previewText(reply, 120);
        }
        if ("PLAN_UPDATED".equals(type)) return "Plan 快照更新";
        if ("RUN_STARTED".equals(type)) return "run 开始";
        if ("RUN_COMPLETED".equals(type)) return "run 完成 · " + previewText(content, 80);
        if ("RUN_FAILED".equals(type) || "RUN_CANCELLED".equals(type))
            return type + " · " + previewText(content, 120);
        if ("RUN_STATUS".equals(type)) return previewText(content, 80);
        return type;
    }

    private String previewText(String s, int max) {
        if (s == null) return "";
        String compact = s.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max) + "…";
    }

    private static final java.util.regex.Pattern TOOL_PATTERN =
            java.util.regex.Pattern.compile("tool=([\\w.-]+)");

    /** 取消 / 异常时调,丢弃累积。 */
    public void discard(String runId) {
        if (runId != null) running.remove(runId);
    }

    // ===== RunEventSink 实现:cross-module 入口,给 agent-runtime 组件埋点用 =====

    @Override
    public void recordPromptSent(String runId, int iteration, String llmConfigId, String llmModel, String prompt) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("iteration", iteration);
        if (llmConfigId != null) data.put("llmConfigId", llmConfigId);
        if (llmModel != null) data.put("llmModel", llmModel);
        data.put("promptLength", prompt == null ? 0 : prompt.length());
        data.put("prompt", prompt == null ? "" : prompt);
        append(runId, "PROMPT_SENT", data);
    }

    @Override
    public void recordLlmResponse(String runId, int iteration, String finishReason, String fullText,
                                  String thinking, Integer promptTokens, Integer completionTokens,
                                  Integer totalTokens) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("iteration", iteration);
        if (finishReason != null) data.put("finishReason", finishReason);
        data.put("fullTextLength", fullText == null ? 0 : fullText.length());
        if (fullText != null) data.put("fullText", fullText);
        if (thinking != null && !thinking.isBlank()) data.put("thinking", thinking);
        if (totalTokens != null) {
            data.put("promptTokens", promptTokens == null ? 0 : promptTokens);
            data.put("completionTokens", completionTokens == null ? 0 : completionTokens);
            data.put("totalTokens", totalTokens);
        }
        append(runId, "LLM_RESPONSE", data);
    }

    @Override
    public void recordLlmAttemptFailed(String runId, int iteration, int attempt,
                                       String errorType, String errorMessage) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("iteration", iteration);
        data.put("attempt", attempt);
        if (errorType != null) data.put("errorType", errorType);
        if (errorMessage != null) data.put("errorMessage", errorMessage);
        append(runId, "LLM_ATTEMPT_FAILED", data);
    }

    private static class RunBuffer {
        /** 去噪后事件,进 event_log_json + run_event_step。 */
        final ArrayNode events;
        /** 完整未过滤事件(含 TOKEN_DELTA / streaming MODEL_OUTPUT),进 raw_log_text。 */
        final ArrayNode rawEvents;
        final AtomicLong seq = new AtomicLong(0);

        RunBuffer() {
            ObjectMapper m = new ObjectMapper();
            this.events = m.createArrayNode();
            this.rawEvents = m.createArrayNode();
        }
    }
}
