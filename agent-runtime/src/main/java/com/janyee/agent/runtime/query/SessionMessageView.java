package com.janyee.agent.runtime.query;

import java.time.Instant;

public record SessionMessageView(
        Long id,
        String runId,
        String role,
        String messageType,
        String content,
        String toolName,
        String toolArgsJson,
        String toolResultJson,
        // Nullable for pre-V20 rows. JSON array of ChatContextReference payloads.
        String referencesJson,
        // Nullable for pre-V20 rows. JSON array of ChatAttachment payloads
        // (空间区域 / 图片 / 文件).
        String attachmentsJson,
        Long seqNo,
        Instant createdAt,
        // Token 用量。仅 role='assistant' 的行有非空值,否则全 null。
        // 跨多轮 LLM 调用累计,由 SimpleAgentRunner 在 run 结束时落 session_message 表。
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
