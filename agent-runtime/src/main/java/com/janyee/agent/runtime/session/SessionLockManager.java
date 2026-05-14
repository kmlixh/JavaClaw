package com.janyee.agent.runtime.session;

import java.time.Duration;

public interface SessionLockManager {
    boolean tryLock(String sessionId, Duration timeout);

    void unlock(String sessionId);

    /**
     * 不获取锁,只看当前 sessionId 是否被持有。给 WS handler 在 createAcceptedRun 之前
     * 做"早判定":session 还在跑,就别再建一条注定 fail 的 run 记录 + 一堆 RUN_FAILED 事件
     * 把日志和 DB 灌满。这只是个**优化提示**,实际正确性靠 SimpleAgentRunner 里的 tryLock 兜底。
     */
    boolean isLocked(String sessionId);
}
