package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.tool.AgentTool;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.Map;

@Component
public class HttpFetchTool implements AgentTool {

    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public HttpFetchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public String name() {
        return "http.fetch";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Fetch a text response from an HTTP or HTTPS URL",
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String url = args.path("url").asText();
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return new ToolResult(false, "unsupported url scheme", "{}", "[]", "only http/https allowed");
            }
            String body = webClient.get()
                    .uri(uri)
                    .accept(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON, MediaType.TEXT_HTML)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            String content = body == null ? "" : truncate(body, 4000);
            return new ToolResult(
                    true,
                    "Fetched " + url,
                    objectMapper.writeValueAsString(Map.of(
                            "url", url,
                            "content", content
                    )),
                    "[]",
                    null
            );
        } catch (Exception error) {
            return new ToolResult(false, "http fetch failed", "{}", "[]", error.getMessage());
        }
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...(truncated)";
    }
}
