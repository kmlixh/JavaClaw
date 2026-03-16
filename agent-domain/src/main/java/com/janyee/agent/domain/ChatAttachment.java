package com.janyee.agent.domain;

public record ChatAttachment(
        String name,
        String contentType,
        String content
) {
}
