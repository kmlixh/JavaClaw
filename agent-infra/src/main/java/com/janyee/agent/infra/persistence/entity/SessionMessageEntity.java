package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "session_message")
public class SessionMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    @Column(name = "message_type", nullable = false, length = 32)
    private String messageType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "tool_args_json", columnDefinition = "TEXT")
    private String toolArgsJson;

    @Column(name = "tool_result_json", columnDefinition = "TEXT")
    private String toolResultJson;

    @Column(name = "seq_no", nullable = false)
    private Long seqNo;

    // JSON array of ChatContextReference payloads (@mention 引用的知识/资源).
    // Nullable 以兼容 V20 之前的老数据;读出来为 null 时调用方当成空列表处理。
    @Column(name = "references_json", columnDefinition = "TEXT")
    private String referencesJson;

    // JSON array of ChatAttachment payloads (地图空间区域 / 图片 / 普通文件).
    // Nullable 同上。大文件 base64 内容也写这里 —— session_message 现在是发送历史的唯一真相,
    // resend 走这一张表就能完整还原用户上次发过什么。
    @Column(name = "attachments_json", columnDefinition = "TEXT")
    private String attachmentsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Token 用量(只有 role=assistant 行有非空值;user/tool 行恒为 null)。
    // 一次 run 通常多轮 LLM 调用,这里存的是该 run 所有 LLM 调用的累计值,
    // 跟最终生成的 assistant message 一一对应。给资费 / 用量分析用。
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolArgsJson() {
        return toolArgsJson;
    }

    public void setToolArgsJson(String toolArgsJson) {
        this.toolArgsJson = toolArgsJson;
    }

    public String getToolResultJson() {
        return toolResultJson;
    }

    public void setToolResultJson(String toolResultJson) {
        this.toolResultJson = toolResultJson;
    }

    public Long getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Long seqNo) {
        this.seqNo = seqNo;
    }

    public String getReferencesJson() {
        return referencesJson;
    }

    public void setReferencesJson(String referencesJson) {
        this.referencesJson = referencesJson;
    }

    public String getAttachmentsJson() {
        return attachmentsJson;
    }

    public void setAttachmentsJson(String attachmentsJson) {
        this.attachmentsJson = attachmentsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
