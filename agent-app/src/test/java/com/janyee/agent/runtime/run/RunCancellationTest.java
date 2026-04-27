package com.janyee.agent.runtime.run;

import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.loop.ToolLoopContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证"终止运行中任务"两块核心机制:
 *  1. {@link ToolLoopContext#requestCancel(String)} 原子地设置取消标志,
 *     {@link ToolLoopContext#isCancelRequested()} 跨线程可见。
 *  2. {@link RunCancellationRegistry} 正确转发信号到注册的 context;
 *     未注册的 runId 返回 false,前端据此决定是否走 DB 兜底路径。
 */
class RunCancellationTest {

    @Test
    void contextCancelFlagIsAtomicAndIdempotent() {
        ToolLoopContext context = newContext();
        assertFalse(context.isCancelRequested());
        assertEquals("", context.cancelReason());

        context.requestCancel("cancelled by user");
        assertTrue(context.isCancelRequested());
        assertEquals("cancelled by user", context.cancelReason());

        // 后续 cancel 不应覆盖原 reason —— 只有首次 CAS 成功的 caller 留下理由
        context.requestCancel("some other reason");
        assertEquals("cancelled by user", context.cancelReason(),
                "第二次 cancel 不得覆盖首次 reason,保留诊断价值");
    }

    @Test
    void blankReasonFallsBackToDefault() {
        ToolLoopContext context = newContext();
        context.requestCancel(null);
        assertEquals("cancelled by user", context.cancelReason());

        ToolLoopContext another = newContext();
        another.requestCancel("   ");
        assertEquals("cancelled by user", another.cancelReason());
    }

    @Test
    void registryForwardsSignalToRegisteredContext() {
        RunCancellationRegistry registry = new RunCancellationRegistry();
        ToolLoopContext context = newContext();
        String runId = context.runId();

        registry.register(runId, context);
        assertTrue(registry.isActive(runId));
        assertEquals(1, registry.size());

        boolean delivered = registry.requestCancel(runId, "stop this");
        assertTrue(delivered, "注册了的 runId 必须返回 true 表示信号已投递");
        assertTrue(context.isCancelRequested(),
                "registry.requestCancel 必须把 context 的标志位翻过来");
        assertEquals("stop this", context.cancelReason());
    }

    @Test
    void registryReportsFalseForUnknownRun() {
        RunCancellationRegistry registry = new RunCancellationRegistry();
        boolean delivered = registry.requestCancel("ghost-run-id", "any");
        assertFalse(delivered,
                "未注册 runId 返回 false,controller 据此决定是否走 DB 兜底");
    }

    @Test
    void unregisterRemovesContext() {
        RunCancellationRegistry registry = new RunCancellationRegistry();
        ToolLoopContext context = newContext();
        registry.register(context.runId(), context);
        registry.unregister(context.runId());

        assertFalse(registry.isActive(context.runId()));
        assertFalse(registry.requestCancel(context.runId(), "too late"),
                "unregister 之后信号不应再投递成功");
        assertFalse(context.isCancelRequested());
    }

    @Test
    void activeRunIdsSnapshotIsImmutable() {
        RunCancellationRegistry registry = new RunCancellationRegistry();
        ToolLoopContext a = newContext();
        ToolLoopContext b = newContext();
        registry.register(a.runId(), a);
        registry.register(b.runId(), b);

        var snapshot = registry.activeRunIds();
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.contains(a.runId()));
        assertTrue(snapshot.contains(b.runId()));

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add("should-fail"),
                "activeRunIds 返回不可变快照,避免调用方误改内部状态");
    }

    private ToolLoopContext newContext() {
        String runId = "run-" + UUID.randomUUID();
        RunRequest request = new RunRequest(
                runId,
                "sess-" + UUID.randomUUID(),
                "dev-agent",
                "junit",
                "hello",
                false, null, null, List.of(), List.of()
        );
        return new ToolLoopContext(
                request,
                runId,
                new PromptContext("system", "hi"),
                100
        );
    }
}
