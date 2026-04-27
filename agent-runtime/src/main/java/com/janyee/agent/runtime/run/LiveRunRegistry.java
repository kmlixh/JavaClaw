package com.janyee.agent.runtime.run;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的活跃 run 登记表 —— 唯一判断"这个 runId 现在后端真的在执行"的权威来源。
 *
 * <p>每次 {@code SimpleAgentRunner.executeRun()} 在产生 runId 之后立刻 {@link #register(String)}，
 * 在 finally 块中 {@link #unregister(String)}。于是：</p>
 * <ul>
 *   <li>正常完成 → finally 注销 → DB 里的状态也已经写成 COMPLETED / FAILED / WAITING_APPROVAL。</li>
 *   <li>进程崩溃 / kill -9 → 整个注册表消失；重启后 {@link RunStartupRecoveryService} 把 DB 里
 *       残留的 in-progress 行刷成 FAILED。</li>
 *   <li>运行中的线程抛出未被捕获的异常 → finally 仍然会跑 unregister，同时更新 DB 状态。</li>
 * </ul>
 *
 * <p>因此"DB 显示 in-progress 但 registry 查不到"这一刻就可以安全断言：该 run 已经死亡，
 * 只是 DB 的状态忘了更新。reconcile 逻辑基于这个不变式工作。</p>
 */
@Component
public class LiveRunRegistry {

    private final Map<String, Long> activeRuns = new ConcurrentHashMap<>();

    public void register(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        activeRuns.put(runId, System.currentTimeMillis());
    }

    public void unregister(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        activeRuns.remove(runId);
    }

    public boolean isActive(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        return activeRuns.containsKey(runId);
    }

    public Set<String> activeRunIds() {
        return Set.copyOf(activeRuns.keySet());
    }

    public int size() {
        return activeRuns.size();
    }
}
