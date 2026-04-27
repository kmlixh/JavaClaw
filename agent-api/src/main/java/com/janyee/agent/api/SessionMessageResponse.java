package com.janyee.agent.api;

import java.time.Instant;

public record SessionMessageResponse(
        Long id,
        String runId,
        String role,
        String messageType,
        String content,
        String toolName,
        String toolArgsJson,
        String toolResultJson,
        // JSON array string of ChatContextReference payloads (@mention 引用).
        // Nullable for pre-V20 rows.
        String referencesJson,
        // JSON array string of ChatAttachment payloads (空间区域 / 图片 / 文件).
        // Nullable for pre-V20 rows.
        String attachmentsJson,
        Long seqNo,
        Instant createdAt
) {
}
