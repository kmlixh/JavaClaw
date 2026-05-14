package com.janyee.agent.infra.lab;

import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.AuthService;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import com.janyee.agent.infra.persistence.entity.AgentLabIterationEntity;
import com.janyee.agent.infra.persistence.entity.AgentLabTaskEntity;
import com.janyee.agent.infra.persistence.repository.AgentLabIterationRepository;
import com.janyee.agent.infra.persistence.repository.AgentLabTaskRepository;
import com.janyee.agent.runtime.admin.AdminCatalogService;
import com.janyee.agent.runtime.admin.AgentDefinitionView;
import com.janyee.agent.runtime.admin.SkillDefinitionCommand;
import com.janyee.agent.runtime.admin.SkillDefinitionView;
import com.janyee.agent.runtime.lab.AgentLabCreateRequest;
import com.janyee.agent.runtime.lab.AgentLabIterationView;
import com.janyee.agent.runtime.lab.AgentLabTaskDetail;
import com.janyee.agent.runtime.lab.AgentLabTaskView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 实验室任务的 CRUD + 异步启动入口。
 *
 * <h3>Phase 1.5:聚焦 Skill 调试</h3>
 * <p>所有新建 task 的 target_type 都是 SKILL —— 用户选/新建一个 Skill,选一个 host
 * Agent(不会被改),meta-LLM 反复迭代 Skill 的 promptTemplate / configJson。</p>
 *
 * <p>三种 mode 都是针对 <b>Skill</b> 的:
 * <ul>
 *   <li>{@code NEW}        —— 新建 skill,meta-LLM 从零设计;host_agent 必填(已存在)</li>
 *   <li>{@code EXISTING}   —— 选已有 skill,直接迭代它的 promptTemplate / configJson</li>
 *   <li>{@code CLONE_FROM} —— 从源 skill 克隆作起点,源不动</li>
 * </ul>
 * 老任务的 target_type=AGENT(V33 行为)继续显示在列表里,但不再支持新建。</p>
 *
 * <p>权限:任一即放行 —— {@code session.read.all} (系统管理员) 或 {@code agent.lab.use};
 * 匿名兜底直接放。</p>
 */
@Service
public class AgentLabService {

    private static final Set<String> ALLOWED_PERMISSIONS =
            Set.of("session.read.all", "agent.lab.use");
    private static final Set<String> SCOPE_TYPES = Set.of("SYSTEM", "TENANT", "USER");
    private static final Set<String> MODES = Set.of("EXISTING", "NEW", "CLONE_FROM");

    private final AgentLabTaskRepository taskRepository;
    private final AgentLabIterationRepository iterationRepository;
    private final AgentLabRunner runner;
    private final AdminCatalogService adminCatalogService;

    public AgentLabService(AgentLabTaskRepository taskRepository,
                           AgentLabIterationRepository iterationRepository,
                           AgentLabRunner runner,
                           AdminCatalogService adminCatalogService) {
        this.taskRepository = taskRepository;
        this.iterationRepository = iterationRepository;
        this.runner = runner;
        this.adminCatalogService = adminCatalogService;
    }

    @Transactional
    public AgentLabTaskView create(AgentLabCreateRequest req) {
        requireLabAccess();
        AuthPrincipal principal = SecurityContextHolder.current();

        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (req.goalDescription() == null || req.goalDescription().isBlank()) {
            throw new IllegalArgumentException("goalDescription 不能为空");
        }
        if (req.constraintRules() == null || req.constraintRules().isBlank()) {
            throw new IllegalArgumentException("constraintRules 不能为空 —— 请用自然语言描述若干约束规则,AI 会据此派生测试场景");
        }

        String mode = (req.mode() == null ? "NEW" : req.mode().toUpperCase(Locale.ROOT));
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("mode 必须是 EXISTING / NEW / CLONE_FROM");
        }

        // host agent:三选一(选已有 / 新建 / 克隆)
        AgentDefinitionView host = resolveHostAgent(req);
        String hostAgentId = host.agentId();
        boolean allowAgentEvolution = Boolean.TRUE.equals(req.allowAgentEvolution());

        // 解析目标 Skill
        ResolvedSkill target = resolveSkill(mode, req, host);

        String taskId = "lab-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        AgentLabTaskEntity entity = new AgentLabTaskEntity();
        entity.setId(taskId);
        entity.setOwnerUserId(principal.userId());
        entity.setTitle(req.title());
        entity.setGoalDescription(req.goalDescription());
        entity.setConstraintRules(req.constraintRules());
        entity.setReferenceDocuments(blankToNull(req.referenceDocuments()));
        // test_cases_json 现在由 runner 在 round-1 之前用 meta-LLM 据 constraint_rules 派生,
        // 但 NOT NULL 约束还在 → 先填一个占位的空数组,避免 INSERT 失败。
        entity.setTestCasesJson("[]");
        entity.setMaxIterations(req.maxIterations() == null || req.maxIterations() <= 0 ? 5 : req.maxIterations());
        entity.setCurrentIteration(0);
        entity.setStatus("PENDING");
        entity.setMode(mode);
        entity.setTargetType("SKILL");
        entity.setTargetAgentId(hostAgentId);                 // host(不被改)
        entity.setTargetSkillName(target.skillName);          // 实际被迭代的 skill_name
        entity.setNewSkillName(blankToNull(req.newSkillName()));
        entity.setCloneFromSkillName(blankToNull(req.cloneFromSkillName()));
        entity.setTargetScopeType(target.scopeType);
        entity.setTargetScopeTenantId(target.scopeTenantId);
        entity.setMetaLlmConfigId(blankToNull(req.metaLlmConfigId()));
        entity.setMetaLlmModel(blankToNull(req.metaLlmModel()));
        entity.setTestLlmConfigId(blankToNull(req.testLlmConfigId()));
        entity.setTestLlmModel(blankToNull(req.testLlmModel()));
        entity.setLlmConfigId(blankToNull(req.metaLlmConfigId()));   // 兼容老字段
        entity.setSandboxAgentId(hostAgentId);                       // 兼容老字段
        entity.setAllowAgentEvolution(allowAgentEvolution);
        taskRepository.save(entity);

        runner.start(taskId);
        return toView(entity);
    }

    @Transactional(readOnly = true)
    public List<AgentLabTaskView> list() {
        requireLabAccess();
        return taskRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public AgentLabTaskDetail getDetail(String taskId) {
        requireLabAccess();
        AgentLabTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        List<AgentLabIterationView> iters = iterationRepository
                .findByTaskIdOrderByIterationNoAsc(taskId).stream()
                .map(this::toIterView).toList();
        return new AgentLabTaskDetail(toView(task), iters);
    }

    public boolean cancel(String taskId) {
        requireLabAccess();
        return runner.cancel(taskId);
    }

    @Transactional
    public AgentLabTaskView restart(String taskId) {
        requireLabAccess();
        AgentLabTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        task.setStatus("PENDING");
        // 不重置 currentIteration —— runLoop 用 baseOffset 续号 iter_no(避免唯一约束撞车)。
        // 重置成 0 在历史已经有 iter 时会导致 (task_id, iteration_no=1) 冲突 → 任务直接 crash。
        task.setFinalSummary(null);
        task.setErrorDetail(null);
        taskRepository.save(task);
        runner.start(taskId);
        return toView(task);
    }

    /**
     * 改写任务参数后再次迭代。所有字段 patch 语义 —— 详情页用"调参面板"全字段提交,
     * 也允许只改一两项。
     */
    @Transactional
    public AgentLabTaskView refineAndRestart(String taskId,
                                             String title, String goalDescription, Integer maxIterations,
                                             String constraintRules, String referenceDocuments,
                                             String metaLlmConfigId, String metaLlmModel,
                                             String testLlmConfigId, String testLlmModel) {
        requireLabAccess();
        AgentLabTaskEntity existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        boolean hasNewRules = constraintRules != null && !constraintRules.isBlank();
        if (!hasNewRules
                && (existing.getConstraintRules() == null || existing.getConstraintRules().isBlank())) {
            throw new IllegalArgumentException("constraintRules 不能为空");
        }
        // maxIterations 夹紧到硬上限
        Integer clamped = maxIterations == null
                ? null
                : Math.min(Math.max(maxIterations, 1), 20);
        runner.refineAndRestart(taskId,
                title, goalDescription, clamped,
                hasNewRules ? constraintRules : null,
                referenceDocuments,
                metaLlmConfigId, metaLlmModel, testLlmConfigId, testLlmModel);
        AgentLabTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        return toView(task);
    }

    // ---------------------------------------------------------------------------------------
    // mode 解析:确认 target Skill 的 skill_name + scope

    private ResolvedSkill resolveSkill(String mode, AgentLabCreateRequest req, AgentDefinitionView host) {
        switch (mode) {
            case "EXISTING": {
                String name = blankToNull(req.targetSkillName());
                if (name == null) throw new IllegalArgumentException("EXISTING 模式必须填 targetSkillName");
                SkillDefinitionView sk = findSkill(name);
                if (sk == null) throw new IllegalArgumentException("targetSkillName 不存在: " + name);
                return new ResolvedSkill(name, sk.scopeType(), sk.scopeTenantId());
            }
            case "NEW": {
                // newSkillName 留空 → 据 title 自动生成,免去用户手填(用户提了:能 AI 生成的就别让人填)。
                // 名字带轻量 slug + 6 位 uuid 后缀,既能从命名里看出来源,又避免冲突。
                String name = blankToNull(req.newSkillName());
                if (name == null) {
                    name = autoSkillName(req.title());
                }
                if (findSkill(name) != null) {
                    // 极小概率冲突 → 再加 4 位随机重试一次
                    name = autoSkillName(req.title()) + "-" + shortUuid(4);
                    if (findSkill(name) != null) {
                        throw new IllegalArgumentException("自动生成的 skill_name 仍冲突,请手填: " + name);
                    }
                }
                String scope = normalizeScope(req.targetScopeType());
                String tenantId = "TENANT".equals(scope) ? requireNonBlank(req.targetScopeTenantId(),
                        "TENANT scope 必须填 targetScopeTenantId") : null;
                // NEW 模式不预创建 skill —— 等 meta-LLM 第一轮设计后由 runner 写入。
                return new ResolvedSkill(name, scope, tenantId);
            }
            case "CLONE_FROM": {
                String src = blankToNull(req.cloneFromSkillName());
                String newName = blankToNull(req.newSkillName());
                if (src == null) throw new IllegalArgumentException("CLONE_FROM 必须填 cloneFromSkillName");
                if (newName == null) {
                    newName = autoSkillName(req.title());     // 同 NEW:留空自动生成
                }
                SkillDefinitionView srcSkill = findSkill(src);
                if (srcSkill == null) throw new IllegalArgumentException("cloneFromSkillName 不存在: " + src);
                if (findSkill(newName) != null) {
                    newName = autoSkillName(req.title()) + "-" + shortUuid(4);
                    if (findSkill(newName) != null) {
                        throw new IllegalArgumentException("自动生成的 skill_name 仍冲突,请手填: " + newName);
                    }
                }
                String scope = normalizeScope(req.targetScopeType());
                String tenantId = "TENANT".equals(scope) ? requireNonBlank(req.targetScopeTenantId(),
                        "TENANT scope 必须填 targetScopeTenantId") : null;
                cloneSkill(srcSkill, newName, host.agentId(), scope, tenantId);
                return new ResolvedSkill(newName, scope, tenantId);
            }
            default:
                throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    /**
     * 解析 host agent。三种 mode:
     * <ul>
     *   <li>EXISTING — 选已有 agent</li>
     *   <li>NEW      — 现场新建一个 Agent;可选 seed prompt(meta-LLM 第一轮据此扩写)</li>
     *   <li>CLONE_FROM — 从源 Agent 克隆 systemPrompt 作起点</li>
     * </ul>
     * 兼容老调用:hostAgentMode 留空时按现有字段推断(hostAgentId / newHostAgentId 二选一)。
     */
    private AgentDefinitionView resolveHostAgent(AgentLabCreateRequest req) {
        String hostMode = req.hostAgentMode() == null ? "" : req.hostAgentMode().toUpperCase(Locale.ROOT);
        String existing = blankToNull(req.hostAgentId());
        String newId = blankToNull(req.newHostAgentId());
        String cloneSrc = blankToNull(req.cloneFromHostAgentId());

        // 兼容:hostAgentMode 为空时按填了哪些字段推断
        if (hostMode.isEmpty()) {
            if (existing != null && newId == null && cloneSrc == null) hostMode = "EXISTING";
            else if (newId != null && cloneSrc == null) hostMode = "NEW";
            else if (cloneSrc != null) hostMode = "CLONE_FROM";
            else throw new IllegalArgumentException("必须指定 Host Agent (EXISTING / NEW / CLONE_FROM)");
        }
        if (!MODES.contains(hostMode)) {
            throw new IllegalArgumentException("hostAgentMode 必须是 EXISTING / NEW / CLONE_FROM");
        }

        switch (hostMode) {
            case "EXISTING": {
                if (existing == null) throw new IllegalArgumentException("EXISTING 模式必须填 hostAgentId");
                AgentDefinitionView found = findAgent(existing);
                if (found == null) throw new IllegalArgumentException("hostAgentId 不存在: " + existing);
                return found;
            }
            case "NEW": {
                // newHostAgentId 留空 → 据 title 自动生成 lab-host-{slug}-{uuid 后缀}
                if (newId == null) {
                    newId = autoAgentId(req.title());
                }
                if (findAgent(newId) != null) {
                    newId = autoAgentId(req.title()) + "-" + shortUuid(4);
                    if (findAgent(newId) != null) {
                        throw new IllegalArgumentException("自动生成的 agent_id 仍冲突,请手填: " + newId);
                    }
                }
                String scope = normalizeScope(req.newHostAgentScopeType() == null
                        || req.newHostAgentScopeType().isBlank() ? "SYSTEM" : req.newHostAgentScopeType());
                String tenantId = "TENANT".equals(scope) ? requireNonBlank(req.newHostAgentScopeTenantId(),
                        "TENANT scope 的新 Host Agent 必须填 newHostAgentScopeTenantId") : null;
                String displayName = req.newHostAgentDisplayName() == null
                        || req.newHostAgentDisplayName().isBlank()
                        ? (req.title() == null ? newId : req.title())
                        : req.newHostAgentDisplayName();
                // seed prompt 落到新 Agent 的 systemPrompt。
                // 用户没填 seed 时给一个最小可用默认 —— 之前给空 prompt,LLM 没指引
                // 经常上来就 file.list workspaces/<agent-id>/ 探查文件系统,但 lab-host 的
                // workspace 目录根本没建,直接 'is different type of Path' 失败白跑一轮。
                // 默认 prompt 明确告诉 agent:别探文件系统,直接用注册的 Skill 回答。
                String seedPrompt = req.newHostAgentSeedPrompt() == null
                        || req.newHostAgentSeedPrompt().isBlank()
                        ? "你是 Agent 实验室创建的助手,专注用注册的 Skill 完成用户请求。\n"
                          + "禁止主动调用 file.list / file.read 等文件系统工具去探查工作区目录"
                          + " —— 实验室 host agent 没有预创建工作区,这类调用必失败。\n"
                          + "直接基于用户消息 + 已挂载的 Skill 回答即可。"
                        : req.newHostAgentSeedPrompt();
                com.janyee.agent.runtime.admin.AgentDefinitionCommand cmd =
                        new com.janyee.agent.runtime.admin.AgentDefinitionCommand(
                                newId, displayName, "(由 Agent 实验室创建,作 Skill 的运行宿主)",
                                seedPrompt, "", "",
                                Boolean.TRUE, scope, tenantId, null, null);
                adminCatalogService.saveAgentDefinition(cmd);
                return requireFreshAgent(newId);
            }
            case "CLONE_FROM": {
                if (cloneSrc == null) throw new IllegalArgumentException("CLONE_FROM 模式必须填 cloneFromHostAgentId");
                if (newId == null) {
                    newId = autoAgentId(req.title());     // 同 NEW:留空自动生成
                }
                AgentDefinitionView src = findAgent(cloneSrc);
                if (src == null) throw new IllegalArgumentException("cloneFromHostAgentId 不存在: " + cloneSrc);
                if (findAgent(newId) != null) {
                    newId = autoAgentId(req.title()) + "-" + shortUuid(4);
                    if (findAgent(newId) != null) {
                        throw new IllegalArgumentException("自动生成的 agent_id 仍冲突,请手填: " + newId);
                    }
                }
                String scope = normalizeScope(req.newHostAgentScopeType() == null
                        || req.newHostAgentScopeType().isBlank() ? "SYSTEM" : req.newHostAgentScopeType());
                String tenantId = "TENANT".equals(scope) ? requireNonBlank(req.newHostAgentScopeTenantId(),
                        "TENANT scope 必须填 newHostAgentScopeTenantId") : null;
                String displayName = req.newHostAgentDisplayName() == null
                        || req.newHostAgentDisplayName().isBlank()
                        ? src.displayName() + " (lab)"
                        : req.newHostAgentDisplayName();
                com.janyee.agent.runtime.admin.AgentDefinitionCommand cmd =
                        new com.janyee.agent.runtime.admin.AgentDefinitionCommand(
                                newId, displayName, src.description(),
                                src.systemPrompt(), src.agentMarkdown(), src.memoryMarkdown(),
                                Boolean.TRUE, scope, tenantId, null, null);
                adminCatalogService.saveAgentDefinition(cmd);
                return requireFreshAgent(newId);
            }
            default:
                throw new IllegalArgumentException("unknown hostAgentMode: " + hostMode);
        }
    }

    private AgentDefinitionView requireFreshAgent(String agentId) {
        AgentDefinitionView created = findAgent(agentId);
        if (created == null) {
            throw new IllegalStateException("host agent 创建后无法查回: " + agentId);
        }
        return created;
    }

    private AgentDefinitionView findAgent(String agentId) {
        return adminCatalogService.listAgentDefinitions().stream()
                .filter(a -> agentId.equals(a.agentId()))
                .findFirst().orElse(null);
    }

    private SkillDefinitionView findSkill(String skillName) {
        return adminCatalogService.listSkillDefinitions(null).stream()
                .filter(s -> skillName.equals(s.skillName()))
                .findFirst().orElse(null);
    }

    /** CLONE_FROM:复制源 Skill 的 promptTemplate / configJson / triggerKeywords 到新 skill_name。 */
    private void cloneSkill(SkillDefinitionView src, String newName, String hostAgentId,
                            String scope, String tenantId) {
        SkillDefinitionCommand cmd = new SkillDefinitionCommand(
                null,                                  // 让 service 按 skillName upsert
                hostAgentId,
                List.of(hostAgentId),
                newName,
                src.description(),
                src.promptTemplate(),
                src.configJson(),
                src.triggerKeywords(),
                true,
                scope,
                tenantId,
                null,
                null
        );
        adminCatalogService.saveSkillDefinition(cmd);
    }

    private String normalizeScope(String s) {
        String x = s == null ? "" : s.toUpperCase(Locale.ROOT);
        if (!SCOPE_TYPES.contains(x)) {
            throw new IllegalArgumentException("targetScopeType 必须是 SYSTEM / TENANT / USER");
        }
        return x;
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private AgentLabTaskView toView(AgentLabTaskEntity e) {
        return new AgentLabTaskView(
                e.getId(), e.getOwnerUserId(), e.getTitle(), e.getGoalDescription(),
                e.getTestCasesJson(),
                e.getConstraintRules(), e.getReferenceDocuments(),
                e.getMaxIterations(), e.getCurrentIteration(),
                e.getStatus(), e.getMode(),
                e.getTargetType() == null ? "SKILL" : e.getTargetType(),
                e.getTargetAgentId(), e.getTargetSkillName(),
                e.getNewSkillName(), e.getCloneFromSkillName(),
                e.getTargetScopeType(), e.getTargetScopeTenantId(),
                e.getCloneFromAgentId(),
                e.getSandboxSkillNamesJson(),
                e.getMetaLlmConfigId(), e.getTestLlmConfigId(),
                e.getLlmConfigId(),
                e.isAllowAgentEvolution(),
                e.getFinalSummary(), e.getErrorDetail(), e.getCreatedAt(), e.getUpdatedAt()
        );
    }

    private AgentLabIterationView toIterView(AgentLabIterationEntity e) {
        return new AgentLabIterationView(
                e.getId(), e.getTaskId(), e.getIterationNo(), e.getStatus(),
                e.getAgentSnapshotJson(), e.getSkillSnapshotsJson(), e.getTestResultsJson(),
                e.getRunTracesJson(),
                e.getPassedCount(), e.getTotalCount(),
                e.getEvaluationSummary(), e.getFixPlanJson(), e.getMetaLlmError(),
                e.getProgressStep(),
                e.getCreatedAt()
        );
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 据 task 标题派生标准化 agent_id。中文标题 slugify 后多半会被剥光,
     * 兜底用纯 uuid 后缀,保证 always 出一个合法 ID(`[a-z0-9-]+`)。
     */
    private String autoAgentId(String title) {
        String slug = slugify(title);
        String suffix = shortUuid(6);
        return "lab-host-" + (slug.isEmpty() ? suffix : slug + "-" + suffix);
    }

    /** 据 task 标题派生 skill_name,统一前缀 skill.lab. 便于在管理页一眼区分。 */
    private String autoSkillName(String title) {
        String slug = slugify(title);
        String suffix = shortUuid(6);
        return "skill.lab." + (slug.isEmpty() ? suffix : slug + "." + suffix);
    }

    /** 标题 → ASCII 小写 + 连字符的 slug。中文/全角字符全过滤,长度 ≤ 24。 */
    private String slugify(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", "-");
        String cleaned = lower.replaceAll("[^a-z0-9-]", "")
                              .replaceAll("-{2,}", "-")
                              .replaceAll("^-+|-+$", "");
        return cleaned.length() > 24 ? cleaned.substring(0, 24) : cleaned;
    }

    private String shortUuid(int len) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, Math.min(len, hex.length()));
    }

    private void requireLabAccess() {
        AuthPrincipal p = SecurityContextHolder.current();
        if (p == null) throw new AuthService.AuthException("forbidden", "missing principal");
        if (p.anonymous()) return;
        for (String perm : ALLOWED_PERMISSIONS) {
            if (p.permissions().contains(perm)) return;
        }
        throw new AuthService.AuthException("forbidden",
                "agent lab requires one of: " + ALLOWED_PERMISSIONS);
    }

    private record ResolvedSkill(String skillName, String scopeType, String scopeTenantId) {
    }
}
