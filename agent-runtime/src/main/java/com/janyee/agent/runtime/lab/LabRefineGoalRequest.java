package com.janyee.agent.runtime.lab;

import java.util.List;

/**
 * Lab "AI 帮我完善目标描述" 的请求体。
 * 用户在 Lab 创建 modal 上点 "让 AI 帮我完善",前端把当前草稿 + 对话历史 + 这一轮新消息发过来,
 * 后端调一次 meta-LLM(用户在 modal 选的 metaLlmConfigId/metaLlmModel,缺省走 default),
 * 返回新的草稿 + AI 的解释,前端展示对话气泡;用户多轮迭代直到满意,点 "采用" 把最新草稿
 * 写回 labForm.goalDescription。
 *
 * @param draft         当前草稿(用户已经在表单里填的目标描述)
 * @param userMessage   这一轮用户输入(可以是 "再具体一点" / "把工具列表加进去" 等)
 * @param history       之前几轮对话历史 [{role:"user|assistant", content:"..."}]
 * @param llmConfigId   用哪个 LLM 配置(留空 = default)
 * @param llmModel      LLM 配置下的具体 model(留空 = LLM 内部 default)
 */
public record LabRefineGoalRequest(
        String draft,
        String userMessage,
        List<Turn> history,
        String llmConfigId,
        String llmModel
) {
    public record Turn(String role, String content) {}
}
