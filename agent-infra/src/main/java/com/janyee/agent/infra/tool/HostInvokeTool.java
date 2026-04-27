package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.bridge.ExternalBridgeGateway;
import com.janyee.agent.runtime.bridge.ExternalBridgeResult;
import com.janyee.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HostInvokeTool implements AgentTool {

    private final ObjectMapper objectMapper;
    private final ExternalBridgeGateway externalBridgeGateway;

    public HostInvokeTool(ObjectMapper objectMapper, ExternalBridgeGateway externalBridgeGateway) {
        this.objectMapper = objectMapper;
        this.externalBridgeGateway = externalBridgeGateway;
    }

    @Override
    public String name() {
        return "host.invoke";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Invoke a host-system method registered by the embedding client via bridge websocket.",
                """
                {"type":"object","properties":{
                  "method":{"type":"string","description":"Host method name"},
                  "payload":{"type":"object","description":"JSON payload to pass to host method"},
                  "timeoutMs":{"type":"integer","minimum":1000,"maximum":120000}
                },"required":["method"]}
                """
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            String method = args.path("method").asText("");
            long timeoutMs = args.path("timeoutMs").asLong(15_000L);
            String payloadJson = toPayloadJson(args.path("payload"));
            ExternalBridgeResult bridgeResult = externalBridgeGateway.invoke(
                    invocation.sessionId(),
                    invocation.runId(),
                    method,
                    payloadJson,
                    timeoutMs
            );
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("method", method);
            data.put("success", bridgeResult.success());
            data.put("result", parseJsonOrRaw(bridgeResult.resultJson()));
            data.put("error", bridgeResult.error());
            return new ToolResult(
                    bridgeResult.success(),
                    bridgeResult.summary(),
                    objectMapper.writeValueAsString(data),
                    "[]",
                    bridgeResult.success() ? null : bridgeResult.error()
            );
        } catch (Exception error) {
            return new ToolResult(false, "host invoke failed", "{}", "[]", error.getMessage());
        }
    }

    private String toPayloadJson(JsonNode payload) {
        try {
            if (payload == null || payload.isMissingNode() || payload.isNull()) {
                return "{}";
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception error) {
            return "{}";
        }
    }

    private Object parseJsonOrRaw(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return value;
        }
    }
}

