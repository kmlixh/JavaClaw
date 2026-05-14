package com.janyee.agent.runtime.admin;

import java.util.List;

/** 会话列表 API 响应:当前页 rows + 总计统计(应用了筛选条件) + 总行数(给分页用)。 */
public record AdminSessionListResponse(
        List<AdminSessionRow> rows,
        long totalSessions,
        long totalRuns,
        long totalMessages,
        long totalTokens
) {
}
