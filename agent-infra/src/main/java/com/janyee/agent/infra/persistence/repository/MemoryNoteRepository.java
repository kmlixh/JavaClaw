package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.MemoryNoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryNoteRepository extends JpaRepository<MemoryNoteEntity, Long> {
    List<MemoryNoteEntity> findTop20ByAgentIdOrderByCreatedAtDesc(String agentId);

    /**
     * Session 级隔离检索：跑同一 agent 不同 session 时，自动生成的 run_summary 绝不会从 A 渗到 B。
     * 仅检索本 session 或用户显式保存（source='pinned'）的 agent 全局备注。
     */
    List<MemoryNoteEntity> findTop20ByAgentIdAndSessionIdOrderByCreatedAtDesc(String agentId, String sessionId);

    /**
     * 按 source 过滤：只有被标为 "pinned" 的 note 才是真正可跨 session 使用的"长期记忆"。
     * 默认的 run summary （source="run_summary"）不应被其他 session 看到。
     */
    List<MemoryNoteEntity> findTop20ByAgentIdAndSourceOrderByCreatedAtDesc(String agentId, String source);

    /**
     * Scope 驱动的检索：agent/global scope 的条目跨 session 可见，session scope 的只本 session 可见。
     * 前端/AdminCatalogService 在列表视图时按 scope 展示（便于用户区分"一次性"vs"长期"）。
     */
    List<MemoryNoteEntity> findTop40ByAgentIdAndScopeInOrderByCreatedAtDesc(String agentId, List<String> scopes);

    List<MemoryNoteEntity> findTop20ByAgentIdAndContentContainingIgnoreCaseOrderByCreatedAtDesc(String agentId, String content);

    void deleteBySessionId(String sessionId);
}
