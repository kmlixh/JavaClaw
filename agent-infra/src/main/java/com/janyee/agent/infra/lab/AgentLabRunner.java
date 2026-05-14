package com.janyee.agent.infra.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.infra.persistence.entity.AgentLabIterationEntity;
import com.janyee.agent.infra.persistence.entity.AgentLabTaskEntity;
import com.janyee.agent.infra.persistence.repository.AgentLabIterationRepository;
import com.janyee.agent.infra.persistence.repository.AgentLabTaskRepository;
import com.janyee.agent.runtime.AgentRunner;
import com.janyee.agent.runtime.admin.AdminCatalogService;
import com.janyee.agent.runtime.admin.AgentDefinitionCommand;
import com.janyee.agent.runtime.admin.AgentDefinitionView;
import com.janyee.agent.runtime.admin.SkillDefinitionCommand;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Agent 实验室异步迭代引擎。
 *
 * <h3>每轮流程</h3>
 * <ol>
 *   <li>调 {@link MetaLlmDesigner}:第一轮初次设计;后续轮带上 trace 修订</li>
 *   <li>解析 {agent, skills, rationale};识别 "ABORT:" 提前终止</li>
 *   <li>把 design 写入 target_agent_id(覆盖 systemPrompt / agentMarkdown 等;skill 按 skillName 覆盖/新增)</li>
 *   <li>每个测试用例:用 {@link AgentRunner#run(RunRequest)} 跑完整 chat (plan/tool loop/SkillGuard 全过) → block 等结束</li>
 *   <li>{@link AgentLabTraceCollector} 拉 run trace → {@link AgentLabEvaluator} 评估</li>
 *   <li>全过 → SUCCESS;失败 + 还有迭代余量 → 把 trace 喂回去,进下一轮</li>
 * </ol>
 *
 * <p>每个 task 用单线程跑(避免并发改 Agent);多 task 之间并发(全局 4 线程池)。</p>
 */
@Component
public class AgentLabRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentLabRunner.class);
    private static final Duration RUN_BLOCK_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_CONCURRENT_TASKS = 4;
    private static final int MAX_ITERATION_HARD_LIMIT = 20;

    private final AgentLabTaskRepository taskRepository;
    private final AgentLabIterationRepository iterationRepository;
    private final MetaLlmDesigner metaDesigner;
    private final AgentLabEvaluator evaluator;
    private final AgentLabTraceCollector traceCollector;
    private final AdminCatalogService adminCatalogService;
    private final AgentRunner agentRunner;
    private final ObjectMapper objectMapper;
    private final com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository skillDefinitionRepository;
    private final com.janyee.agent.runtime.run.RunRecordService runRecordService;

    private ExecutorService executor;
    private final ConcurrentHashMap<String, Future<?>> running = new ConcurrentHashMap<>();

    @Autowired
    public AgentLabRunner(AgentLabTaskRepository taskRepository,
                          AgentLabIterationRepository iterationRepository,
                          MetaLlmDesigner metaDesigner,
                          AgentLabEvaluator evaluator,
                          AgentLabTraceCollector traceCollector,
                          AdminCatalogService adminCatalogService,
                          AgentRunner agentRunner,
                          ObjectMapper objectMapper,
                          com.janyee.agent.infra.persistence.repository.SkillDefinitionRepository skillDefinitionRepository,
                          com.janyee.agent.runtime.run.RunRecordService runRecordService) {
        this.taskRepository = taskRepository;
        this.iterationRepository = iterationRepository;
        this.metaDesigner = metaDesigner;
        this.evaluator = evaluator;
        this.traceCollector = traceCollector;
        this.adminCatalogService = adminCatalogService;
        this.agentRunner = agentRunner;
        this.objectMapper = objectMapper;
        this.skillDefinitionRepository = skillDefinitionRepository;
        this.runRecordService = runRecordService;
    }

    @PostConstruct
    void initExecutor() {
        executor = Executors.newFixedThreadPool(MAX_CONCURRENT_TASKS, r -> {
            Thread t = new Thread(r, "agent-lab-runner");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    public void start(String taskId) {
        if (running.containsKey(taskId)) return;
        Future<?> future = executor.submit(() -> {
            try {
                runLoop(taskId);
            } catch (Throwable error) {
                log.error("agent.lab.runner_crashed taskId={}", taskId, error);
                markTaskError(taskId, "runner_crash", error.toString());
            } finally {
                running.remove(taskId);
            }
        });
        running.put(taskId, future);
    }

    public boolean cancel(String taskId) {
        Future<?> f = running.get(taskId);
        if (f != null) {
            f.cancel(true);
            running.remove(taskId);
            updateStatus(taskId, "CANCELLED", "用户手动取消", null);
            return true;
        }
        return false;
    }

    public boolean isRunning(String taskId) {
        Future<?> f = running.get(taskId);
        return f != null && !f.isDone();
    }

    // ---------------------------------------------------------------------------------------
    // main loop

    private void runLoop(String taskId) {
        AgentLabTaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        updateStatus(taskId, "RUNNING", null, null);
        boolean skillMode = "SKILL".equalsIgnoreCase(task.getTargetType());
        boolean dualMode = skillMode && task.isAllowAgentEvolution();
        log.info("agent.lab.run_start taskId={}, target_type={}, mode={}, dual={}, host={}, target_skill={}, maxIter={}",
                taskId, task.getTargetType(), task.getMode(), dualMode, task.getTargetAgentId(),
                task.getTargetSkillName(), task.getMaxIterations());

        // meta-LLM 优先用 meta_llm_config_id;兼容老字段 llm_config_id
        String metaLlmConfigId = task.getMetaLlmConfigId() != null ? task.getMetaLlmConfigId() : task.getLlmConfigId();

        // 新流程:用户不写测试用例,只写约束规则。runner 在第一轮迭代之前调一次 meta-LLM
        // 据 constraintRules + referenceDocuments + goalDescription 派生 ~3 条测试场景,
        // 写回 test_cases_json,后续迭代直接复用(场景稳定 = 多轮间结果可比)。
        // 兼容老任务:test_cases_json 已经有内容(老格式)就跳过派生。
        if (needsDeriveScenarios(task)) {
            try {
                deriveAndStoreScenarios(task, metaLlmConfigId);
                task = taskRepository.findById(taskId).orElse(task);
            } catch (Exception derive) {
                // 派生失败不再让整个任务死掉。退回到一条兜底场景:
                // 用 goalDescription 当 input,constraintRules 当 expected_output。
                // 设计 + 测试循环仍能跑通,只是覆盖度低。日志里留下真实失败原因便于排查
                // (常见 = meta-LLM 选了 application-default 这条坏 token,或网络抖)。
                log.warn("agent.lab.derive_failed taskId={} reason={} → fallback to single scenario",
                        taskId, derive.getMessage());
                String fallback = buildFallbackScenarios(task);
                AgentLabTaskEntity reloaded = taskRepository.findById(taskId).orElse(task);
                reloaded.setTestCasesJson(fallback);
                taskRepository.save(reloaded);
                task = reloaded;
                // 不改 status,但用 finalSummary 临时挂个提示位
                appendErrorDetail(taskId, "派生测试场景失败,改用兜底单场景跑: " + derive.getMessage());
            }
        }

        int maxIter = Math.min(task.getMaxIterations() <= 0 ? 5 : task.getMaxIterations(), MAX_ITERATION_HARD_LIMIT);
        // refine/restart 时,iter_no 必须续接历史 max → 否则跟旧记录撞唯一约束 (task_id, iteration_no)。
        // baseOffset = 历史已有的 iter 数(也是历史最大 iter_no,因为单调)
        // 之前 currentIteration=0 的设计在新建任务时是对的,但 refine 时就崩了 —— 现在统一用
        // baseOffset 算 iterNo,新建任务依然从 1 起(baseOffset=0),refine 后从 N+1 起。
        int baseOffset = (int) iterationRepository.countByTaskId(taskId);
        String previousAgentJson = null;
        String previousSkillsJson = null;
        String previousSkillJson = null;       // SKILL 模式专用
        String previousTestResultsJson = null;
        String previousRunTracesJson = null;

        for (int round = 1; round <= maxIter; round++) {
            int iterNo = baseOffset + round;     // 全局唯一的 iter_no
            updateCurrentIteration(taskId, iterNo);
            AgentLabIterationEntity iteration = createIteration(taskId, iterNo);
            try {
                updateProgress(iteration, "调 meta-LLM 设计 Agent / Skill 中...");
                String prompt;
                if (dualMode) {
                    // 拉当前 host agent 的 systemPrompt 作为初次 prompt 的种子上下文
                    // 注意:这里要看"本次 round" 是不是第一轮(用 round==1 而不是 iterNo==1),
                    // refine 时第一次重新设计依然要走"初次 prompt"路径
                    String currentAgentPrompt = (round == 1)
                            ? lookupAgentSystemPrompt(task.getTargetAgentId()) : null;
                    prompt = (round == 1)
                            ? metaDesigner.buildInitialDualPrompt(task.getGoalDescription(),
                                    task.getTestCasesJson(), task.getTargetAgentId(),
                                    currentAgentPrompt, task.getTargetSkillName(), task.getMode())
                            : metaDesigner.buildRefineDualPrompt(task.getGoalDescription(),
                                    task.getTestCasesJson(), task.getTargetAgentId(),
                                    task.getTargetSkillName(),
                                    previousAgentJson, previousSkillJson,
                                    previousTestResultsJson, previousRunTracesJson);
                } else if (skillMode) {
                    prompt = (round == 1)
                            ? metaDesigner.buildInitialSkillPrompt(task.getGoalDescription(),
                                    task.getTestCasesJson(), task.getTargetSkillName(),
                                    task.getTargetAgentId(), task.getMode())
                            : metaDesigner.buildRefineSkillPrompt(task.getGoalDescription(),
                                    task.getTestCasesJson(), task.getTargetSkillName(),
                                    task.getTargetAgentId(),
                                    previousSkillJson, previousTestResultsJson, previousRunTracesJson);
                } else {
                    prompt = (round == 1)
                            ? metaDesigner.buildInitialPrompt(task.getGoalDescription(),
                                    task.getTestCasesJson(), task.getTargetAgentId(), task.getMode())
                            : metaDesigner.buildRefinePrompt(task.getGoalDescription(),
                                    task.getTestCasesJson(), task.getTargetAgentId(),
                                    previousAgentJson, previousSkillsJson, previousTestResultsJson, previousRunTracesJson);
                }

                JsonNode design = metaDesigner.design(metaLlmConfigId, task.getMetaLlmModel(), prompt);
                String rationale = design.path("rationale").asText("");

                JsonNode agentNode = design.path("agent");
                JsonNode skillsNode = design.path("skills");
                JsonNode skillNode = design.path("skill");

                String agentJson = agentNode.isMissingNode() ? "{}" : objectMapper.writeValueAsString(agentNode);
                String skillsJson;
                if (skillMode) {
                    // SKILL/DUAL 模式都只产 single skill,统一存到 skill_snapshots_json(单元素数组)
                    if (!skillNode.isMissingNode()) {
                        ArrayNode arr = objectMapper.createArrayNode().add(skillNode);
                        skillsJson = objectMapper.writeValueAsString(arr);
                        previousSkillJson = objectMapper.writeValueAsString(skillNode);
                    } else {
                        skillsJson = "[]";
                    }
                    if (dualMode && !agentNode.isMissingNode()) {
                        previousAgentJson = agentJson;     // dual 模式还要把 agent 喂回下一轮
                    }
                } else {
                    skillsJson = skillsNode.isMissingNode() ? "[]" : objectMapper.writeValueAsString(skillsNode);
                }
                iteration.setAgentSnapshotJson(agentJson);
                iteration.setSkillSnapshotsJson(skillsJson);
                iteration.setProgressStep("meta-LLM 已返回设计,准备写入 Agent / Skill...");
                iterationRepository.save(iteration);

                if (rationale != null && rationale.startsWith("ABORT:")) {
                    iteration.setStatus("EVAL_FAILED");
                    iteration.setEvaluationSummary("meta-LLM 主动 ABORT: " + rationale);
                    iteration.setProgressStep(null);
                    iterationRepository.save(iteration);
                    finishTask(taskId, "FAILED_META_LLM",
                            "meta-LLM 在第 " + iterNo + " 轮主动 ABORT: " + rationale, rationale);
                    return;
                }

                // 写入目标:
                //   DUAL 模式 → 同时写 host agent + target skill
                //   SKILL 模式 → 只写 target skill (host agent 不动)
                //   AGENT 模式(老路径) → 写 agent + skills
                if (dualMode) {
                    writeHostAgentOverwrite(task, agentNode);
                    writeTargetSkill(task, skillNode);
                } else if (skillMode) {
                    writeTargetSkill(task, skillNode);
                } else {
                    writeTarget(task, agentNode, skillsNode);
                }

                // 跑测试用例(每个跑完整 chat)
                JsonNode testCases = objectMapper.readTree(
                        task.getTestCasesJson() == null ? "[]" : task.getTestCasesJson());
                int totalCases = testCases.isArray() ? testCases.size() : 0;
                updateProgress(iteration, "准备跑测试场景 (0/" + totalCases + ")...");
                ArrayNode results = objectMapper.createArrayNode();
                ArrayNode traces = objectMapper.createArrayNode();
                int passed = 0, total = 0;
                if (testCases.isArray()) {
                    for (JsonNode caseNode : testCases) {
                        total++;
                        updateProgress(iteration, "跑测试场景 (" + total + "/" + totalCases + "):" + caseNode.path("id").asText("anonymous"));
                        CaseRunOutcome outcome = runOneCase(task, caseNode);
                        results.add(objectMapper.valueToTree(outcome.evalResult.resultMap()));
                        ObjectNode traceWithCaseId = (ObjectNode) outcome.trace;
                        traceWithCaseId.put("caseId", caseNode.path("id").asText("anonymous"));
                        traces.add(traceWithCaseId);
                        if (outcome.evalResult.passed()) passed++;
                    }
                }
                updateProgress(iteration, "评估中...");
                String resultsJson = objectMapper.writeValueAsString(results);
                String tracesJson = objectMapper.writeValueAsString(traces);
                iteration.setTestResultsJson(resultsJson);
                iteration.setRunTracesJson(tracesJson);
                iteration.setPassedCount(passed);
                iteration.setTotalCount(total);
                String summary = "通过 " + passed + " / " + total + " 用例";
                iteration.setEvaluationSummary(summary);

                if (passed == total && total > 0) {
                    iteration.setStatus("EVAL_PASSED");
                    iteration.setProgressStep(null);
                    iterationRepository.save(iteration);
                    finishTask(taskId, "SUCCEEDED",
                            "第 " + iterNo + " 轮全部用例通过(" + summary + ")", null);
                    return;
                }

                iteration.setStatus("EVAL_FAILED");
                iteration.setProgressStep(null);
                ObjectNode fixPlan = objectMapper.createObjectNode();
                fixPlan.put("rationale", rationale);
                fixPlan.put("nextAction", round < maxIter ? "refine" : "exhausted");
                iteration.setFixPlanJson(fixPlan.toString());
                iterationRepository.save(iteration);

                previousAgentJson = agentJson;
                previousSkillsJson = skillsJson;
                previousTestResultsJson = resultsJson;
                previousRunTracesJson = tracesJson;
            } catch (RuntimeException designError) {
                iteration.setStatus("META_LLM_ERROR");
                iteration.setMetaLlmError(designError.toString());
                iteration.setProgressStep(null);
                iterationRepository.save(iteration);
                log.warn("agent.lab.iter_meta_failed taskId={}, iter={}, err={}",
                        taskId, iterNo, designError.getMessage());
            } catch (Exception other) {
                iteration.setStatus("META_LLM_ERROR");
                iteration.setMetaLlmError("unexpected: " + other);
                iteration.setProgressStep(null);
                iterationRepository.save(iteration);
                log.error("agent.lab.iter_unexpected taskId={}, iter={}", taskId, iterNo, other);
            }
        }

        finishTask(taskId, "FAILED_MAX_ITER",
                "迭代 " + maxIter + " 轮仍未通过全部测试用例", null);
    }

    /** 拉取某 agent 当前的 systemPrompt。找不到返回空串。 */
    private String lookupAgentSystemPrompt(String agentId) {
        if (agentId == null) return "";
        return adminCatalogService.listAgentDefinitions().stream()
                .filter(a -> agentId.equals(a.agentId()))
                .findFirst()
                .map(AgentDefinitionView::systemPrompt)
                .orElse("");
    }

    /**
     * DUAL 模式专用:覆盖 host agent 的 systemPrompt / agentMarkdown 等内容字段。
     * agentId / scope 沿用 task 已记录的(meta-LLM 输出的 agentId 仅用作回显校验)。
     */
    private void writeHostAgentOverwrite(AgentLabTaskEntity task, JsonNode agentNode) {
        if (agentNode == null || agentNode.isMissingNode()) {
            log.warn("agent.lab.dual_agent_design_missing taskId={}", task.getId());
            return;
        }
        String agentId = task.getTargetAgentId();
        // 拉当前 agent 拿已有的 scope / displayName 作 fallback,避免被 meta-LLM 输出污染
        AgentDefinitionView current = adminCatalogService.listAgentDefinitions().stream()
                .filter(a -> agentId.equals(a.agentId())).findFirst().orElse(null);
        String scopeType = current != null ? current.scopeType() : "SYSTEM";
        String tenantId = current != null ? current.scopeTenantId() : null;
        String displayName = agentNode.path("displayName").asText(
                current != null ? current.displayName() : agentId);
        String description = agentNode.path("description").asText(
                current != null ? current.description() : "");

        com.janyee.agent.runtime.admin.AgentDefinitionCommand cmd =
                new com.janyee.agent.runtime.admin.AgentDefinitionCommand(
                        agentId, displayName, description,
                        agentNode.path("systemPrompt").asText(current != null ? current.systemPrompt() : ""),
                        agentNode.path("agentMarkdown").asText(current != null ? current.agentMarkdown() : ""),
                        current != null ? current.memoryMarkdown() : "",
                        Boolean.TRUE, scopeType, tenantId, null, null);
        adminCatalogService.saveAgentDefinition(cmd);
    }

    /**
     * SKILL 模式:只覆盖 target_skill_name 这一个 skill 的 promptTemplate / configJson 等内容字段。
     * Agent 完全不动 —— host_agent_id 保持原样。
     */
    private void writeTargetSkill(AgentLabTaskEntity task, JsonNode skillNode) {
        String skillName = task.getTargetSkillName();
        if (skillName == null || skillName.isBlank()) {
            log.warn("agent.lab.skill_target_missing taskId={}", task.getId());
            return;
        }
        if (skillNode == null || skillNode.isMissingNode()) {
            log.warn("agent.lab.skill_design_missing taskId={}", task.getId());
            return;
        }
        String hostAgentId = task.getTargetAgentId();
        String scopeType = task.getTargetScopeType() == null ? "SYSTEM" : task.getTargetScopeType();
        String tenantId = task.getTargetScopeTenantId();

        // 关键修复:之前 id 传 null,saveSkillDefinition 内部按 id 是否存在判定 create/update —— 永远新建。
        // 结果 N 轮迭代写 N 条同名 skill 行,前端 Skills 页面看起来"没建出来"(被同名行淹没)。
        // 这里手动按 skillName 查一下:存在就用它的 id 升级到 UPDATE,不存在才插入。
        String existingId = skillDefinitionRepository.findBySkillName(skillName)
                .map(com.janyee.agent.infra.persistence.entity.SkillDefinitionEntity::getId)
                .orElse(null);
        SkillDefinitionCommand cmd = new SkillDefinitionCommand(
                existingId,                            // 复用同名 skill 的 id → upsert
                hostAgentId,
                List.of(hostAgentId),
                skillName,
                skillNode.path("description").asText(""),
                skillNode.path("promptTemplate").asText(""),
                normalizeJsonString(skillNode.path("configJson"), "{}"),
                normalizeJsonString(skillNode.path("triggerKeywords"), "[]"),
                true,
                scopeType,
                tenantId,
                null,
                null
        );
        adminCatalogService.saveSkillDefinition(cmd);

        try {
            task.setSandboxSkillNamesJson(objectMapper.writeValueAsString(java.util.Set.of(skillName)));
            taskRepository.save(task);
        } catch (Exception ignored) {
            // 仅冗余字段
        }
    }

    /** 把 meta-LLM 设计的 Agent + Skills 写到 task.target_agent_id(覆盖式)。 */
    private void writeTarget(AgentLabTaskEntity task, JsonNode agentNode, JsonNode skillsNode) {
        String agentId = task.getTargetAgentId();
        String scopeType = task.getTargetScopeType() == null ? "SYSTEM" : task.getTargetScopeType();
        String tenantId = task.getTargetScopeTenantId();

        AgentDefinitionCommand agentCmd = new AgentDefinitionCommand(
                agentId,
                agentNode.path("displayName").asText(task.getTitle()),
                agentNode.path("description").asText(""),
                agentNode.path("systemPrompt").asText(""),
                agentNode.path("agentMarkdown").asText(""),
                "",
                Boolean.TRUE,
                scopeType,
                tenantId,
                null,
                null
        );
        adminCatalogService.saveAgentDefinition(agentCmd);

        Set<String> skillNames = new HashSet<>();
        if (skillsNode != null && skillsNode.isArray()) {
            for (JsonNode skillNode : skillsNode) {
                String name = skillNode.path("skillName").asText("");
                if (name.isBlank()) continue;
                skillNames.add(name);
                SkillDefinitionCommand skillCmd = new SkillDefinitionCommand(
                        null,
                        agentId,
                        List.of(agentId),
                        name,
                        skillNode.path("description").asText(""),
                        skillNode.path("promptTemplate").asText(""),
                        normalizeJsonString(skillNode.path("configJson"), "{}"),
                        normalizeJsonString(skillNode.path("triggerKeywords"), "[]"),
                        true,
                        scopeType,
                        tenantId,
                        null,
                        null
                );
                adminCatalogService.saveSkillDefinition(skillCmd);
            }
        }
        try {
            task.setSandboxSkillNamesJson(objectMapper.writeValueAsString(skillNames));
            taskRepository.save(task);
        } catch (Exception ignored) {
            // 仅冗余字段
        }
    }

    private String normalizeJsonString(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        if (node.isTextual()) return node.asText();
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * 跑单个测试用例:用真实 chat run。返回评估结果 + 完整 trace。
     * 失败/超时时 trace 仅含已收集的部分(可能没 toolCalls 或 assistantTexts)。
     */
    private CaseRunOutcome runOneCase(AgentLabTaskEntity task, JsonNode caseNode) {
        String caseId = caseNode.path("id").asText("anonymous");
        String input = caseNode.path("input").asText("");

        // 每个用例新 sessionId + runId,跨用例无上下文污染
        String sessionId = "lab-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String runId = "lab-r-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 取目标 agent 的 owner-side scope 信息,补到 RunRequest 的身份三元组,
        // 让 SimpleAgentRunner 切线程时能重建 principal(否则匿名 fallback,数据隔离会乱)。
        AgentDefinitionView agent = adminCatalogService.listAgentDefinitions().stream()
                .filter(a -> task.getTargetAgentId().equals(a.agentId()))
                .findFirst().orElse(null);
        String authUserId = task.getOwnerUserId();
        String authTenantId = agent != null ? agent.scopeTenantId() : task.getTargetScopeTenantId();
        String authAppId = null;

        // 测试 LLM:优先 task.testLlmConfigId(用户在创建时显式指定);
        // 留空 = 让 SimpleAgentRunner 走 Agent 自己绑的默认(不强制覆盖)。
        // testLlmModel 也跟着传 —— 同一个 LLM 配置可挂多个 model,用户选的 (configId, model) 才完整。
        String testLlmConfigId = task.getTestLlmConfigId();
        String testLlmModel = task.getTestLlmModel();
        RunRequest runRequest = new RunRequest(
                runId, sessionId, task.getTargetAgentId(), authUserId, input,
                false, testLlmConfigId, testLlmModel,
                List.of(), List.of(),
                authUserId, authTenantId, authAppId
        );

        // 预创建 run_record 行 —— 否则 SimpleAgentRunner 后续 updateStatus / attachEventLog
        // 走 findById().ifPresent() 静默 no-op,复盘日志永远落不下来。
        // 走 createAcceptedRunWithId 指定 lab 自己生成的 runId,跟 RunRequest 对齐。
        try {
            runRecordService.createAcceptedRunWithId(
                    runId, sessionId, task.getTargetAgentId(), authUserId, input,
                    List.of(), List.of(),
                    testLlmConfigId, null, testLlmModel
            );
        } catch (Exception preCreateError) {
            log.warn("agent.lab.run_record_precreate_failed taskId={}, runId={}, err={}",
                    task.getId(), runId, preCreateError.getMessage());
        }

        try {
            agentRunner.run(runRequest)
                    .blockLast(RUN_BLOCK_TIMEOUT);
        } catch (Exception runError) {
            log.warn("agent.lab.case_run_failed taskId={}, caseId={}, err={}",
                    task.getId(), caseId, runError.getMessage());
            // 即使 run 失败也继续收集 trace —— 通常 run_record 已经标了 FAILED + detail
        }

        ObjectNode trace;
        try {
            trace = traceCollector.collect(sessionId, runId);
        } catch (Exception collectError) {
            trace = objectMapper.createObjectNode();
            trace.put("collectError", collectError.toString());
        }
        // 判官 LLM 用 metaLlmConfigId(优先)或 llmConfigId(老兼容字段);留空 = LLM provider 走 default
        String judgeLlmConfigId = task.getMetaLlmConfigId() != null && !task.getMetaLlmConfigId().isBlank()
                ? task.getMetaLlmConfigId()
                : task.getLlmConfigId();
        String judgeLlmModel = task.getMetaLlmModel();    // 没设也行,LlmProvider 自己 fallback
        AgentLabEvaluator.CaseResult er = evaluator.evaluate(caseId, input, trace, caseNode.path("rules"), judgeLlmConfigId, judgeLlmModel);
        return new CaseRunOutcome(er, trace);
    }

    // ---------------------------------------------------------------------------------------
    // status helpers

    @Transactional
    void updateStatus(String taskId, String status, String summary, String errorDetail) {
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(status);
            if (summary != null) t.setFinalSummary(summary);
            if (errorDetail != null) t.setErrorDetail(errorDetail);
            taskRepository.save(t);
        });
    }

    @Transactional
    void updateCurrentIteration(String taskId, int iter) {
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setCurrentIteration(iter);
            taskRepository.save(t);
        });
    }

    @Transactional
    AgentLabIterationEntity createIteration(String taskId, int iterNo) {
        AgentLabIterationEntity it = new AgentLabIterationEntity();
        it.setTaskId(taskId);
        it.setIterationNo(iterNo);
        it.setStatus("IN_PROGRESS");
        it.setProgressStep("准备中...");
        return iterationRepository.save(it);
    }

    /** 实时更新进度提示。每次 save 都会触发前端 3 秒一次的轮询拉到。 */
    @Transactional
    void updateProgress(AgentLabIterationEntity iteration, String step) {
        iteration.setProgressStep(step);
        iterationRepository.save(iteration);
    }

    private void finishTask(String taskId, String status, String summary, String errorDetail) {
        updateStatus(taskId, status, summary, errorDetail);
        log.info("agent.lab.run_finish taskId={}, status={}, summary={}", taskId, status, summary);
    }

    private void markTaskError(String taskId, String code, String detail) {
        updateStatus(taskId, "FAILED_META_LLM", "runner 异常: " + code, detail);
    }

    private record CaseRunOutcome(AgentLabEvaluator.CaseResult evalResult, ObjectNode trace) {
    }

    /** 是否需要在 round-1 之前 由 meta-LLM 派生测试场景。 */
    private boolean needsDeriveScenarios(AgentLabTaskEntity task) {
        // 用户走新流程(写了 constraintRules)且 test_cases_json 还是占位空数组,就需要派生
        if (task.getConstraintRules() == null || task.getConstraintRules().isBlank()) {
            return false;   // 老任务没 constraintRules,沿用老 test_cases_json
        }
        String json = task.getTestCasesJson();
        if (json == null || json.isBlank()) return true;
        try {
            JsonNode parsed = objectMapper.readTree(json);
            return parsed.isArray() && parsed.size() == 0;
        } catch (Exception e) {
            return true;   // 解析失败也当成空,派生一份
        }
    }

    /** 调 meta-LLM 派生测试场景,正规化后写回 task.test_cases_json。 */
    @Transactional
    void deriveAndStoreScenarios(AgentLabTaskEntity task, String metaLlmConfigId) {
        log.info("agent.lab.derive_scenarios taskId={}", task.getId());
        String prompt = metaDesigner.buildDeriveScenariosPrompt(
                task.getGoalDescription(), task.getConstraintRules(), task.getReferenceDocuments());
        // 复用 metaDesigner.design 的 LLM 调用通道(它返回 JsonNode);它假定输出是 JSON 对象
        // 而我们要的是 JSON 数组 → 用一个 try 兜底:design 内部会调用 readTree,数组也能被解析
        JsonNode parsed = metaDesigner.design(metaLlmConfigId, task.getMetaLlmModel(), prompt);
        // parsed 应该是数组;如果 LLM 给了 {"scenarios":[...]} 或 {"cases":[...]} 也兜底取
        JsonNode arr = parsed;
        if (parsed != null && parsed.isObject()) {
            if (parsed.has("scenarios") && parsed.path("scenarios").isArray()) arr = parsed.path("scenarios");
            else if (parsed.has("cases") && parsed.path("cases").isArray()) arr = parsed.path("cases");
            else if (parsed.has("testCases") && parsed.path("testCases").isArray()) arr = parsed.path("testCases");
        }
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            throw new RuntimeException("meta-LLM 派生场景输出非数组或为空");
        }
        try {
            // 持久化:用反序列化 → 再序列化方式标准化(去掉多余空格)
            String normalized = objectMapper.writeValueAsString(arr);
            AgentLabTaskEntity reloaded = taskRepository.findById(task.getId()).orElse(task);
            reloaded.setTestCasesJson(normalized);
            taskRepository.save(reloaded);
            log.info("agent.lab.derive_scenarios_done taskId={}, count={}", task.getId(), arr.size());
        } catch (Exception e) {
            throw new RuntimeException("写回 testCasesJson 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 兜底场景:派生失败时用 goal/constraints 拼一条最小可跑场景,保证任务能继续。
     * 比直接 FAIL 整个 task 强 —— 用户至少能看到一轮迭代结果,据此再决定怎么改约束。
     */
    private String buildFallbackScenarios(AgentLabTaskEntity task) {
        ObjectNode caseNode = objectMapper.createObjectNode();
        caseNode.put("id", "case-fallback");
        String input = task.getGoalDescription() == null || task.getGoalDescription().isBlank()
                ? "请按约束规则完成任务"
                : task.getGoalDescription();
        caseNode.put("input", input);
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("type", "expected_output");
        rule.put("description", task.getConstraintRules() == null ? "符合用户描述" : task.getConstraintRules());
        ArrayNode rules = objectMapper.createArrayNode();
        rules.add(rule);
        caseNode.set("rules", rules);
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(caseNode);
        try { return objectMapper.writeValueAsString(arr); }
        catch (Exception e) { return "[]"; }
    }

    /** 在 errorDetail 上 append 一行警告(任务还能继续跑,但要让用户看到 ⚠)。 */
    @Transactional
    void appendErrorDetail(String taskId, String warn) {
        taskRepository.findById(taskId).ifPresent(t -> {
            String prev = t.getErrorDetail() == null ? "" : t.getErrorDetail() + "\n";
            t.setErrorDetail(prev + "⚠ " + warn);
            taskRepository.save(t);
        });
    }

    /**
     * 改写约束规则(可选)+ 切换 LLM(可选)后再次迭代:更新 entity.constraintRules
     * / referenceDocuments / *LlmConfigId / *LlmModel,清空 test_cases_json
     * 让 runner 重新派生场景,然后从 round-1 重启。
     *
     * <p>所有参数都是 patch 语义:传 null 表示不动该字段;传非空值整段覆盖。
     * constraintRules 例外 —— 详情页允许只换 LLM 不改规则,所以传 null 不覆盖,
     * 但要保证 entity 上原本就有 constraintRules(否则派生场景没素材)。</p>
     */
    @Transactional
    public void refineAndRestart(String taskId,
                                 String newTitle, String newGoalDescription, Integer newMaxIterations,
                                 String newConstraintRules, String newReferenceDocuments,
                                 String newMetaLlmConfigId, String newMetaLlmModel,
                                 String newTestLlmConfigId, String newTestLlmModel) {
        AgentLabTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        Future<?> f = running.get(taskId);
        if (f != null) {
            f.cancel(true);
            running.remove(taskId);
        }
        // —— 标题 / 目标 / 上限:patch 语义,null = 不动
        if (newTitle != null && !newTitle.isBlank()) {
            task.setTitle(newTitle);
        }
        if (newGoalDescription != null && !newGoalDescription.isBlank()) {
            task.setGoalDescription(newGoalDescription);
        }
        if (newMaxIterations != null && newMaxIterations > 0) {
            // 用户填的是"本次再跑几轮"。后端把它跟 baseOffset 累加,确保 maxIterations
            // 始终是绝对总轮数 —— UI 显示 currentIteration / maxIterations 才不会在
            // refine 后出现 "10 / 5" 这种 currentIteration 超过 max 的怪状态。
            int baseOffset = (int) iterationRepository.countByTaskId(taskId);
            task.setMaxIterations(baseOffset + newMaxIterations);
        }
        if (newConstraintRules != null && !newConstraintRules.isBlank()) {
            task.setConstraintRules(newConstraintRules);
        }
        // referenceDocuments / LLM 字段:null = 不动;空串 = 显式清空;非空 = 覆盖
        if (newReferenceDocuments != null) {
            task.setReferenceDocuments(newReferenceDocuments.isBlank() ? null : newReferenceDocuments);
        }
        if (newMetaLlmConfigId != null) task.setMetaLlmConfigId(newMetaLlmConfigId.isBlank() ? null : newMetaLlmConfigId);
        if (newMetaLlmModel != null)    task.setMetaLlmModel(newMetaLlmModel.isBlank() ? null : newMetaLlmModel);
        if (newTestLlmConfigId != null) task.setTestLlmConfigId(newTestLlmConfigId.isBlank() ? null : newTestLlmConfigId);
        if (newTestLlmModel != null)    task.setTestLlmModel(newTestLlmModel.isBlank() ? null : newTestLlmModel);
        // 老兼容字段 llm_config_id 跟着 meta 走
        if (newMetaLlmConfigId != null) task.setLlmConfigId(newMetaLlmConfigId.isBlank() ? null : newMetaLlmConfigId);

        task.setTestCasesJson("[]");           // 强制 runner 重新派生场景(用新规则 + 新 LLM)
        // 不重置 currentIteration —— runLoop 会基于 iterationRepository.countByTaskId 算 baseOffset
        // 续号(避免跟历史 iter_no 撞唯一约束)。currentIteration 字段在 runLoop 里也会被更新成绝对 iter_no。
        task.setStatus("PENDING");
        task.setFinalSummary(null);
        task.setErrorDetail(null);
        taskRepository.save(task);
        start(taskId);
    }
}
