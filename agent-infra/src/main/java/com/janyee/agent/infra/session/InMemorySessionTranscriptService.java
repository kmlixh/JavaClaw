package com.janyee.agent.infra.session;

import com.janyee.agent.infra.persistence.entity.SessionEntity;
import com.janyee.agent.infra.persistence.entity.SessionMessageEntity;
import com.janyee.agent.infra.persistence.repository.SessionMessageRepository;
import com.janyee.agent.infra.persistence.repository.SessionRepository;
import com.janyee.agent.runtime.session.SessionTranscriptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InMemorySessionTranscriptService implements SessionTranscriptService {

    /** 用第一条 user message 截到这个长度作 title;跟 JpaAgentQueryService.toSessionTitle 一致。 */
    private static final int TITLE_MAX_LENGTH = 36;

    private final SessionMessageRepository sessionMessageRepository;
    private final SessionRepository sessionRepository;

    public InMemorySessionTranscriptService(SessionMessageRepository sessionMessageRepository,
                                            SessionRepository sessionRepository) {
        this.sessionMessageRepository = sessionMessageRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional
    public void appendUserMessage(String sessionId, String runId, String content, String referencesJson, String attachmentsJson) {
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        entity.setRole("user");
        entity.setMessageType("chat");
        entity.setContent(content);
        entity.setReferencesJson(blankToNull(referencesJson));
        entity.setAttachmentsJson(blankToNull(attachmentsJson));
        entity.setSeqNo(sessionMessageRepository.findMaxSeqNoBySessionId(sessionId) + 1);
        sessionMessageRepository.save(entity);

        // 第一条 user message 时把它截断版本写到 session.title(若 title 仍为空)。
        // 这样所有读 title 的地方(列表 / 复盘 / chat 页 tab)都直接拿到一致结果,
        // 不再依赖各自的 fallback 兜底。手动改过 title 的会话不会被覆盖。
        ensureSessionTitle(sessionId, content);
    }

    private void ensureSessionTitle(String sessionId, String firstContent) {
        if (firstContent == null || firstContent.isBlank()) return;
        sessionRepository.findById(sessionId).ifPresent(session -> {
            if (session.getTitle() != null && !session.getTitle().isBlank()) return;
            session.setTitle(toSessionTitle(firstContent));
            sessionRepository.save(session);
        });
    }

    private String toSessionTitle(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return null;
        return normalized.length() <= TITLE_MAX_LENGTH
                ? normalized
                : normalized.substring(0, TITLE_MAX_LENGTH) + "...";
    }

    @Override
    @Transactional
    public void appendAssistantMessage(String sessionId, String runId, String content,
                                       Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        entity.setRole("assistant");
        entity.setMessageType("chat");
        entity.setContent(content);
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        entity.setTotalTokens(totalTokens);
        entity.setSeqNo(sessionMessageRepository.findMaxSeqNoBySessionId(sessionId) + 1);
        sessionMessageRepository.save(entity);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    @Override
    @Transactional
    public void appendToolMessage(String sessionId, String runId, String toolName, String toolArgsJson, String toolResultJson, String content) {
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        entity.setRole("tool");
        entity.setMessageType("tool_result");
        entity.setContent(content);
        entity.setToolName(toolName);
        entity.setToolArgsJson(toolArgsJson);
        entity.setToolResultJson(toolResultJson);
        entity.setSeqNo(sessionMessageRepository.findMaxSeqNoBySessionId(sessionId) + 1);
        sessionMessageRepository.save(entity);
    }

    private void saveMessage(String sessionId, String runId, String role, String messageType, String content) {
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        entity.setRole(role);
        entity.setMessageType(messageType);
        entity.setContent(content);
        entity.setSeqNo(sessionMessageRepository.findMaxSeqNoBySessionId(sessionId) + 1);
        sessionMessageRepository.save(entity);
    }
}
