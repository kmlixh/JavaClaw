package com.janyee.agent.infra.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Postgres 分区表探测。暴露三个问题的答案：
 * <ol>
 *   <li>该表是否是分区父表 ({@code pg_class.relkind = 'p'});</li>
 *   <li>分区键是哪一列（对 RANGE 分区通常是 dt_record / dt_month 等时间列）;</li>
 *   <li>按 child relname 降序取第一个 —— 也就是"最新"的子分区。</li>
 * </ol>
 *
 * <p>之所以用 relname 降序而不是解析 {@code relpartbound}：业务侧约定的命名模板为
 * {@code <parent>_partition_YYYY_MM_DD}，按名字字典序反向排就是按日期倒序 —— 对 1000+ 个
 * 历史分区来说，这比每个都跑 {@code pg_get_expr} 再解析边界要便宜一到两个数量级。
 * 如果后面遇到命名不遵守这个约定的父表，再用 {@code relpartbound} 做 fallback。</p>
 *
 * <p>缓存策略：按 fingerprint(jdbcUrl + schema.table) 缓存 {@code PartitionInfo}
 * {@value #CACHE_TTL_MILLIS} ms。分区表每日新增一个子分区，5 分钟内的 staleness 对"取最新分区"
 * 几乎没有影响，但能避免每次 db.query 都去读三次 pg_catalog。</p>
 */
final class PartitionInspector {

    private static final Logger log = LoggerFactory.getLogger(PartitionInspector.class);

    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    private static final String IS_PARTITIONED_SQL = """
            SELECT c.relkind = 'p' AS partitioned
            FROM pg_class c
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = ? AND c.relname = ?
            """;

    private static final String PARTITION_KEY_SQL = """
            SELECT a.attname
            FROM pg_partitioned_table pt
            JOIN pg_class c ON pt.partrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(pt.partattrs)
            WHERE n.nspname = ? AND c.relname = ?
            ORDER BY array_position(pt.partattrs::int[], a.attnum::int)
            LIMIT 1
            """;

    private static final String LATEST_PARTITION_SQL = """
            SELECT n2.nspname AS child_schema,
                   c2.relname AS child_name,
                   pg_get_expr(c2.relpartbound, c2.oid) AS bound
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhparent
            JOIN pg_namespace n ON c.relnamespace = n.oid
            JOIN pg_class c2 ON c2.oid = i.inhrelid
            JOIN pg_namespace n2 ON c2.relnamespace = n2.oid
            WHERE n.nspname = ? AND c.relname = ?
            ORDER BY c2.relname DESC
            LIMIT 1
            """;

    private static final String CHILD_COUNT_SQL = """
            SELECT COUNT(*)
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhparent
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = ? AND c.relname = ?
            """;

    private static final Map<String, CachedInfo> CACHE = new ConcurrentHashMap<>();

    private PartitionInspector() {
    }

    /**
     * 对非 Postgres 数据库直接返回 {@link PartitionInfo#regular()} —— 其它库不会命中 pg_class。
     */
    static PartitionInfo inspect(Connection connection, String jdbcUrl, String schema, String table) {
        if (connection == null || schema == null || schema.isBlank() || table == null || table.isBlank()) {
            return PartitionInfo.regular();
        }
        String cacheKey = cacheKey(jdbcUrl, schema, table);
        CachedInfo cached = CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.info();
        }
        try {
            if (!isPostgres(connection)) {
                return PartitionInfo.regular();
            }
            boolean partitioned = queryPartitioned(connection, schema, table);
            if (!partitioned) {
                PartitionInfo info = PartitionInfo.regular();
                CACHE.put(cacheKey, new CachedInfo(System.currentTimeMillis(), info));
                return info;
            }
            String partitionKey = queryPartitionKey(connection, schema, table);
            LatestChild latest = queryLatestChild(connection, schema, table);
            int childCount = queryChildCount(connection, schema, table);
            PartitionInfo info = new PartitionInfo(
                    true,
                    partitionKey,
                    latest.qualifiedName(),
                    latest.bound(),
                    childCount
            );
            log.info("partition.inspect schema={}, table={}, partitionKey={}, latestChild={}, childCount={}",
                    schema, table, partitionKey, info.latestPartition(), childCount);
            CACHE.put(cacheKey, new CachedInfo(System.currentTimeMillis(), info));
            return info;
        } catch (Exception error) {
            log.warn("partition.inspect.failed schema={}, table={}, error={}", schema, table, error.getMessage());
            return PartitionInfo.regular();
        }
    }

    // VisibleForTesting
    static void invalidateCache() {
        CACHE.clear();
    }

    private static boolean queryPartitioned(Connection connection, String schema, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(IS_PARTITIONED_SQL)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static String queryPartitionKey(Connection connection, String schema, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(PARTITION_KEY_SQL)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private static LatestChild queryLatestChild(Connection connection, String schema, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(LATEST_PARTITION_SQL)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new LatestChild("", "");
                }
                String childSchema = rs.getString("child_schema");
                String childName = rs.getString("child_name");
                String bound = rs.getString("bound");
                String qualified = (childSchema == null ? "" : childSchema) + "."
                        + (childName == null ? "" : childName);
                return new LatestChild(qualified, bound == null ? "" : bound);
            }
        }
    }

    private static int queryChildCount(Connection connection, String schema, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(CHILD_COUNT_SQL)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static boolean isPostgres(Connection connection) {
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String cacheKey(String jdbcUrl, String schema, String table) {
        return ((jdbcUrl == null ? "" : jdbcUrl) + "|" + schema + "." + table).toLowerCase(Locale.ROOT);
    }

    /**
     * 注意 {@code latestPartition} 始终带 schema 前缀，便于直接替换到 SQL 的 FROM 子句中。
     */
    record PartitionInfo(
            boolean isPartitioned,
            String partitionKey,
            String latestPartition,
            String latestPartitionBound,
            int childCount
    ) {
        PartitionInfo {
            partitionKey = partitionKey == null ? "" : partitionKey;
            latestPartition = latestPartition == null ? "" : latestPartition;
            latestPartitionBound = latestPartitionBound == null ? "" : latestPartitionBound;
            if (childCount < 0) {
                childCount = 0;
            }
        }

        static PartitionInfo regular() {
            return new PartitionInfo(false, "", "", "", 0);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("isPartitioned", isPartitioned);
            map.put("partitionKey", partitionKey);
            map.put("latestPartition", latestPartition);
            map.put("latestPartitionBound", latestPartitionBound);
            map.put("childCount", childCount);
            return map;
        }
    }

    private record LatestChild(String qualifiedName, String bound) {
    }

    private record CachedInfo(long createdAtMillis, PartitionInfo info) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAtMillis > CACHE_TTL_MILLIS;
        }
    }

    /**
     * 返回 fallback 候选：如果缓存未命中、schema/table 缺失，或底层库不是 Postgres，返回常规空信息。
     * 在 {@link DatabaseQueryTool} 中使用 {@link Optional} 语义更清晰。
     */
    static Optional<PartitionInfo> inspectOptional(Connection connection, String jdbcUrl, String schema, String table) {
        PartitionInfo info = inspect(connection, jdbcUrl, schema, table);
        return info.isPartitioned() && !info.latestPartition().isBlank()
                ? Optional.of(info)
                : Optional.empty();
    }
}
