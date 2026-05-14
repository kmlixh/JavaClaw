package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Run 复盘事件的行级存储 —— V42 起替代 run_record.event_log_json 里的 JSON 数组。
 *
 * <p>每条事件一行,显式 category / event_type / tool_name 列,后端可以按这些字段
 * 做 WHERE 过滤,前端拿到的就是分类好的列表;不再需要拉整段 JSON 自己解析。</p>
 */
@Entity
@Table(name = "run_event_step")
public class RunEventStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    /** ai / db / file / artifact / plan / tool-other / lifecycle */
    @Column(name = "category", nullable = false, length = 16)
    private String category;

    /** PROMPT_SENT / LLM_RESPONSE / TOOL_REQUESTED / TOOL_COMPLETED / ... */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "iteration_no")
    private Integer iterationNo;

    /** 一句话摘要,前端列表直接显示。 */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** 完整事件 payload,JSON。 */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "success")
    private Boolean success;

    @PrePersist
    void onCreate() {
        if (ts == null) ts = Instant.now();
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public Integer getIterationNo() { return iterationNo; }
    public void setIterationNo(Integer iterationNo) { this.iterationNo = iterationNo; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
}
