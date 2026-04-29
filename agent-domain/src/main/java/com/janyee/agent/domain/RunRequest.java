package com.janyee.agent.domain;

public record RunRequest(
        String runId,
        String sessionId,
        String agentId,
        String userId,
        String message,
        boolean resume,
        String llmConfigId,
        String llmModel,
        java.util.List<ChatContextReference> references,
        java.util.List<ChatAttachment> attachments,
        // 身份三元组(从 OAuth/cookie token 解析得到)。controller 在请求线程上从 exchange
        // 属性取出来塞进来,SimpleAgentRunner.executeRun 切到 boundedElastic 调度器后用它
        // 重建 SecurityContextHolder。否则 ThreadLocal 一切线程就丢,session/run 全部
        // fallback 到 anonymousSystemAdmin (tenant='system', userId='admin') —— 这是
        // xmap embed "最近会话"列表为空、user_id 永远是 "1" 的根因。
        String authUserId,
        String authTenantId,
        String authAppId
) {
    /** 旧调用点的兼容构造器:不带身份三元组(测试 / 老接入路径)。 */
    public RunRequest(
            String runId,
            String sessionId,
            String agentId,
            String userId,
            String message,
            boolean resume,
            String llmConfigId,
            String llmModel,
            java.util.List<ChatContextReference> references,
            java.util.List<ChatAttachment> attachments
    ) {
        this(runId, sessionId, agentId, userId, message, resume, llmConfigId, llmModel,
                references, attachments, null, null, null);
    }
}
