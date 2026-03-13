package com.janyee.agent.api;

import java.util.List;

public record SearchResponse(
        List<SessionMessageResponse> messages,
        List<MemoryNoteResponse> memories,
        List<ArtifactResponse> artifacts
) {
}
