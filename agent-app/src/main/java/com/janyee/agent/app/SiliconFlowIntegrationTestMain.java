package com.janyee.agent.app;

import com.janyee.agent.infra.persistence.entity.LlmProviderConfigEntity;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

public final class SiliconFlowIntegrationTestMain {

    private SiliconFlowIntegrationTestMain() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = FlowTestSupport.startContext("postgres")) {
            FlowTestSupport.requireRouting(context);
            LlmProviderConfigEntity config = FlowTestSupport.requireLlmConfig(context, "siliconflow-glm47");
            FlowTestSupport.require(config.isEnabled(), "siliconflow-glm47 is disabled");
            FlowTestSupport.require(config.getApiKey() != null && !config.getApiKey().isBlank(), "siliconflow-glm47 api key is blank");
            FlowTestSupport.require("Pro/zai-org/GLM-4.7".equals(config.getModel()), "siliconflow-glm47 model mismatch");

            WebClient webClient = FlowTestSupport.createClient(context);
            FlowTestSupport.verifyHttpFlow(
                    webClient,
                    "siliconflow-glm47",
                    "Pro/zai-org/GLM-4.7",
                    "请用中文简短回复“集成测试通过”，不要输出其他内容。"
            );
            System.out.println("SILICONFLOW INTEGRATION TEST PASSED");
        }
    }
}
