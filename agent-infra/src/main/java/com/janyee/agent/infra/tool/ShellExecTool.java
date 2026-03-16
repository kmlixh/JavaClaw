package com.janyee.agent.infra.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.runtime.worker.WorkerClient;
import com.janyee.agent.runtime.worker.WorkerTaskRequest;
import com.janyee.agent.runtime.worker.WorkerTaskResult;
import com.janyee.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ShellExecTool implements AgentTool {

    private final WorkerClient workerClient;
    private final ObjectMapper objectMapper;

    public ShellExecTool(WorkerClient workerClient, ObjectMapper objectMapper) {
        this.workerClient = workerClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "shell.exec";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                name(),
                "Execute a restricted shell command through the isolated worker. Only use direct allow-listed command prefixes such as Get-Location, pwd, ls, dir, Get-ChildItem, Get-Content, echo, Write-Output, or type. Do not wrap commands with powershell -Command and do not use cd.",
                "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode args = objectMapper.readTree(invocation.argumentsJson());
            WorkerTaskResult result = workerClient.execute(new WorkerTaskRequest(
                    invocation.agentId(),
                    invocation.sessionId(),
                    invocation.runId(),
                    name(),
                    objectMapper.writeValueAsString(Map.of("command", args.path("command").asText("")))
            ));
            return new ToolResult(
                    result.ok(),
                    result.summary(),
                    objectMapper.writeValueAsString(Map.of(
                            "output", result.output(),
                            "durationMillis", result.durationMillis()
                    )),
                    "[]",
                    result.error()
            );
        } catch (Exception error) {
            return new ToolResult(false, "shell exec failed", "{}", "[]", error.getMessage());
        }
    }
}
