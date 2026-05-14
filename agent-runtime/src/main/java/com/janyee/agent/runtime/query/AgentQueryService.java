package com.janyee.agent.runtime.query;

public interface AgentQueryService {
    /** 兼容老调用,等价于 listSessions(agentId, 20)。 */
    default java.util.List<SessionSummaryView> listSessions(String agentId) {
        return listSessions(agentId, 20);
    }

    /** 按 updatedAt desc 拉前 limit 条 session(权限自动过滤);limit 上限 500 防滥查。 */
    java.util.List<SessionSummaryView> listSessions(String agentId, int limit);

    /**
     * 真分页查询 session 列表(权限自动按 SessionVisibility 过滤)。
     * keyword 非空时按 title / id / agentId 子串模糊匹配。
     * 返回 {@link SessionPage} 含 rows + total + 当前 page + size。
     */
    SessionPage listSessionsPaged(String agentId, int page, int size, String keyword);

    /**
     * 增强版分页查询:在 {@link #listSessionsPaged(String,int,int,String)} 基础上加 userId/tenantId/
     * appId/runStatus 多维过滤,给会话管理页用。
     *
     * <ul>
     *   <li>userId / tenantId / appId 非 null/blank 时做精确匹配(注意 tenantId 过滤仍受
     *       SessionVisibility 约束:非 readAll 不能跨租户过滤)</li>
     *   <li>runStatus 取值 "running"(有 in-progress run 的 session) /
     *       "idle"(没有 in-progress run) / "all" 或 null/blank(不过滤)。靠 EXISTS 子查询 join
     *       run_record 实现,不展平到 join。</li>
     * </ul>
     */
    SessionPage listSessionsPagedAdvanced(SessionListFilter filter);

    SessionDetailView getSession(String sessionId);

    RunDetailView getRun(String runId);

    java.util.Optional<RunDetailView> findActiveRun(String sessionId);

    java.util.List<RunSummaryView> listRunsBySession(String sessionId);

    /**
     * 当前用户(按 SessionVisibility)有权看到的所有 in-progress run。
     * 给左下角"正在运行"dock 和会话列表"运行中"过滤用。结果按 updated_at 倒序。
     */
    java.util.List<RunDetailView> listVisibleActiveRuns();

    java.util.List<MemoryNoteView> listMemoryNotes(String agentId);

    java.util.List<SessionMessageView> searchMessages(String query, String sessionId);

    java.util.List<MemoryNoteView> searchMemoryNotes(String agentId, String query);

    java.util.List<ArtifactView> searchArtifacts(String runId, String query);
}
