package com.janyee.agent.app;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

public final class SmokeTestMain {

    private SmokeTestMain() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = FlowTestSupport.startContext()) {
            FlowTestSupport.requireRouting(context);
            FlowTestSupport.seedLlmConfig(
                    context,
                    "smoke-glm47",
                    "Smoke GLM-4.7",
                    "Pro/zai-org/GLM-4.7",
                    "http://127.0.0.1:9/v1",
                    "smoke-key",
                    "/chat/completions",
                    true,
                    true
            );

            WebClient webClient = FlowTestSupport.createClient(context);
            FlowTestSupport.verifyHttpFlow(webClient, "smoke-glm47", "Pro/zai-org/GLM-4.7", "/tool echo smoke");

            System.out.println("SMOKE TEST PASSED");
        }
    }
}
