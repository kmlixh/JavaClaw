package com.janyee.agent.runtime.query;

public interface AgentQueryService {
    java.util.List<SessionSummaryView> listSessions(String agentId);

    SessionDetailView getSession(String sessionId);

    RunDetailView getRun(String runId);

    java.util.Optional<RunDetailView> findActiveRun(String sessionId);

    java.util.List<RunSummaryView> listRunsBySession(String sessionId);

    java.util.List<MemoryNoteView> listMemoryNotes(String agentId);

    java.util.List<SessionMessageView> searchMessages(String query, String sessionId);

    java.util.List<MemoryNoteView> searchMemoryNotes(String agentId, String query);

    java.util.List<ArtifactView> searchArtifacts(String runId, String query);
}
