package com.janyee.agent.domain;

public record ToolResult(
        boolean ok,
        String summary,
        String dataJson,
        String artifactsJson,
        String error
) {
}
