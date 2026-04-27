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
        Instant createdAt
) {
}
