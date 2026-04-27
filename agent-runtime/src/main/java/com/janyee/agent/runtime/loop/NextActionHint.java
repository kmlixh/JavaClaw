package com.janyee.agent.runtime.loop;

import java.util.Optional;

/**
 * 给 plan.* 工具错误 / 幂等返回用的"你下一步到底该干嘛"提示生成器。
 *
 * <p>动机：观察到被 plan.create 幂等或 plan.update 拒绝后，LLM 往往重新开场白 + 再调一次
 * plan.create —— 说明它没看懂"下一步"。把具体的 tool 名 + 参数模板塞进错误文本，LLM 往往
 * 就能直接按着做，省掉几个无效 iteration。</p>
 *
 * <p>不要在这里做任何状态修改 —— 只读 plan 快照，返回一个字符串。调用方自己决定往哪个
 * 错误消息里拼。</p>
 */
public final class NextActionHint {

    private NextActionHint() {
    }

    /**
     * 基于当前 plan 快照推断下一个应当推进的 step，给出"可以直接照抄"的建议调用语。
     * Plan 已全部 COMPLETED 时返回空 Optional —— 调用方应提示 run 结束。
     */
    public static Optional<String> suggest(RunPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return Optional.empty();
        }
        PlanStep inProgress = plan.currentInProgressStep().orElse(null);
        if (inProgress != null) {
            return Optional.of(describeInProgress(inProgress));
        }
        PlanStep nextPending = plan.steps().stream()
                .filter(step -> step.status() == PlanStatus.PENDING)
                .findFirst()
                .orElse(null);
        if (nextPending == null) {
            return Optional.empty();
        }
        return Optional.of(describePendingStart(nextPending));
    }

    /**
     * 基于一个特定 step 的规则违规给提示 —— 例如 LLM 试图把 PENDING 的 sector 直接跳到
     * COMPLETED，我们告诉它先 IN_PROGRESS、跑 db.query、再 COMPLETED。
     */
    public static String forCompletionRejection(PlanStep step) {
        if (step == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Next action for step '").append(step.id()).append("':");
        if (step.status() == PlanStatus.PENDING) {
            builder.append(" (1) call plan.update with {\"stepId\":\"")
                    .append(step.id()).append("\",\"status\":\"IN_PROGRESS\"};");
        } else if (step.status() != PlanStatus.IN_PROGRESS) {
            builder.append(" (1) transition stepId='").append(step.id())
                    .append("' to IN_PROGRESS first via plan.update;");
        } else {
            builder.append(" (1) step is already IN_PROGRESS — proceed;");
        }
        builder.append(' ').append(describeWorkTool(step, 2));
        builder.append(" Then (").append(step.toolHint().toLowerCase().startsWith("artifact.") ? "3" : "3")
                .append(") call plan.update with {\"stepId\":\"").append(step.id())
                .append("\",\"status\":\"COMPLETED\",\"resultNote\":\"...summary of what the tool produced...\"}.");
        return builder.toString();
    }

    private static String describeInProgress(PlanStep step) {
        StringBuilder builder = new StringBuilder();
        builder.append("Active step is '").append(step.id()).append("' (IN_PROGRESS). ")
                .append(describeWorkTool(step, 1));
        builder.append(" Then call plan.update with {\"stepId\":\"").append(step.id())
                .append("\",\"status\":\"COMPLETED\",\"resultNote\":\"...\"}.");
        return builder.toString();
    }

    private static String describePendingStart(PlanStep step) {
        StringBuilder builder = new StringBuilder();
        builder.append("Next pending step is '").append(step.id()).append("'.")
                .append(" (1) call plan.update with {\"stepId\":\"").append(step.id())
                .append("\",\"status\":\"IN_PROGRESS\"}; ")
                .append(describeWorkTool(step, 2))
                .append(" Then (3) call plan.update with {\"stepId\":\"").append(step.id())
                .append("\",\"status\":\"COMPLETED\",\"resultNote\":\"...\"}.");
        return builder.toString();
    }

    /**
     * 根据 toolHint 决定该 step 中间做什么：
     * - artifact.* → 建议调用对应 artifact 工具；
     * - db.query  → 建议从 plan 里的 sql 模板选一套 admin / geojson / no filter 抄到 sql 字段；
     * - 其他      → 直接说 toolHint 是什么。
     */
    private static String describeWorkTool(PlanStep step, int ordinal) {
        String hint = step.toolHint() == null ? "" : step.toolHint().trim().toLowerCase();
        if (hint.startsWith("artifact.")) {
            return "(" + ordinal + ") call " + hint
                    + " with the final deliverable content (markdown for artifact.markdown etc);";
        }
        if ("db.query".equals(hint)) {
            StringBuilder builder = new StringBuilder();
            builder.append("(").append(ordinal).append(") call db.query");
            if (step.jdbcUrl() != null && !step.jdbcUrl().isBlank()) {
                builder.append(" with {\"jdbcUrl\":\"").append(step.jdbcUrl()).append("\",\"sql\":\"...\"}");
            } else {
                builder.append(" with {\"jdbcUrl\":\"<from plan>\",\"sql\":\"...\"}");
            }
            if (!step.sqlTemplatesGeoJson().isEmpty() || !step.sqlTemplates().isEmpty() || !step.sqlTemplatesNoFilter().isEmpty()) {
                builder.append(" — copy the sql from the plan step (")
                        .append(availableSqlVariants(step)).append(");");
            } else {
                builder.append(";");
            }
            return builder.toString();
        }
        if (!hint.isEmpty()) {
            return "(" + ordinal + ") call " + hint + " as the step's work tool;";
        }
        return "(" + ordinal + ") invoke the work tool declared by the step;";
    }

    private static String availableSqlVariants(PlanStep step) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        if (!step.sqlTemplatesGeoJson().isEmpty()) {
            builder.append(first ? "" : ", ").append("sql (geojson filter)");
            first = false;
        }
        if (!step.sqlTemplates().isEmpty()) {
            builder.append(first ? "" : ", ").append("sql (admin filter)");
            first = false;
        }
        if (!step.sqlTemplatesNoFilter().isEmpty()) {
            builder.append(first ? "" : ", ").append("sql (no filter)");
        }
        return builder.toString();
    }
}
