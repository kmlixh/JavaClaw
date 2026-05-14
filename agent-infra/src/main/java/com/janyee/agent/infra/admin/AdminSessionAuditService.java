package com.janyee.agent.infra.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.infra.auth.SessionVisibility;
import com.janyee.agent.infra.persistence.entity.RunEventStepEntity;
import com.janyee.agent.infra.persistence.entity.RunRecordEntity;
import com.janyee.agent.infra.persistence.repository.RunEventStepRepository;
import com.janyee.agent.infra.persistence.repository.RunRecordRepository;
import com.janyee.agent.runtime.admin.AdminRunReplay;
import com.janyee.agent.runtime.admin.AdminRunStep;
import com.janyee.agent.runtime.admin.AdminRunSummary;
import com.janyee.agent.runtime.admin.AdminSessionListResponse;
import com.janyee.agent.runtime.admin.AdminSessionRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理员视角的会话列表 + run 复盘。权限沿用 {@link SessionVisibility} 三档:
 * 系统管理员看全;租户管理员看本租户;普通用户看自己。
 */
@Service
public class AdminSessionAuditService {

    @PersistenceContext
    private EntityManager em;

    private final RunRecordRepository runRecordRepository;
    private final RunEventStepRepository runEventStepRepository;
    private final ObjectMapper objectMapper;

    public AdminSessionAuditService(RunRecordRepository runRecordRepository,
                                    RunEventStepRepository runEventStepRepository,
                                    ObjectMapper objectMapper) {
        this.runRecordRepository = runRecordRepository;
        this.runEventStepRepository = runEventStepRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AdminSessionListResponse listSessions(String userId, String tenantId, String appId,
                                                 String agentId, Instant from, Instant to,
                                                 int page, int size) {
        SessionVisibility vis = SessionVisibility.forCurrent();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object[]> params = new ArrayList<>();
        if (userId != null && !userId.isBlank()) {
            where.append(" AND s.user_id = :p_userId ");
            params.add(new Object[]{"p_userId", userId});
        }
        if (tenantId != null && !tenantId.isBlank()) {
            where.append(" AND s.tenant_id = :p_tenantId ");
            params.add(new Object[]{"p_tenantId", tenantId});
        }
        if (appId != null && !appId.isBlank()) {
            where.append(" AND s.app_id = :p_appId ");
            params.add(new Object[]{"p_appId", appId});
        }
        if (agentId != null && !agentId.isBlank()) {
            where.append(" AND s.agent_id = :p_agentId ");
            params.add(new Object[]{"p_agentId", agentId});
        }
        if (from != null) {
            where.append(" AND s.created_at >= :p_from ");
            params.add(new Object[]{"p_from", from});
        }
        if (to != null) {
            where.append(" AND s.created_at < :p_to ");
            params.add(new Object[]{"p_to", to});
        }
        // 权限过滤
        if (!vis.readAll()) {
            if (vis.readTenant()) {
                where.append(" AND s.tenant_id = :p_visTenantId ");
                params.add(new Object[]{"p_visTenantId", vis.tenantId() == null ? "" : vis.tenantId()});
            } else {
                where.append(" AND s.user_id = :p_visUserId ");
                params.add(new Object[]{"p_visUserId", vis.userId() == null ? "" : vis.userId()});
            }
        }

        // 列表 + 聚合(每条 session 的 message_count / run_count / total_tokens)
        String listSql = "SELECT s.id, s.title, s.agent_id, s.user_id, s.tenant_id, s.app_id, " +
                "       s.channel, s.status, s.created_at, s.updated_at, " +
                "       (SELECT COUNT(*) FROM session_message m WHERE m.session_id = s.id)::bigint AS msg_count, " +
                "       (SELECT COUNT(*) FROM run_record r WHERE r.session_id = s.id)::bigint AS run_count, " +
                "       COALESCE((SELECT SUM(m.total_tokens) FROM session_message m WHERE m.session_id = s.id AND m.role='assistant'), 0)::bigint AS total_tokens " +
                "FROM session s " + where +
                " ORDER BY s.updated_at DESC " +
                " LIMIT :p_limit OFFSET :p_offset";

        var listQuery = em.createNativeQuery(listSql);
        for (Object[] p : params) listQuery.setParameter((String) p[0], p[1]);
        listQuery.setParameter("p_limit", safeSize);
        listQuery.setParameter("p_offset", safePage * safeSize);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = listQuery.getResultList();
        List<AdminSessionRow> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(new AdminSessionRow(
                    str(r[0]), str(r[1]), str(r[2]), str(r[3]),
                    str(r[4]), str(r[5]), str(r[6]), str(r[7]),
                    toInstant(r[8]), toInstant(r[9]),
                    asLong(r[10]), asLong(r[11]), asLong(r[12])
            ));
        }

        // 总计统计(同样的 where,但跨整个表不分页)
        String statSql = "SELECT COUNT(DISTINCT s.id)::bigint, " +
                "       (SELECT COUNT(*) FROM run_record r JOIN session s2 ON s2.id = r.session_id " + where.toString().replace("s.", "s2.") + ")::bigint, " +
                "       (SELECT COUNT(*) FROM session_message m JOIN session s3 ON s3.id = m.session_id " + where.toString().replace("s.", "s3.") + ")::bigint, " +
                "       COALESCE((SELECT SUM(m.total_tokens) FROM session_message m JOIN session s4 ON s4.id = m.session_id " + where.toString().replace("s.", "s4.") + " AND m.role='assistant'), 0)::bigint " +
                "FROM session s " + where;
        var statQuery = em.createNativeQuery(statSql);
        for (Object[] p : params) statQuery.setParameter((String) p[0], p[1]);
        Object[] stat = (Object[]) statQuery.getSingleResult();

        return new AdminSessionListResponse(
                result,
                asLong(stat[0]), asLong(stat[1]), asLong(stat[2]), asLong(stat[3])
        );
    }

    @Transactional(readOnly = true)
    public List<AdminRunSummary> listRunsForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        SessionVisibility vis = SessionVisibility.forCurrent();
        List<RunRecordEntity> all = runRecordRepository.findBySessionId(sessionId);
        List<AdminRunSummary> out = new ArrayList<>(all.size());
        for (RunRecordEntity rr : all) {
            // 权限过滤:每个 run 自己的 tenant_id / user_id 必须可见
            if (!vis.canRead(rr.getTenantId(), rr.getUserId())) continue;
            String log = rr.getEventLogJson();
            boolean hasLog = log != null && !log.isBlank();
            Integer eventCount = hasLog ? estimateEventCount(log) : null;
            out.add(new AdminRunSummary(
                    rr.getId(), rr.getStatus(), rr.getDetail(),
                    rr.getLlmConfigId(), rr.getLlmModel(),
                    rr.getCreatedAt(), rr.getUpdatedAt(),
                    hasLog, eventCount
            ));
        }
        // 倒序按创建时间(最新在前)
        out.sort((a, b) -> {
            if (a.createdAt() == null && b.createdAt() == null) return 0;
            if (a.createdAt() == null) return 1;
            if (b.createdAt() == null) return -1;
            return b.createdAt().compareTo(a.createdAt());
        });
        return out;
    }

    /** 估算事件条数:数 JSON 里 "seq": 出现次数。比解析整段 JSON 快得多,够给 UI 显示。 */
    private Integer estimateEventCount(String json) {
        if (json == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = json.indexOf("\"seq\"", idx)) >= 0) {
            count++;
            idx += 5;
        }
        return count;
    }

    @Transactional(readOnly = true)
    public AdminRunReplay getRunReplay(String runId) {
        RunRecordEntity rr = runRecordRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        // 权限校验:能看本 run 的 session 才能看 replay
        SessionVisibility vis = SessionVisibility.forCurrent();
        if (!vis.canRead(rr.getTenantId(), rr.getUserId())) {
            throw new IllegalArgumentException("forbidden: cannot read this run");
        }
        return new AdminRunReplay(
                rr.getId(), rr.getSessionId(), rr.getAgentId(), rr.getUserId(),
                rr.getTenantId(), rr.getAppId(),
                rr.getLlmConfigId(), rr.getLlmModel(),
                rr.getStatus(), rr.getDetail(), rr.getRequestMessage(),
                rr.getPlanJson(), rr.getEventLogJson(),
                rr.getCreatedAt(), rr.getUpdatedAt()
        );
    }

    /**
     * 行级事件查询(V42 主路径)。
     *
     * <p>{@code categories} 非空 → 服务端只查命中分类的行,前端 chip 直接发回选中集合;
     * 为空 → 返回全量。</p>
     *
     * <p>老 run 没有写入 run_event_step,fallback 到 run_record.event_log_json
     * 即时解析返回,前端不用关心两种数据源。</p>
     */
    @Transactional(readOnly = true)
    public List<AdminRunStep> listRunSteps(String runId, Collection<String> categories) {
        RunRecordEntity rr = runRecordRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        SessionVisibility vis = SessionVisibility.forCurrent();
        if (!vis.canRead(rr.getTenantId(), rr.getUserId())) {
            throw new IllegalArgumentException("forbidden: cannot read this run");
        }

        boolean hasFilter = categories != null && !categories.isEmpty();
        List<RunEventStepEntity> rows = hasFilter
                ? runEventStepRepository.findByRunIdAndCategoryInOrderBySeqAsc(runId, categories)
                : runEventStepRepository.findByRunIdOrderBySeqAsc(runId);

        if (!rows.isEmpty()) {
            List<AdminRunStep> out = new ArrayList<>(rows.size());
            for (RunEventStepEntity e : rows) {
                out.add(new AdminRunStep(
                        e.getId(), e.getSeq(), e.getTs(),
                        e.getCategory(), e.getEventType(), e.getToolName(),
                        e.getIterationNo(), e.getSummary(), e.getPayloadJson(),
                        e.getDurationMs(), e.getSuccess()
                ));
            }
            return out;
        }

        // 老 run fallback:从 event_log_json 即时拆 + 分类
        String json = rr.getEventLogJson();
        if (json == null || json.isBlank()) return List.of();
        List<AdminRunStep> parsed = parseLegacyEventLog(json);
        if (!hasFilter) return parsed;
        List<AdminRunStep> filtered = new ArrayList<>(parsed.size());
        for (AdminRunStep s : parsed) {
            if (categories.contains(s.category())) filtered.add(s);
        }
        return filtered;
    }

    private static final Pattern TOOL_PATTERN = Pattern.compile("tool=([\\w.-]+)");

    /**
     * 老 run event_log_json fallback 解析。规则跟 RunEventCollector#classify
     * 完全一致,保证新老 run 在前端看起来无差别。
     */
    private List<AdminRunStep> parseLegacyEventLog(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) return List.of();
            List<AdminRunStep> out = new ArrayList<>(root.size());
            for (JsonNode evt : root) {
                int seq = evt.path("seq").asInt(0);
                Instant ts;
                try {
                    String t = evt.path("ts").asText("");
                    ts = t.isBlank() ? null : Instant.parse(t);
                } catch (Exception ignored) {
                    ts = null;
                }
                String type = evt.path("type").asText("");
                JsonNode data = evt.path("data");
                String content = data.path("content").asText("");
                String toolFromContent = extractToolName(content);
                String toolFromField = data.path("toolName").asText("");
                String toolName = !toolFromContent.isEmpty() ? toolFromContent
                        : (!toolFromField.isEmpty() ? toolFromField : null);
                Integer iter = data.has("iteration") && !data.path("iteration").isNull()
                        ? data.path("iteration").asInt(0) : null;
                String category = classify(type, content, toolName);
                String summary = buildLegacySummary(type, content, toolName, data);
                Long durationMs = data.has("durationMs") ? data.path("durationMs").asLong(0) : null;
                Boolean success = data.has("success") ? data.path("success").asBoolean(false) : null;
                out.add(new AdminRunStep(
                        null, seq, ts, category, type, toolName,
                        iter, summary, data.toString(), durationMs, success
                ));
            }
            return out;
        } catch (Exception err) {
            return List.of();
        }
    }

    private String extractToolName(String content) {
        if (content == null || content.isEmpty()) return "";
        Matcher m = TOOL_PATTERN.matcher(content);
        return m.find() ? m.group(1) : "";
    }

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

    private String buildLegacySummary(String type, String content, String toolName, JsonNode data) {
        if (type.startsWith("TOOL_") && toolName != null) {
            int idx = content == null ? -1 : content.indexOf(System.lineSeparator());
            String head = idx > 0 ? content.substring(0, idx) : content;
            return head == null || head.isEmpty() ? type + " " + toolName : head;
        }
        if ("PROMPT_SENT".equals(type)) {
            String prompt = data.path("prompt").asText("");
            return "PROMPT iter=" + data.path("iteration").asInt(0) + " · " + preview(prompt, 120);
        }
        if ("LLM_RESPONSE".equals(type)) {
            String reply = data.path("fullText").asText("");
            return "LLM_RESPONSE iter=" + data.path("iteration").asInt(0)
                    + " · finish=" + data.path("finishReason").asText("")
                    + " · " + preview(reply, 120);
        }
        if ("PLAN_UPDATED".equals(type)) return "Plan 快照更新";
        if ("RUN_STARTED".equals(type)) return "run 开始";
        if ("RUN_COMPLETED".equals(type)) return "run 完成 · " + preview(content, 80);
        if ("RUN_FAILED".equals(type) || "RUN_CANCELLED".equals(type))
            return type + " · " + preview(content, 120);
        if ("RUN_STATUS".equals(type)) return preview(content, 80);
        return type;
    }

    private String preview(String s, int max) {
        if (s == null) return "";
        String compact = s.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max) + "…";
    }

    private long asLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(value.toString());
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    private Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }
}
