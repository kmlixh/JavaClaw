package com.janyee.agent.runtime.query;

import java.util.List;

/** listSessionsPaged 的返回:当前页 rows + total + page + size。 */
public record SessionPage(
        List<SessionSummaryView> rows,
        long total,
        int page,
        int size
) {
}
