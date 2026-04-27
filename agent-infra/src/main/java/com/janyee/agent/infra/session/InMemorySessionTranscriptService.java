package com.janyee.agent.infra.session;

import com.janyee.agent.infra.persistence.entity.SessionMessageEntity;
import com.janyee.agent.infra.persistence.repository.SessionMessageRepository;
import com.janyee.agent.runtime.session.SessionTranscriptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InMemorySessionTranscriptService implements SessionTranscriptService {

    private final SessionMessageRepository sessionMessageRepository;

    public InMemorySessionTranscriptService(SessionMessageRepository sessionMessageRepository) {
        this.sessionMessageRepository = sessionMessageRepository;
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
    }

    @Override
    @Transactional
    public void appendAssistantMessage(String sessionId, String runId, String content) {
        saveMessage(sessionId, runId, "assistant", "chat", content);
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
