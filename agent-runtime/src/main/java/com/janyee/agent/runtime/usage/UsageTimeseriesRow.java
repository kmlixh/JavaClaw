package com.janyee.agent.runtime.usage;

import java.time.Instant;

/**
 * 时间序列一个数据点。{@code bucketStart} 是按 interval 截断后的时间桶起点
 * (interval=day → 当天 00:00 UTC;interval=hour → 当前小时 00 分)。
 * groupValue 为 null 时表示"全部数据未分维度"的总趋势。
 */
public record UsageTimeseriesRow(
        Instant bucketStart,
        String groupKey,
        String groupValue,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long messageCount
) {
}
