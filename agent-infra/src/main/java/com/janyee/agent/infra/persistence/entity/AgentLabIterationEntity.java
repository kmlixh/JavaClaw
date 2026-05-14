package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** Agent 实验室单轮迭代快照。 */
@Entity
@Table(name = "agent_lab_iteration")
public class AgentLabIterationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "iteration_no", nullable = false)
    private int iterationNo;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "agent_snapshot_json", columnDefinition = "TEXT")
    private String agentSnapshotJson;

    @Column(name = "skill_snapshots_json", columnDefinition = "TEXT")
    private String skillSnapshotsJson;

    @Column(name = "test_results_json", columnDefinition = "TEXT")
    private String testResultsJson;

    @Column(name = "passed_count")
    private Integer passedCount;

    @Column(name = "total_count")
    private Integer totalCount;

    @Column(name = "evaluation_summary", columnDefinition = "TEXT")
    private String evaluationSummary;

    @Column(name = "fix_plan_json", columnDefinition = "TEXT")
    private String fixPlanJson;

    @Column(name = "meta_llm_error", columnDefinition = "TEXT")
    private String metaLlmError;

    /** 当前进度提示(实时更新):"调 meta-LLM 中..." / "跑测试场景 (i/N)..." / "评估中..."。
     *  终态时清空。详情页迭代时间线靠这字段在 IN_PROGRESS 时给用户看到一些反馈,
     *  之前 IN_PROGRESS 行所有 *_json 字段都 null,展开"查看 Agent/Skill/测试结果"什么都没有。 */
    @Column(name = "progress_step", columnDefinition = "TEXT")
    private String progressStep;

    /** 每个测试用例完整 chat run 的 trace JSON array(assistant text / tool 调用 / audit decisions / plan 终态)。 */
    @Column(name = "run_traces_json", columnDefinition = "TEXT")
    private String runTracesJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public int getIterationNo() { return iterationNo; }
    public void setIterationNo(int iterationNo) { this.iterationNo = iterationNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAgentSnapshotJson() { return agentSnapshotJson; }
    public void setAgentSnapshotJson(String agentSnapshotJson) { this.agentSnapshotJson = agentSnapshotJson; }
    public String getSkillSnapshotsJson() { return skillSnapshotsJson; }
    public void setSkillSnapshotsJson(String skillSnapshotsJson) { this.skillSnapshotsJson = skillSnapshotsJson; }
    public String getTestResultsJson() { return testResultsJson; }
    public void setTestResultsJson(String testResultsJson) { this.testResultsJson = testResultsJson; }
    public Integer getPassedCount() { return passedCount; }
    public void setPassedCount(Integer passedCount) { this.passedCount = passedCount; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    public String getEvaluationSummary() { return evaluationSummary; }
    public void setEvaluationSummary(String evaluationSummary) { this.evaluationSummary = evaluationSummary; }
    public String getFixPlanJson() { return fixPlanJson; }
    public void setFixPlanJson(String fixPlanJson) { this.fixPlanJson = fixPlanJson; }
    public String getMetaLlmError() { return metaLlmError; }
    public void setMetaLlmError(String metaLlmError) { this.metaLlmError = metaLlmError; }
    public String getProgressStep() { return progressStep; }
    public void setProgressStep(String progressStep) { this.progressStep = progressStep; }
    public String getRunTracesJson() { return runTracesJson; }
    public void setRunTracesJson(String runTracesJson) { this.runTracesJson = runTracesJson; }
    public Instant getCreatedAt() { return createdAt; }
}
