package com.janyee.agent.runtime.usage;

/**
 * 一行聚合结果。{@code groupKey} 是分组维度名(tenant_id/app_id/user_id/model/none),
 * {@code groupValue} 是该维度的具体值(null = 该列没数据,例如旧 message 没记 model)。
 *
 * <p>tokens 字段全 long 而非 int —— SUM(integer) 在 PG 会自动升 bigint,接 long 更稳;
 * Integer 还容易在月报量级溢出。</p>
 */
public record UsageSummaryRow(
        String groupKey,
        String groupValue,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long messageCount,
        long runCount,
        long sessionCount
) {
}
