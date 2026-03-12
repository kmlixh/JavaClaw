package com.janyee.agent.infra.agent;

import com.janyee.agent.domain.AgentBinding;
import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.runtime.agent.AgentRouteRequest;
import com.janyee.agent.runtime.agent.AgentRouter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConfigAgentRouter implements AgentRouter {

    private final AgentPlatformProperties properties;

    public ConfigAgentRouter(AgentPlatformProperties properties) {
        this.properties = properties;
    }

    @Override
    public AgentBinding route(AgentRouteRequest request) {
        if (request.requestedAgentId() != null && !request.requestedAgentId().isBlank()) {
            return new AgentBinding(request.requestedAgentId(), "explicit request");
        }

        List<AgentPlatformProperties.RouteBindingProperties> bindings = properties.routing() != null
                && properties.routing().bindings() != null
                ? properties.routing().bindings()
                : List.of();

        for (AgentPlatformProperties.RouteBindingProperties binding : bindings) {
            if (matches(binding, request)) {
                return new AgentBinding(binding.agentId(), reason(binding));
            }
        }

        return new AgentBinding(properties.defaultAgentId(), "default agent");
    }

    private boolean matches(AgentPlatformProperties.RouteBindingProperties binding, AgentRouteRequest request) {
        return matchesExact(binding.channel(), request.channel())
                && matchesExact(binding.userId(), request.userId())
                && matchesPrefix(binding.sessionPrefix(), request.sessionId())
                && binding.agentId() != null
                && !binding.agentId().isBlank();
    }

    private boolean matchesExact(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private boolean matchesPrefix(String expectedPrefix, String actual) {
        return expectedPrefix == null || expectedPrefix.isBlank() || (actual != null && actual.startsWith(expectedPrefix));
    }

    private String reason(AgentPlatformProperties.RouteBindingProperties binding) {
        if (binding.userId() != null && !binding.userId().isBlank()) {
            return "matched userId=" + binding.userId();
        }
        if (binding.sessionPrefix() != null && !binding.sessionPrefix().isBlank()) {
            return "matched sessionPrefix=" + binding.sessionPrefix();
        }
        if (binding.channel() != null && !binding.channel().isBlank()) {
            return "matched channel=" + binding.channel();
        }
        return "matched configured binding";
    }
}
