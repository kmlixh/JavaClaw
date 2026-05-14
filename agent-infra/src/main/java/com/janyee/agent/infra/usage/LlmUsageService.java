package com.janyee.agent.infra.usage;

import com.janyee.agent.runtime.usage.UsageRecentRow;
import com.janyee.agent.runtime.usage.UsageSummaryRow;
import com.janyee.agent.runtime.usage.UsageTimeseriesRow;
import com.janyee.agent.infra.auth.SessionVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LLM token 用量统计服务。
 *
 * <p>数据来源:{@code session_message} 表的 prompt_tokens/completion_tokens/total_tokens
 * 三列(只有 role='assistant' 的行有非空值)。维度信息(tenant_id/app_id/user_id)从
 * {@code session} 表 join 拿。</p>
 *
 * <p>权限模型:沿用 {@link SessionVisibility} 三档 ——
 * <ul>
 *   <li>系统管理员(session.read.all 或 anonymous)→ 看全</li>
 *   <li>租户管理员(session.read.tenant)→ 只看本租户</li>
 *   <li>普通用户 → 只看自己 user_id</li>
 * </ul>
 * 在 SQL 的 WHERE 里直接拼,SQL 注入由 setParameter 防御。</p>
 *
 * <p>聚合 SQL 用原生 PG 而非 JPQL —— PG 的 date_trunc / SUM(integer) → bigint 这些跨方言行为
 * Hibernate 抽象起来麻烦,直接 native 更直观。这个项目本身就是只跑 PG 的,无方言切换需求。</p>
 */
@Service
public class LlmUsageService {

    private static final Set<String> ALLOWED_GROUP_BY = Set.of("tenant", "app", "user", "model", "agent", "none");
    private static final Set<String> ALLOWED_INTERVAL = Set.of("hour", "day");

    @PersistenceContext
    private EntityManager em;

    /**
     * 按 groupBy 维度聚合 [from, to] 区间内的 token 用量。groupBy=none 返回单行总计。
     * groupBy=model 跑 join run_record 拿 llm_model;其他维度直接读 session 表的字段。
     */
    @Transactional(readOnly = true)
    public List<UsageSummaryRow> summary(Instant from, Instant to, String groupBy) {
        String normalizedGroup = normalizeGroupBy(groupBy);
        SessionVisibility vis = SessionVisibility.forCurrent();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        appendGroupExpr(sql, normalizedGroup, "alias_value");
        sql.append(", COALESCE(SUM(sm.prompt_tokens), 0)::bigint AS prompt_tok ")
           .append(", COALESCE(SUM(sm.completion_tokens), 0)::bigint AS completion_tok ")
           .append(", COALESCE(SUM(sm.total_tokens), 0)::bigint AS total_tok ")
           .append(", COUNT(*)::bigint AS msg_count ")
           .append(", COUNT(DISTINCT sm.run_id)::bigint AS run_count ")
           .append(", COUNT(DISTINCT sm.session_id)::bigint AS sess_count ")
           .append("FROM session_message sm ")
           .append("JOIN session s ON s.id = sm.session_id ");
        if ("model".equals(normalizedGroup)) {
            sql.append("LEFT JOIN run_record r ON r.id = sm.run_id ");
        }
        sql.append("WHERE sm.role = 'assistant' AND sm.total_tokens IS NOT NULL ")
           .append("AND sm.created_at >= :fromTs AND sm.created_at < :toTs ");
        appendVisibilityWhere(sql, vis);
        if (!"none".equals(normalizedGroup)) {
            sql.append("GROUP BY alias_value ");
        }
        sql.append("ORDER BY total_tok DESC ");

        var query = em.createNativeQuery(sql.toString());
        query.setParameter("fromTs", from);
        query.setParameter("toTs", to);
        bindVisibilityParams(query, vis);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<UsageSummaryRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String groupValue = r[0] == null ? null : r[0].toString();
            out.add(new UsageSummaryRow(
                    normalizedGroup,
                    groupValue,
                    asLong(r[1]),
                    asLong(r[2]),
                    asLong(r[3]),
                    asLong(r[4]),
                    asLong(r[5]),
                    asLong(r[6])
            ));
        }
        return out;
    }

    /**
     * 按时间桶聚合。interval=day(UTC 当天 00:00 起)或 hour。
     * groupBy=none 返回单维度趋势;否则每个组一条折线。前端按 (groupValue, bucketStart) 拆线。
     */
    @Transactional(readOnly = true)
    public List<UsageTimeseriesRow> timeseries(Instant from, Instant to, String interval, String groupBy) {
        String normalizedInterval = normalizeInterval(interval);
        String normalizedGroup = normalizeGroupBy(groupBy);
        SessionVisibility vis = SessionVisibility.forCurrent();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT date_trunc('").append(normalizedInterval).append("', sm.created_at) AS bucket_start, ");
        appendGroupExpr(sql, normalizedGroup, "alias_value");
        sql.append(", COALESCE(SUM(sm.prompt_tokens), 0)::bigint AS prompt_tok ")
           .append(", COALESCE(SUM(sm.completion_tokens), 0)::bigint AS completion_tok ")
           .append(", COALESCE(SUM(sm.total_tokens), 0)::bigint AS total_tok ")
           .append(", COUNT(*)::bigint AS msg_count ")
           .append("FROM session_message sm ")
           .append("JOIN session s ON s.id = sm.session_id ");
        if ("model".equals(normalizedGroup)) {
            sql.append("LEFT JOIN run_record r ON r.id = sm.run_id ");
        }
        sql.append("WHERE sm.role = 'assistant' AND sm.total_tokens IS NOT NULL ")
           .append("AND sm.created_at >= :fromTs AND sm.created_at < :toTs ");
        appendVisibilityWhere(sql, vis);
        sql.append("GROUP BY bucket_start");
        if (!"none".equals(normalizedGroup)) {
            sql.append(", alias_value");
        }
        sql.append(" ORDER BY bucket_start ASC");
        if (!"none".equals(normalizedGroup)) {
            sql.append(", alias_value ASC");
        }

        var query = em.createNativeQuery(sql.toString());
        query.setParameter("fromTs", from);
        query.setParameter("toTs", to);
        bindVisibilityParams(query, vis);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<UsageTimeseriesRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Instant bucket = toInstant(r[0]);
            String groupValue = r[1] == null ? null : r[1].toString();
            out.add(new UsageTimeseriesRow(
                    bucket,
                    normalizedGroup,
                    groupValue,
                    asLong(r[2]),
                    asLong(r[3]),
                    asLong(r[4]),
                    asLong(r[5])
            ));
        }
        return out;
    }

    /** 最近 N 条 assistant 消息(按 created_at desc),给"明细" tab 用。limit 上限 500 防滥查。 */
    @Transactional(readOnly = true)
    public List<UsageRecentRow> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        SessionVisibility vis = SessionVisibility.forCurrent();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT sm.id, sm.session_id, s.title, sm.run_id, s.agent_id, ")
           .append("       s.tenant_id, s.app_id, s.user_id, ")
           .append("       sm.prompt_tokens, sm.completion_tokens, sm.total_tokens, sm.created_at ")
           .append("FROM session_message sm ")
           .append("JOIN session s ON s.id = sm.session_id ")
           .append("WHERE sm.role = 'assistant' AND sm.total_tokens IS NOT NULL ");
        appendVisibilityWhere(sql, vis);
        sql.append("ORDER BY sm.created_at DESC LIMIT :lim");

        var query = em.createNativeQuery(sql.toString());
        query.setParameter("lim", safeLimit);
        bindVisibilityParams(query, vis);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<UsageRecentRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new UsageRecentRow(
                    asLong(r[0]),
                    str(r[1]),
                    str(r[2]),
                    str(r[3]),
                    str(r[4]),
                    str(r[5]),
                    str(r[6]),
                    str(r[7]),
                    asInt(r[8]),
                    asInt(r[9]),
                    asInt(r[10]),
                    toInstant(r[11])
            ));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // SQL 拼接 helpers

    /** 把 groupBy 维度名映射到一个 SQL 表达式 + 取别名。 */
    private void appendGroupExpr(StringBuilder sql, String groupBy, String alias) {
        switch (groupBy) {
            case "tenant" -> sql.append("s.tenant_id AS ").append(alias);
            case "app"    -> sql.append("s.app_id    AS ").append(alias);
            case "user"   -> sql.append("s.user_id   AS ").append(alias);
            case "agent"  -> sql.append("s.agent_id  AS ").append(alias);
            case "model"  -> sql.append("r.llm_model AS ").append(alias);
            case "none"   -> sql.append("NULL::text  AS ").append(alias);
            default       -> throw new IllegalArgumentException("unsupported groupBy: " + groupBy);
        }
        sql.append(" ");
    }

    /**
     * 按 SessionVisibility 三档拼 WHERE 子句。注意:这里的 `:visTenantId` / `:visUserId`
     * 必须在 bindVisibilityParams 一起 set,否则 createNativeQuery 会抛 missing parameter。
     */
    private void appendVisibilityWhere(StringBuilder sql, SessionVisibility vis) {
        if (vis.readAll()) {
            return;
        }
        if (vis.readTenant()) {
            sql.append("AND s.tenant_id = :visTenantId ");
        } else {
            sql.append("AND s.user_id = :visUserId ");
        }
    }

    private void bindVisibilityParams(jakarta.persistence.Query query, SessionVisibility vis) {
        if (vis.readAll()) {
            return;
        }
        if (vis.readTenant()) {
            query.setParameter("visTenantId", vis.tenantId() == null ? "" : vis.tenantId());
        } else {
            query.setParameter("visUserId", vis.userId() == null ? "" : vis.userId());
        }
    }

    private String normalizeGroupBy(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) return "none";
        String low = groupBy.toLowerCase(Locale.ROOT);
        if (!ALLOWED_GROUP_BY.contains(low)) {
            throw new IllegalArgumentException("groupBy must be one of " + ALLOWED_GROUP_BY + ", got: " + groupBy);
        }
        return low;
    }

    private String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank()) return "day";
        String low = interval.toLowerCase(Locale.ROOT);
        if (!ALLOWED_INTERVAL.contains(low)) {
            throw new IllegalArgumentException("interval must be one of " + ALLOWED_INTERVAL + ", got: " + interval);
        }
        return low;
    }

    // ---------------------------------------------------------------------------------------
    // 类型 helpers — JPA native query 默认返 Number / java.sql.* 等,统一收口

    private long asLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(value.toString());
    }

    private Integer asInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Native query 的 TIMESTAMPTZ 列在不同环境会返回不同 Java 类型:
     *   - 较老的 driver / Hibernate 6.x with PgJdbc 42.x → java.sql.Timestamp
     *   - 较新的 PgJdbc + Hibernate 6.5 + jsr310 datatype → java.time.OffsetDateTime
     *   - 某些环境直接返回 java.time.Instant
     * 之前死硬强转 java.sql.Timestamp,Postgres 17 + 最新 driver 下直接 ClassCastException
     * 整个 /api/usage/timeseries 和 /recent 500。这个 helper 把三种都吃掉,保持一致。
     */
    private Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.time.LocalDateTime ldt) return ldt.toInstant(java.time.ZoneOffset.UTC);
        return null;
    }
}
