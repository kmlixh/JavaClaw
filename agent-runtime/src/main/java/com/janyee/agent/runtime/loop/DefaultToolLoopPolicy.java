package com.janyee.agent.runtime.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.runtime.skill.SkillGuard;
import com.janyee.agent.tool.policy.ToolPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DefaultToolLoopPolicy implements ToolLoopPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolLoopPolicy.class);

    private final ApprovalRequirementService approvalRequirementService;
    private final ToolPolicyService toolPolicyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * artifact.* 内容里如果出现以下任一模式,认为是"占位符报告" —— LLM 没等查询结果就硬写,
     * 让用户先看着 placeholder 后面再修。直接拒绝,要求先调 db.query 把数据补齐。
     * 列表保守只放强信号词,避免误伤(单个 "—" 在表头分隔/列表 bullet 中常见,要看连续重复)。
     */
    private static final List<String> PLACEHOLDER_TEXT_MARKERS = List.of(
            "数据采集中", "暂无数据", "待补充", "待查询", "待填充", "待确认",
            "占位符", "占位中", "(数据缺失)", "数据缺失", "TBD", "TODO",
            "placeholder", "Placeholder"
    );
    /** 三个或更多连续 "—" / "-" 占位列(典型表格"暂无数据"行) */
    private static final Pattern PLACEHOLDER_DASH_RUN = Pattern.compile("(?:\\|\\s*[—–\\-]\\s*){3,}");

    public DefaultToolLoopPolicy(
            ApprovalRequirementService approvalRequirementService,
            ToolPolicyService toolPolicyService
    ) {
        this.approvalRequirementService = approvalRequirementService;
        this.toolPolicyService = toolPolicyService;
    }

    @Override
    public ToolCallDecision evaluate(ToolLoopContext context, ToolCallRequest request) {
        String toolName = request.toolName();
        if (!toolPolicyService.isAllowed(context.agentId(), toolName)) {
            return new ToolCallDecision(false, false, "tool not allowed by workspace policy", toolName, request.argumentsJson());
        }

        // Skill-driven artifact gate: when the active skill declares a specific artifact.*
        // deliverable via requiresSuccess, reject any other artifact.* call so the LLM can't
        // silently substitute a .word/.pptx when .md is required.
        SkillGuard guard = context.guard();
        if (guard != null && toolName != null && toolName.startsWith("artifact.")) {
            Set<String> allowed = guard.allowedArtifactTools();
            if (!allowed.isEmpty() && !allowed.contains(toolName)) {
                String reason = "skill restricts artifact output to " + allowed + "; refusing " + toolName;
                log.warn("tool.loop.artifact_not_allowed runId={}, toolName={}, allowed={}",
                        context.runId(), toolName, allowed);
                return new ToolCallDecision(false, false, reason, toolName, request.argumentsJson());
            }
            // Also refuse artifact.* when the skill requires a plan but none has been
            // created — prevents LLM jumping straight to the deliverable without executing
            // the data-gathering steps.
            if (guard.hasPlanEnforcement() && context.runPlan().isEmpty()) {
                String reason = "skill requires a plan with steps " + guard.requiredPlanStepIds()
                        + " before producing any artifact.*; call plan.create first and execute the data steps.";
                log.warn("tool.loop.artifact_before_plan runId={}, toolName={}", context.runId(), toolName);
                return new ToolCallDecision(false, false, reason, toolName, request.argumentsJson());
            }
        }

        // 占位符拦截:LLM 经常在数据没查全时就调 artifact.markdown 写"数据采集中""暂无数据""—"
        // 等占位,然后在 plan.update 阶段被 PlanStepRule 拒掉,再补查、再写 markdown,反复多轮
        // 浪费 token 和耗时。这里直接在工具调用前扫一遍 content/markdown 字段,出现强占位词就
        // 拒绝 + nudge "先把数据查全再写报告",从源头掐住"先写占位再修"的劣习。
        // 范围限定 artifact.*,且只有 skill 要求 plan(=正经报告任务)时才查 —— 否则误伤
        // 用户主动写"暂无数据"作为合法内容的场景。
        if (guard != null && toolName != null && toolName.startsWith("artifact.")
                && guard.hasPlanEnforcement()) {
            String placeholder = detectPlaceholderInArtifact(request.argumentsJson());
            if (placeholder != null) {
                String reason = "artifact 内容含未填充占位符 (\"" + placeholder + "\") —— "
                        + "请先调 db.query 把对应章节的数据查出来,把占位符替换成真实数字后再写报告。"
                        + "先 plan.update 关联的 data step,再 artifact.markdown,不要先写占位后修。";
                log.warn("tool.loop.artifact_placeholder_detected runId={}, toolName={}, marker={}",
                        context.runId(), toolName, placeholder);
                return new ToolCallDecision(false, false, reason, toolName, request.argumentsJson());
            }
        }

        // Plan 推进守卫:skill 要求 plan + plan 已 seed 但当前没有 IN_PROGRESS step + LLM 想调
        // 产出型工具(db.query / artifact.*)时,先逼它 plan.update 把下一个 PENDING step 推到
        // IN_PROGRESS。这条对应"先生成/推进 plan,再做事"的运行时硬约束 —— 之前 buildRunEndGateNudge
        // 只在 LLM 想结束 run 时检查,中间过程跳过 plan.update 直接调工具的没人管。
        // 不拦 plan.* / host.invoke / db.schema.inspect 这种非"step 工作工具":它们不属于具体 step。
        if (guard != null && guard.hasPlanEnforcement()
                && !context.runPlan().isEmpty()
                && isPlanWorkTool(toolName)
                && context.runPlan().currentInProgressStep().isEmpty()
                && context.runPlan().steps().stream()
                        .anyMatch(s -> s.status() == PlanStatus.PENDING)) {
            String hint = NextActionHint.suggest(context.runPlan()).orElse(
                    "call plan.update with {stepId:'<next pending>', status:'IN_PROGRESS'} first.");
            String reason = "plan has no IN_PROGRESS step but you tried to call " + toolName
                    + ". " + hint;
            log.warn("tool.loop.plan_step_not_active runId={}, toolName={}, hint={}",
                    context.runId(), toolName, hint);
            return new ToolCallDecision(false, false, reason, toolName, request.argumentsJson());
        }

        // Wave barrier 第二道防线:LLM 在 IN_PROGRESS step 上调工作工具时,如果该 step 的
        // dependsOn 列表里还有未完成的兄弟 step,直接拒。理论上 PlanUpdateTool 已经在 IN_PROGRESS
        // 转换时挡住了这种状况,但留这道防御以应对历史 plan 数据 / LLM 直接 mutate plan 的边角场景。
        // 只针对工作工具 db.query / artifact.* —— plan.update / 探索类工具不卡。
        if (guard != null && guard.hasPlanEnforcement()
                && isPlanWorkTool(toolName)
                && !context.runPlan().isEmpty()) {
            PlanStep active = context.runPlan().currentInProgressStep().orElse(null);
            if (active != null) {
                List<String> unmet = context.runPlan().unmetDependencies(active);
                if (!unmet.isEmpty()) {
                    String reason = "active step '" + active.id() + "' has unmet dependencies "
                            + unmet + "; revert it to PENDING and finish those steps first via plan.update.";
                    log.warn("tool.loop.active_step_deps_unmet runId={}, stepId={}, unmet={}",
                            context.runId(), active.id(), unmet);
                    return new ToolCallDecision(false, false, reason, toolName, request.argumentsJson());
                }
            }
        }

        if (approvalRequirementService.requiresApproval(context, toolName)) {
            return new ToolCallDecision(true, true, "approval required", toolName, request.argumentsJson());
        }
        return new ToolCallDecision(true, false, "allowed", toolName, request.argumentsJson());
    }

    /**
     * "plan 工作工具":属于具体 plan step 完成路径上必经的工具,典型是 db.query 和 artifact.*。
     * 这些工具被调用前,plan 必须有一个 IN_PROGRESS step。其它工具(plan.* 自身、host.invoke
     * 浏览器桥、db.schema.inspect 等诊断/探索类工具)放过 —— 它们不属于具体 step,不做这道校验。
     */
    private static boolean isPlanWorkTool(String toolName) {
        if (toolName == null) return false;
        if (toolName.startsWith("artifact.")) return true;
        return "db.query".equals(toolName);
    }

    /**
     * 扫 artifact.* 工具的 args(content / markdown / body 任一字段)中是否含强信号占位符。
     * 命中第一个就返回该 marker;没有返回 null。
     * 解析失败/字段缺失/字段为空 → 返回 null(允许通过,让工具自身处理参数错误)。
     */
    private String detectPlaceholderInArtifact(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return null;
        String body = null;
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            for (String field : new String[]{"content", "markdown", "body", "text"}) {
                JsonNode node = root.path(field);
                if (node.isTextual()) {
                    String value = node.asText("");
                    if (!value.isBlank()) {
                        body = value;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        if (body == null || body.isBlank()) return null;
        for (String marker : PLACEHOLDER_TEXT_MARKERS) {
            if (body.contains(marker)) return marker;
        }
        if (PLACEHOLDER_DASH_RUN.matcher(body).find()) return "连续多个 — / - 占位列";
        return null;
    }
}
