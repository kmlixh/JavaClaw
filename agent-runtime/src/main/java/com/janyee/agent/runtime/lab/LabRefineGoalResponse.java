package com.janyee.agent.runtime.lab;

/**
 * "AI 帮我完善目标描述" 的响应。
 *
 * @param refinedGoal       AI 给出的最新完整目标描述(用户点 "采用此版本" 时整段替换 labForm.goalDescription)
 * @param assistantMessage  AI 跟用户解释这一轮改了什么 / 还可以再补充什么(展示在对话气泡里)
 */
public record LabRefineGoalResponse(
        String refinedGoal,
        String assistantMessage
) {
}
