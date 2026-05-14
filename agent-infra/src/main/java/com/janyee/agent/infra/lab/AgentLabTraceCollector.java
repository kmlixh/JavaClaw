package com.janyee.agent.infra.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.entity.SessionMessageEntity;
import com.janyee.agent.infra.persistence.entity.ToolAuditLogEntity;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.infra.persistence.repository.SessionMessageRepository;
import com.janyee.agent.infra.persistence.repository.ToolAuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 跑完一次 chat run 后,把过程数据(run_record / session_message / tool_audit_log)
 * 拼成结构化 JSON,喂给 meta-LLM 做"为什么失败 / 怎么改" 的诊断。
 *
 * <p>输出结构:
 * <pre>
 * {
 *   "runId": "...",
 *   "sessionId": "...",
 *   "runStatus": "COMPLETED",
 *   "runDetail": "...",
 *   "assistantTexts": ["..."],
 *   "tokenUsage": {"prompt": N, "completion": N, "total": N},
 *   "toolCalls": [
 *     {"id": L, "phase": "POLICY_DECISION", "tool": "db.query",
 *      "allowed": true, "executed": null, "success": null, "reason": "...",
 *      "summary": null, "errorMessage": null}
 *   ]
 * }
 * </pre>
 * </p>
 *
 * <p>大字段(arguments_json / result_summary)做截断:防 trace 单条 ≥ 100KB 把 meta-LLM
 * prompt 撑爆。重要的是"是否成功 + 失败原因",不是完整 args。</p>
 */
@Component
public class AgentLabTraceCollector {

    private static final int FIELD_TRUNCATE_AT = 800;

    private final ObjectMapper objectMapper;
    private final RunRecordRepository runRecordRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final ToolAuditLogRepository toolAuditLogRepository;

    public AgentLabTraceCollector(ObjectMapper objectMapper,
                                  RunRecordRepository runRecordRepository,
                                  SessionMessageRepository sessionMessageRepository,
                                  ToolAuditLogRepository toolAuditLogRepository) {
        this.objectMapper = objectMapper;
        this.runRecordRepository = runRecordRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.toolAuditLogRepository = toolAuditLogRepository;
    }

    @Transactional(readOnly = true)
    public ObjectNode collect(String sessionId, String runId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("sessionId", sessionId);
        root.put("runId", runId);

        runRecordRepository.findById(runId).ifPresent(rr -> appendRunRecord(root, rr));
        appendAssistantMessages(root, sessionId, runId);
        appendToolAudit(root, runId);

        return root;
    }

    private void appendRunRecord(ObjectNode root, RunRecordEntity rr) {
        root.put("runStatus", rr.getStatus());
        root.put("runDetail", truncate(rr.getDetail(), FIELD_TRUNCATE_AT));
    }

    private void appendAssistantMessages(ObjectNode root, String sessionId, String runId) {
        List<SessionMessageEntity> all = sessionMessageRepository.findBySessionIdOrderBySeqNoAsc(sessionId);
        ArrayNode texts = objectMapper.createArrayNode();
        Integer prompt = null, completion = null, total = null;
        for (SessionMessageEntity m : all) {
            if (!runId.equals(m.getRunId())) continue;
            if (!"assistant".equalsIgnoreCase(m.getRole())) continue;
            String content = m.getContent() == null ? "" : m.getContent();
            texts.add(truncate(content, FIELD_TRUNCATE_AT * 2));   // assistant 文本可放宽,LLM 主要看
            // 取最后一条 assistant 的 token 用量(整 run 的累计)
            if (m.getTotalTokens() != null) {
                prompt = m.getPromptTokens();
                completion = m.getCompletionTokens();
                total = m.getTotalTokens();
            }
        }
        root.set("assistantTexts", texts);
        if (total != null) {
            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("prompt", prompt == null ? 0 : prompt);
            usage.put("completion", completion == null ? 0 : completion);
            usage.put("total", total);
            root.set("tokenUsage", usage);
        }
    }

    private void appendToolAudit(ObjectNode root, String runId) {
        List<ToolAuditLogEntity> audits = toolAuditLogRepository.findByRunIdOrderByIdAsc(runId);
        ArrayNode arr = objectMapper.createArrayNode();
        for (ToolAuditLogEntity a : audits) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", a.getId());
            node.put("phase", a.getPhase());
            node.put("tool", a.getToolName());
            node.put("allowed", a.isAllowed());
            node.put("approvalRequired", a.isApprovalRequired());
            if (a.getExecuted() != null) node.put("executed", a.getExecuted());
            if (a.getSuccess() != null) node.put("success", a.getSuccess());
            if (a.getReason() != null) node.put("reason", truncate(a.getReason(), FIELD_TRUNCATE_AT));
            if (a.getResultSummary() != null) node.put("summary", truncate(a.getResultSummary(), FIELD_TRUNCATE_AT));
            if (a.getErrorMessage() != null) node.put("errorMessage", truncate(a.getErrorMessage(), FIELD_TRUNCATE_AT));
            arr.add(node);
        }
        root.set("toolCalls", arr);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
