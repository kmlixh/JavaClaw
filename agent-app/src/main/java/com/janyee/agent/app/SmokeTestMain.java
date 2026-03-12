package com.janyee.agent.app;

import com.janyee.agent.domain.AgentBinding;
import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.AgentRunner;
import com.janyee.agent.runtime.agent.AgentRouteRequest;
import com.janyee.agent.runtime.agent.AgentRouter;
import com.janyee.agent.runtime.artifact.ArtifactService;
import com.janyee.agent.runtime.query.AgentQueryService;
import com.janyee.agent.runtime.run.RunRecordService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

public final class SmokeTestMain {

    private SmokeTestMain() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AgentApplication.class)
                .properties(
                        "spring.main.web-application-type=none",
                        "server.port=0"
                )
                .run(args)) {

            AgentRouter agentRouter = context.getBean(AgentRouter.class);
            RunRecordService runRecordService = context.getBean(RunRecordService.class);
            AgentRunner agentRunner = context.getBean(AgentRunner.class);
            AgentQueryService agentQueryService = context.getBean(AgentQueryService.class);
            ArtifactService artifactService = context.getBean(ArtifactService.class);

            AgentBinding opsBinding = agentRouter.route(new AgentRouteRequest("web", "ops", "ops-smoke-session", null));
            require("ops-agent".equals(opsBinding.agentId()), "route binding to ops-agent failed");

            String sessionId = "smoke-session";
            String runId = runRecordService.createAcceptedRun(sessionId, "dev-agent", "smoke-user", "/tool echo smoke");
            List<AgentEvent> events = agentRunner.run(new RunRequest(runId, sessionId, "dev-agent", "smoke-user", "/tool echo smoke", false))
                    .collectList()
                    .block();
            require(events != null && events.stream().anyMatch(event -> event.type() == AgentEventType.TOOL_COMPLETED),
                    "tool loop smoke run did not complete a tool");

            artifactService.saveTextArtifact("dev-agent", sessionId, runId, "smoke", "smoke.txt", "text/plain", "artifact smoke");
            require(!agentQueryService.getRun(runId).artifacts().isEmpty(), "artifact query did not return saved artifact");
            require(!agentQueryService.listMemoryNotes("dev-agent").isEmpty(), "memory notes were not persisted");

            System.out.println("SMOKE TEST PASSED");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
