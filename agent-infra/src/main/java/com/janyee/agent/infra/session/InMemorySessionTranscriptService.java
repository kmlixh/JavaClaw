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
    public void appendUserMessage(String sessionId, String runId, String content) {
        saveMessage(sessionId, runId, "user", "chat", content);
    }

    @Override
    @Transactional
    public void appendAssistantMessage(String sessionId, String runId, String content) {
        saveMessage(sessionId, runId, "assistant", "chat", content);
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
