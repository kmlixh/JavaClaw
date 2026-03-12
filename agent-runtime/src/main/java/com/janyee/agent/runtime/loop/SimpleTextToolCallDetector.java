package com.janyee.agent.runtime.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SimpleTextToolCallDetector implements ToolCallDetector {

    private static final Pattern TOOL_PATTERN = Pattern.compile("(?s)TOOL_CALL\\s*:\\s*(\\w[\\w.\\-]*)\\s*\\nARGS\\s*:\\s*(\\{.*})");
    private final ObjectMapper objectMapper;

    public SimpleTextToolCallDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ToolCallRequest> detect(ModelTurnResult result) {
        Optional<ToolCallRequest> structured = detectStructured(result);
        if (structured.isPresent()) {
            return structured;
        }

        Matcher matcher = TOOL_PATTERN.matcher(result.fullText());
        if (!matcher.find()) {
            return Optional.empty();
        }

        return Optional.of(new ToolCallRequest(
                UUID.randomUUID().toString(),
                matcher.group(1),
                matcher.group(2),
                matcher.group(0)
        ));
    }

    private Optional<ToolCallRequest> detectStructured(ModelTurnResult result) {
        for (LlmStreamEvent event : result.rawEvents()) {
            if ("tool_call_request".equalsIgnoreCase(event.type())) {
                return parseCompleteRequest(event.content());
            }
        }

        Map<Integer, ToolCallAssembly> assemblies = new LinkedHashMap<>();
        for (LlmStreamEvent event : result.rawEvents()) {
            if (!"tool_call_delta".equalsIgnoreCase(event.type())) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(event.content());
                int index = node.path("index").asInt(0);
                ToolCallAssembly assembly = assemblies.computeIfAbsent(index, ignored -> new ToolCallAssembly());
                if (node.hasNonNull("id")) {
                    assembly.id = node.path("id").asText();
                }
                if (node.hasNonNull("name")) {
                    assembly.name = node.path("name").asText();
                }
                if (node.hasNonNull("arguments")) {
                    assembly.arguments.append(node.path("arguments").asText());
                }
            } catch (Exception ignored) {
                // ignore malformed tool deltas and fall back to text protocol
            }
        }

        return assemblies.values().stream()
                .filter(assembly -> assembly.name != null && !assembly.name.isBlank())
                .findFirst()
                .map(assembly -> new ToolCallRequest(
                        assembly.id != null && !assembly.id.isBlank() ? assembly.id : UUID.randomUUID().toString(),
                        assembly.name,
                        normalizeArguments(assembly.arguments.toString()),
                        "structured_tool_call"
                ));
    }

    private Optional<ToolCallRequest> parseCompleteRequest(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String name = node.path("name").asText("");
            if (name.isBlank()) {
                name = node.path("function").path("name").asText("");
            }
            String arguments = node.path("arguments").asText("");
            if (arguments.isBlank()) {
                arguments = node.path("function").path("arguments").asText("");
            }
            if (name.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ToolCallRequest(
                    node.path("id").asText(UUID.randomUUID().toString()),
                    name,
                    normalizeArguments(arguments),
                    payload
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String normalizeArguments(String arguments) {
        String value = arguments == null ? "" : arguments.trim();
        return value.isBlank() ? "{}" : value;
    }

    private static final class ToolCallAssembly {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
