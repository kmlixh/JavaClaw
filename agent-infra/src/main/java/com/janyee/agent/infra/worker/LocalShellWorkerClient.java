package com.janyee.agent.infra.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.runtime.worker.WorkerClient;
import com.janyee.agent.runtime.worker.WorkerTaskRequest;
import com.janyee.agent.runtime.worker.WorkerTaskResult;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class LocalShellWorkerClient implements WorkerClient {

    private final AgentPlatformProperties properties;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public LocalShellWorkerClient(
            AgentPlatformProperties properties,
            WorkspaceService workspaceService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkerTaskResult execute(WorkerTaskRequest request) {
        if (!"shell.exec".equals(request.taskType())) {
            throw new IllegalArgumentException("unsupported worker task type: " + request.taskType());
        }

        long start = System.currentTimeMillis();
        try {
            JsonNode payload = objectMapper.readTree(request.payload());
            String command = payload.path("command").asText("");
            validateCommand(command);

            Path workspaceRoot = workspaceService.getWorkspaceRoot(request.agentId());
            Path workdir = workspaceRoot.resolve("temp").resolve(request.runId()).normalize();
            if (!workdir.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("worker path escape detected");
            }
            Files.createDirectories(workdir);

            ProcessBuilder builder = createProcess(command, workdir);
            Process process = builder.start();
            boolean completed = process.waitFor(timeoutMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new WorkerTaskResult(false, "shell timeout", "", "command timed out", System.currentTimeMillis() - start);
            }

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            boolean ok = process.exitValue() == 0;
            String summary = ok ? "shell exec completed" : "shell exec failed";
            String output = stdout.isBlank() ? stderr : stdout;
            return new WorkerTaskResult(ok, summary, truncate(output, 4000), ok ? null : truncate(stderr.isBlank() ? stdout : stderr, 2000), System.currentTimeMillis() - start);
        } catch (Exception error) {
            return new WorkerTaskResult(false, "shell exec failed", "", error.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private void validateCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        List<String> allowedPrefixes = properties.worker() != null && properties.worker().shell() != null
                && properties.worker().shell().allowedPrefixes() != null
                ? properties.worker().shell().allowedPrefixes()
                : List.of("Get-ChildItem", "Get-Content", "Get-Location", "Write-Output", "echo", "dir", "ls", "pwd", "type");
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        boolean allowed = allowedPrefixes.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("command prefix not allowed by shell worker policy");
        }
    }

    private ProcessBuilder createProcess(String command, Path workdir) {
        String shell = properties.worker() != null && properties.worker().shell() != null && properties.worker().shell().shell() != null
                ? properties.worker().shell().shell()
                : "powershell";
        List<String> argv = switch (shell.toLowerCase(Locale.ROOT)) {
            case "pwsh" -> List.of("pwsh", "-Command", command);
            case "powershell" -> List.of("powershell", "-Command", command);
            default -> List.of(shell, "-Command", command);
        };
        return new ProcessBuilder(argv)
                .directory(workdir.toFile());
    }

    private long timeoutMillis() {
        long configured = properties.worker() != null && properties.worker().shell() != null
                && properties.worker().shell().timeoutMillis() != null
                ? properties.worker().shell().timeoutMillis()
                : Duration.ofSeconds(10).toMillis();
        return Math.max(configured, 1000L);
    }

    private String readStream(InputStream inputStream) throws Exception {
        try (inputStream; ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            return outputStream.toString(StandardCharsets.UTF_8);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "\n...(truncated)";
    }
}
