package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "run_record")
public class RunRecordEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    private String id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "agent_id", nullable = false, length = 128)
    private String agentId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "llm_config_id", length = 64)
    private String llmConfigId;

    @Column(name = "llm_provider", length = 64)
    private String llmProvider;

    @Column(name = "llm_model", length = 255)
    private String llmModel;

    @Column(name = "status", nullable = false, length = 64)
    private String status;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "request_message", columnDefinition = "TEXT")
    private String requestMessage;

    @Column(name = "request_references_json", columnDefinition = "TEXT")
    private String requestReferencesJson;

    @Column(name = "request_attachments_json", columnDefinition = "TEXT")
    private String requestAttachmentsJson;

    // Snapshot of the current RunPlan (toSnapshot() serialized). Updated on every plan
    // mutation — create/update/auto-seed. Historical runs surface this so users can see
    // "how did this run plan its steps" retroactively. Null for runs with no plan.
    @Column(name = "plan_json", columnDefinition = "TEXT")
    private String planJson;

    // V23: 多租户字段
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "app_id", length = 64)
    private String appId;

    // V36: 完整执行流程的 JSON 日志(一个 run 一行 JSON 数组),用于事后复盘 + 前端时间线渲染。
    // 跟 session_message / tool_audit_log 是不同的访问模式:这里按 run 时序读全部事件,
    // 那两张按消息 / 工具维度过滤。冗余存储是必要的。
    @Column(name = "event_log_json", columnDefinition = "TEXT")
    private String eventLogJson;

    // V43: 完整**未过滤**事件日志(JSON 数组)。跟 event_log_json 的区别是它**包含** TOKEN_DELTA 和
    // 流式 MODEL_OUTPUT chunk —— 即每个 LLM token 增量、每个 700ms 进度心跳全都记下来。
    // 用途:debug LLM 流式响应卡顿、回放 token 用量逐 chunk 累加曲线、查"为什么 UI 这一刻卡了 X 秒"
    // 这类细粒度问题。run 结束时由 RunEventCollector.flushAndPersist 一次性写入。
    @Column(name = "raw_log_text", columnDefinition = "TEXT")
    private String rawLogText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getLlmConfigId() {
        return llmConfigId;
    }

    public void setLlmConfigId(String llmConfigId) {
        this.llmConfigId = llmConfigId;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getRequestMessage() {
        return requestMessage;
    }

    public void setRequestMessage(String requestMessage) {
        this.requestMessage = requestMessage;
    }

    public String getRequestReferencesJson() {
        return requestReferencesJson;
    }

    public void setRequestReferencesJson(String requestReferencesJson) {
        this.requestReferencesJson = requestReferencesJson;
    }

    public String getRequestAttachmentsJson() {
        return requestAttachmentsJson;
    }

    public void setRequestAttachmentsJson(String requestAttachmentsJson) {
        this.requestAttachmentsJson = requestAttachmentsJson;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getEventLogJson() { return eventLogJson; }
    public void setEventLogJson(String eventLogJson) { this.eventLogJson = eventLogJson; }

    public String getRawLogText() { return rawLogText; }
    public void setRawLogText(String rawLogText) { this.rawLogText = rawLogText; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

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
}
