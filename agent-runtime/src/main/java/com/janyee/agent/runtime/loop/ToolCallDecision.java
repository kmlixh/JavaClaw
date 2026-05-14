package com.janyee.agent.runtime.loop;

/**
 * Policy 层对一次工具调用的判定结果。
 *
 * <ul>
 *   <li>{@code allowed=true,  approvalRequired=false} → 直接执行</li>
 *   <li>{@code allowed=true,  approvalRequired=true}  → 转 WAITING_APPROVAL,等人审批后再执行</li>
 *   <li>{@code allowed=false, recoverable=true}       → <b>软拒</b>。把 {@link #reason()} 当成一条工具错误塞回
 *       LLM context,让它下一轮自己纠正(典型:plan-step 守卫"先 plan.update 再 db.query"、
 *       wave barrier、artifact 占位符检查)。orchestrator <b>不终止 run</b>。</li>
 *   <li>{@code allowed=false, recoverable=false}      → <b>硬拒</b>。run 整体 FAIL,reason 写进
 *       errorMessage。仅用于"LLM 怎么改都不该做"的场景。</li>
 * </ul>
 */
public record ToolCallDecision(
        boolean allowed,
        boolean approvalRequired,
        String reason,
        String normalizedToolName,
        String normalizedArgumentsJson,
        boolean recoverable
) {
    /** 兼容老调用点:不指定 recoverable 时默认按 true(软拒,LLM 可自纠)。 */
    public ToolCallDecision(boolean allowed, boolean approvalRequired, String reason,
                            String normalizedToolName, String normalizedArgumentsJson) {
        this(allowed, approvalRequired, reason, normalizedToolName, normalizedArgumentsJson, true);
    }
}
