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

            try {
                WebClient webClient = FlowTestSupport.createClient(context);
                FlowTestSupport.verifyHttpFlow(webClient, "smoke-glm47", "Pro/zai-org/GLM-4.7", "/tool echo smoke");
                System.out.println("SMOKE TEST PASSED");
            } finally {
                // 一定要清掉:smoke-glm47 用的是 127.0.0.1:9 这种不存在的 LLM endpoint,种子留在
                // 共享 DB 里会污染所有真实用户的 chat —— 没指定 llmConfigId 就被当成 default 选中,
                // 真实 chat 请求挂在 Connection refused 上。historically 这条没清,导致开发环境
                // 手动测的人莫名其妙就遇到 LLM 拒连。
                try {
                    FlowTestSupport.deleteLlmConfig(context, "smoke-glm47");
                } catch (Exception cleanupError) {
                    System.err.println("smoke-test cleanup failed: " + cleanupError.getMessage());
                }
            }
        }
    }
}
