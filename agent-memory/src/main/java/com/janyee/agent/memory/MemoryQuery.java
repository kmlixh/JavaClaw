package com.janyee.agent.memory;

/**
 * 查询 memory 的关键词 + 隔离维度。
 *
 * <p>{@code sessionId} 是防止"跨会话数据泄露"的关键：
 * 历史上 memory 只按 {@code agentId} 全局检索，结果就是 session A 生成的数字
 * （扇区数、覆盖率等）会被 session B 通过关键词匹配命中，塞进 prompt 的
 * {@code Relevant memory} 段落，LLM 误以为是自己查到的数据抄进新报告。
 * 现在 retrieve 会优先按 {@code agentId + sessionId} 过滤本会话的 run summary，
 * 跨 session 的"run summary"不再进入检索池。</p>
 *
 * <p>{@code sessionId} 可以为空：对不区分 session 的调用（比如离线分析）会退化为原来的全局检索。</p>
 */
public record MemoryQuery(
        String agentId,
        String sessionId,
        String query
) {
    public MemoryQuery(String agentId, String query) {
        this(agentId, null, query);
    }
}
