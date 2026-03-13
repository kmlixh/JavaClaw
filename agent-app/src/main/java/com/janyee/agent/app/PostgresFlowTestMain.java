package com.janyee.agent.app;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

public final class PostgresFlowTestMain {

    private PostgresFlowTestMain() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = FlowTestSupport.startContext("postgres")) {
            FlowTestSupport.requireRouting(context);
            FlowTestSupport.requireLlmConfig(context, "siliconflow-glm47");
            WebClient webClient = FlowTestSupport.createClient(context);
            FlowTestSupport.verifyHttpFlow(
                    webClient,
                    "siliconflow-glm47",
                    "Pro/zai-org/GLM-4.7",
                    "请用中文简短回复“Postgres Flow Test Passed”，不要输出其他内容。"
            );
            System.out.println("POSTGRES FLOW TEST PASSED");
        }
    }
}
