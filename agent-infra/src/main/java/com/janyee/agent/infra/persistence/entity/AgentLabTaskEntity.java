package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Agent 实验室 - 顶层迭代任务。一个 task 多轮 iteration,所有产出都隔离在
 * scope_type='AGENT_LAB' 的 sandbox agent / skill 上。
 */
@Entity
@Table(name = "agent_lab_task")
public class AgentLabTaskEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    @Column(name = "owner_user_id", nullable = false, length = 128)
    private String ownerUserId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "goal_description", nullable = false, columnDefinition = "TEXT")
    private String goalDescription;

    /** Meta-LLM 配置 id;NULL = 用 is_default=true 的那条。 */
    @Column(name = "llm_config_id", length = 64)
    private String llmConfigId;

    /**
     * AI 派生的测试场景 JSON([{id, input, rules:[{type:expected_output, description:"..."}]}])。
     * V37 之前是用户手填,V38 起改成 meta-LLM 在 round-1 之前据 constraintRules + referenceDocuments
     * + goalDescription 自动生成。Runner 仍用这字段跑评估,所以 schema 不动。
     */
    @Column(name = "test_cases_json", nullable = false, columnDefinition = "TEXT")
    private String testCasesJson;

    /** 用户自然语言写的约束规则。V38 新增,替代用户手写测试用例。 */
    @Column(name = "constraint_rules", columnDefinition = "TEXT")
    private String constraintRules;

    /** 用户粘贴的参考文档(数据字典 / 业务规范 / 样例)。可选。 */
    @Column(name = "reference_documents", columnDefinition = "TEXT")
    private String referenceDocuments;

    @Column(name = "max_iterations", nullable = false)
    private int maxIterations;

    @Column(name = "current_iteration", nullable = false)
    private int currentIteration;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "sandbox_agent_id", length = 128)
    private String sandboxAgentId;

    @Column(name = "sandbox_skill_names_json", columnDefinition = "TEXT")
    private String sandboxSkillNamesJson;

    /** EXISTING / NEW / CLONE_FROM */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode = "NEW";

    /** 实际被迭代修改的 agent_id(EXISTING 直接用源,NEW/CLONE_FROM 用任务创建时填的)。 */
    @Column(name = "target_agent_id", length = 128)
    private String targetAgentId;

    /** SYSTEM / TENANT / USER —— EXISTING 跟随源 Agent;NEW/CLONE_FROM 用户在创建时指定。 */
    @Column(name = "target_scope_type", length = 16)
    private String targetScopeType;

    @Column(name = "target_scope_tenant_id", length = 64)
    private String targetScopeTenantId;

    /** 仅 mode=CLONE_FROM 时填:克隆的源 agent_id。 */
    @Column(name = "clone_from_agent_id", length = 128)
    private String cloneFromAgentId;

    /** 调试目标:AGENT(老任务) 或 SKILL(新任务默认)。 */
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType = "SKILL";

    @Column(name = "target_skill_name", length = 128)
    private String targetSkillName;

    @Column(name = "new_skill_name", length = 128)
    private String newSkillName;

    @Column(name = "clone_from_skill_name", length = 128)
    private String cloneFromSkillName;

    /** 设计师 LLM。null = 用 default。优先于 llm_config_id 兼容字段。 */
    @Column(name = "meta_llm_config_id", length = 64)
    private String metaLlmConfigId;

    /** 设计师 LLM 选定的 model(LlmConfig 可挂多 model 时区分用)。null = 用 LLM 内部 default。 */
    @Column(name = "meta_llm_model", length = 128)
    private String metaLlmModel;

    /** 跑测试用例时用的 LLM。null = 走目标 Agent 自己绑定的默认 LLM。 */
    @Column(name = "test_llm_config_id", length = 64)
    private String testLlmConfigId;

    /** 测试 LLM 选定的 model。null = 用 LLM 内部 default 或 Agent 默认。 */
    @Column(name = "test_llm_model", length = 128)
    private String testLlmModel;

    /** false(默认):迭代只改 Skill;true:每轮 meta-LLM 可同时改 Agent + Skill。 */
    @Column(name = "allow_agent_evolution", nullable = false)
    private boolean allowAgentEvolution;

    @Column(name = "final_summary", columnDefinition = "TEXT")
    private String finalSummary;

    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String errorDetail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getGoalDescription() { return goalDescription; }
    public void setGoalDescription(String goalDescription) { this.goalDescription = goalDescription; }
    public String getLlmConfigId() { return llmConfigId; }
    public void setLlmConfigId(String llmConfigId) { this.llmConfigId = llmConfigId; }
    public String getTestCasesJson() { return testCasesJson; }
    public void setTestCasesJson(String testCasesJson) { this.testCasesJson = testCasesJson; }
    public String getConstraintRules() { return constraintRules; }
    public void setConstraintRules(String constraintRules) { this.constraintRules = constraintRules; }
    public String getReferenceDocuments() { return referenceDocuments; }
    public void setReferenceDocuments(String referenceDocuments) { this.referenceDocuments = referenceDocuments; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public int getCurrentIteration() { return currentIteration; }
    public void setCurrentIteration(int currentIteration) { this.currentIteration = currentIteration; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSandboxAgentId() { return sandboxAgentId; }
    public void setSandboxAgentId(String sandboxAgentId) { this.sandboxAgentId = sandboxAgentId; }
    public String getSandboxSkillNamesJson() { return sandboxSkillNamesJson; }
    public void setSandboxSkillNamesJson(String sandboxSkillNamesJson) { this.sandboxSkillNamesJson = sandboxSkillNamesJson; }
    public String getFinalSummary() { return finalSummary; }
    public void setFinalSummary(String finalSummary) { this.finalSummary = finalSummary; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getTargetAgentId() { return targetAgentId; }
    public void setTargetAgentId(String targetAgentId) { this.targetAgentId = targetAgentId; }
    public String getTargetScopeType() { return targetScopeType; }
    public void setTargetScopeType(String targetScopeType) { this.targetScopeType = targetScopeType; }
    public String getTargetScopeTenantId() { return targetScopeTenantId; }
    public void setTargetScopeTenantId(String targetScopeTenantId) { this.targetScopeTenantId = targetScopeTenantId; }
    public String getCloneFromAgentId() { return cloneFromAgentId; }
    public void setCloneFromAgentId(String cloneFromAgentId) { this.cloneFromAgentId = cloneFromAgentId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetSkillName() { return targetSkillName; }
    public void setTargetSkillName(String targetSkillName) { this.targetSkillName = targetSkillName; }
    public String getNewSkillName() { return newSkillName; }
    public void setNewSkillName(String newSkillName) { this.newSkillName = newSkillName; }
    public String getCloneFromSkillName() { return cloneFromSkillName; }
    public void setCloneFromSkillName(String cloneFromSkillName) { this.cloneFromSkillName = cloneFromSkillName; }
    public String getMetaLlmConfigId() { return metaLlmConfigId; }
    public void setMetaLlmConfigId(String metaLlmConfigId) { this.metaLlmConfigId = metaLlmConfigId; }
    public String getMetaLlmModel() { return metaLlmModel; }
    public void setMetaLlmModel(String metaLlmModel) { this.metaLlmModel = metaLlmModel; }
    public String getTestLlmConfigId() { return testLlmConfigId; }
    public void setTestLlmConfigId(String testLlmConfigId) { this.testLlmConfigId = testLlmConfigId; }
    public String getTestLlmModel() { return testLlmModel; }
    public void setTestLlmModel(String testLlmModel) { this.testLlmModel = testLlmModel; }
    public boolean isAllowAgentEvolution() { return allowAgentEvolution; }
    public void setAllowAgentEvolution(boolean allowAgentEvolution) { this.allowAgentEvolution = allowAgentEvolution; }
}
