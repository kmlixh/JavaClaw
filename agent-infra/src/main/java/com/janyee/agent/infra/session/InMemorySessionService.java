package com.janyee.agent.infra.session;

import com.janyee.agent.infra.persistence.entity.SessionEntity;
import com.janyee.agent.infra.persistence.repository.SessionRepository;
import com.janyee.agent.runtime.session.SessionService;
import com.janyee.agent.runtime.session.SessionSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InMemorySessionService implements SessionService {

    private final SessionRepository sessionRepository;

    public InMemorySessionService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional
    public SessionSnapshot ensureSession(String sessionId, String agentId, String userId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseGet(() -> createSession(sessionId, agentId, userId));
        return new SessionSnapshot(session.getId(), session.getAgentId(), session.getUserId());
    }

    private SessionEntity createSession(String sessionId, String agentId, String userId) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setAgentId(agentId);
        session.setUserId(userId);
        session.setChannel("web");
        session.setStatus("ACTIVE");
        return sessionRepository.save(session);
    }
}
