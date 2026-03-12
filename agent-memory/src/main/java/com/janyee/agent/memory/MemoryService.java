package com.janyee.agent.memory;

import java.util.List;

public interface MemoryService {
    List<MemoryItem> retrieve(MemoryQuery query);
}
