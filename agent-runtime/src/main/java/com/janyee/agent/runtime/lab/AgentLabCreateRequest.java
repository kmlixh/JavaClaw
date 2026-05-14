package com.janyee.agent.runtime.lab;

/**
 * 创建一个 Agent 实验室任务。Phase 1.5:聚焦 <b>Skill 调试 / 创建</b>。
 *
 * <p><b>不再要求用户写测试用例</b>。用户自然化描述若干约束规则(constraintRules)和可选的
 * 参考文档(referenceDocuments),meta-LLM 自己据此生成测试场景并迭代调试。这跟"AI 自动
 * 调试 Agent / Skill"的初衷一致 —— 用户只描述输入/输出契约,不指定调用什么工具/做什么步骤。
 *
 * <h3>三种 mode(都是针对 Skill 的)</h3>
 * <ul>
 *   <li>{@code NEW}        —— 新建 skill,meta-LLM 从零设计 promptTemplate / configJson</li>
 *   <li>{@code EXISTING}   —— 选已有 skill,每轮 meta-LLM 覆盖它的 promptTemplate / configJson</li>
 *   <li>{@code CLONE_FROM} —— 从源 skill 克隆配置作起点,源不动</li>
 * </ul>
 *
 * <h3>Host Agent 三种 mode</h3>
 * <ul>
 *   <li>{@code EXISTING}   —— 选已有 agent 当 host;{@code hostAgentId} 必填</li>
 *   <li>{@code NEW}        —— 现场新建空 Agent;{@code newHostAgentId} 必填,可选填
 *       {@code newHostAgentSeedPrompt}(短一句话描述,meta-LLM 第一轮据此扩写正式 systemPrompt)</li>
 *   <li>{@code CLONE_FROM} —— 从源 Agent 克隆 systemPrompt 作起点;{@code newHostAgentId} +
 *       {@code cloneFromHostAgentId} 必填</li>
 * </ul>
 *
 * <p>{@code allowAgentEvolution}:false(默认)迭代只改 Skill;true 时每轮 meta-LLM 可同时改 Agent。</p>
 */
public record AgentLabCreateRequest(
        String title,
        String goalDescription,
        // 约束规则:用户自然语言描述"什么样的输入应该出什么样的输出 / 边界条件 / 必须涵盖的事实"等。
        // meta-LLM 据此自己派生测试场景。必填。
        String constraintRules,
        // 参考文档:可选,用户粘贴的文本资料(数据字典 / 业务规范 / 样例报告等),
        // meta-LLM 在派生测试场景和设计 prompt 时一并参考。
        String referenceDocuments,
        Integer maxIterations,           // null/<=0 用默认 5,上限 20

        // 目标 Skill (mode = EXISTING / NEW / CLONE_FROM)
        String mode,
        String targetSkillName,          // EXISTING 时必填
        String newSkillName,             // NEW / CLONE_FROM 时必填
        String cloneFromSkillName,       // CLONE_FROM 时必填
        String targetScopeType,          // NEW / CLONE_FROM 时必填(SYSTEM / TENANT / USER)
        String targetScopeTenantId,      // TENANT 时必填

        // Host Agent (hostAgentMode = EXISTING / NEW / CLONE_FROM,默认 EXISTING)
        String hostAgentMode,
        String hostAgentId,                       // EXISTING 时必填
        String newHostAgentId,                    // NEW / CLONE_FROM 时必填
        String newHostAgentDisplayName,           // NEW / CLONE_FROM 时可选,留空用 task title
        String newHostAgentSeedPrompt,            // NEW 时可选 —— 用户写一句描述,meta-LLM 据此扩写
        String newHostAgentScopeType,             // NEW / CLONE_FROM 时(SYSTEM / TENANT / USER)
        String newHostAgentScopeTenantId,         // newHostAgentScopeType=TENANT 时必填
        String cloneFromHostAgentId,              // CLONE_FROM 时必填

        // false=只改 Skill;true=meta-LLM 也能改 Agent
        Boolean allowAgentEvolution,

        // LLM 双栏 —— configId + apiModel 一起,跟会话面板一致(LLM 配置可挂多个 model)
        String metaLlmConfigId,          // 设计师 LLM,留空 = default
        String metaLlmModel,             // 设计师 LLM 的 apiModel(可选,留空让 LLM 用其内部默认)
        String testLlmConfigId,          // 跑测试 LLM,留空 = Agent 默认
        String testLlmModel              // 测试 LLM 的 apiModel(可选)
) {
}
