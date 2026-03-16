package com.janyee.agent.api;

public record ChatAttachment(
        String name,
        String contentType,
        String content
) {
}
