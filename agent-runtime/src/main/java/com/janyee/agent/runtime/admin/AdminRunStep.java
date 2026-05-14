package com.janyee.agent.runtime.admin;

import java.time.Instant;

/**
 * 单条 run 复盘事件(行级)。
 *
 * <p>V42 起后端在 RunEventCollector.flushAndPersist 写入 run_event_step 表,
 * 前端复盘从 GET /api/admin/runs/{runId}/steps?category=... 拉这种结构 ——
 * category 已经在后端 once-for-all 分类好,前端不用再写分类规则。</p>
 *
 * <p>对老 run(只有 event_log_json 没有行级数据),服务会即时把 JSON 拆成
 * 这种结构再返回,前端不用感知两种数据源。</p>
 */
public record AdminRunStep(
        Long id,
        int seq,
        Instant ts,
        /** ai / db / file / artifact / plan / tool-other / lifecycle */
        String category,
        String eventType,
        String toolName,
        Integer iterationNo,
        String summary,
        /** 完整事件 payload JSON 字符串,前端按需展开。 */
        String payloadJson,
        Long durationMs,
        Boolean success
) {
}
