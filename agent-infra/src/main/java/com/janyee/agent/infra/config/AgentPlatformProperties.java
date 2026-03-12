package com.janyee.agent.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "agent")
public record AgentPlatformProperties(
        String workspaceRoot,
        String defaultAgentId,
        RuntimeProperties runtime,
        LlmProperties llm,
        SessionLockProperties sessionLock,
        ApprovalProperties approval,
        RoutingProperties routing,
        WorkerProperties worker
) {
    public record RuntimeProperties(
            int maxToolIterations,
            int streamBufferSize
    ) {
    }

    public record LlmProperties(
            boolean enabled,
            String provider,
            String model,
            String baseUrl,
            String apiKey,
            String chatPath,
            boolean stream
    ) {
    }

    public record SessionLockProperties(
            String type,
            String keyPrefix,
            Duration ttl
    ) {
    }

    public record ApprovalProperties(
            List<String> requiredTools
    ) {
    }

    public record RoutingProperties(
            List<RouteBindingProperties> bindings
    ) {
    }

    public record RouteBindingProperties(
            String channel,
            String userId,
            String sessionPrefix,
            String agentId
    ) {
    }

    public record WorkerProperties(
            ShellWorkerProperties shell
    ) {
    }

    public record ShellWorkerProperties(
            String shell,
            Long timeoutMillis,
            List<String> allowedPrefixes
    ) {
    }
}
