package com.janyee.agent.infra.memory;

import com.janyee.agent.memory.MemoryItem;
import com.janyee.agent.memory.MemoryQuery;
import com.janyee.agent.memory.MemoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NoopMemoryService implements MemoryService {
    @Override
    public List<MemoryItem> retrieve(MemoryQuery query) {
        return List.of();
    }

    @Override
    public void saveNote(String agentId, String sessionId, String runId, String content, String source) {
    }
}
