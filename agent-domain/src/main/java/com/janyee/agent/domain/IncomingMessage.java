package com.janyee.agent.domain;

public record IncomingMessage(
        String channel,
        String userId,
        String sessionId,
        String content
) {
}
