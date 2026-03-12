# Tool Loop 状态机类设计说明

本文档是**独立文件**，只描述 Tool Loop 子系统，不依赖前一份文档的附录位置。

目标：为 Codex 提供可直接读取的类级设计规格，用于生成 Java 版 Agent Runtime 中的 Tool Loop 状态机相关代码。

适用范围：
- Java 21
- Spring Boot 3.x
- Agent Runtime Phase 2
- 单次 Run 内串行 Tool Call

不包含：
- 具体 Tool 实现
- 完整审批流实现
- 多 Agent Router
- Memory 分层实现

---

## 1. 设计目标

Tool Loop 的目标是把一次 Agent Run 中的以下过程做成一个**可控、可审计、可恢复、可串行**的运行子系统：

```text
MODEL_REQUEST
 -> MODEL_STREAM
 -> TOOL_CALL_DETECTED
 -> TOOL_POLICY_CHECK
 -> TOOL_EXECUTION
 -> TOOL_RESULT_APPEND
 -> MODEL_RESUME
 -> FINISH
```

强约束：

- 同一 Run 内工具调用必须串行
- 不允许并行 Tool Call
- 必须限制最大工具迭代次数
- 所有 Tool Call 必须留下结构化记录
- 工具执行前必须经过策略检查
- Tool Result 回填格式必须稳定

---

## 2. Codex 读取模板

后续所有类说明都遵循这个模板：

```text
ClassName:
  Module:
  Type:
  Purpose:
  Responsibilities:
  NonResponsibilities:
  Inputs:
  Outputs:
  Dependencies:
  State:
  KeyMethods:
  Constraints:
  FailureModes:
  Persistence:
  Observability:
  ImplementationNotes:
```

---

## 3. 包结构建议

```text
agent-runtime/
  loop/
    ToolLoopState.java
    ToolLoopContext.java
    ToolLoopResult.java
    ToolLoopIteration.java
    ToolCallRequest.java
    ToolCallDecision.java
    ToolCallOutcome.java
    ToolLoopOrchestrator.java
    DefaultToolLoopOrchestrator.java
    ToolCallDetector.java
    ModelTurnExecutor.java
    ModelTurnResult.java
    ToolLoopPolicy.java
    ToolExecutor.java
    ToolResultAppender.java
    ToolLoopException.java
    MaxToolIterationExceededException.java
    ToolCallRejectedException.java
    ToolExecutionFailedException.java

agent-tool/
  registry/
    ToolRegistry.java
    DefaultToolRegistry.java
```

---

## 4. 状态机定义

### ClassName: `ToolLoopState`

Module:
- `agent-runtime.loop`

Type:
- Enum

Purpose:
- 定义 Tool Loop 内部状态。

Responsibilities:
- 约束状态推进。
- 为日志、事件流和调试提供统一状态语义。

NonResponsibilities:
- 不执行任何业务逻辑。

Inputs:
- 无

Outputs:
- 供 Orchestrator 和日志使用的状态值。

Dependencies:
- 无

State:
- 不可变枚举值。

KeyMethods:
- 无

RecommendedValues:
- `INITIALIZING`
- `MODEL_REQUESTING`
- `MODEL_STREAMING`
- `TOOL_CALL_DETECTED`
- `TOOL_POLICY_CHECKING`
- `TOOL_EXECUTING`
- `TOOL_RESULT_APPENDING`
- `MODEL_RESUMING`
- `COMPLETED`
- `FAILED`
- `WAITING_APPROVAL`

Constraints:
- 不允许跳过 `TOOL_POLICY_CHECKING` 直接执行工具。
- 不允许 `TOOL_EXECUTING` 后直接 `COMPLETED`。

FailureModes:
- 无

Persistence:
- 可映射到 run audit 或 debug snapshot，但枚举本身不持久化。

Observability:
- 应出现在状态日志与 `AgentEvent` 中。

ImplementationNotes:
- 建议后续把状态转换合法性校验下沉到 Context 或 StateMachine 辅助类。

---

## 5. 核心上下文类

### ClassName: `ToolLoopContext`

Module:
- `agent-runtime.loop`

Type:
- Mutable Context Object

Purpose:
- 保存一次 Tool Loop 运行所需的中间状态与输入输出。

Responsibilities:
- 保存 `sessionId`、`runId`、`agentId`、`userId`。
- 保存当前 `ToolLoopState`。
- 保存迭代次数与最大迭代次数。
- 保存当前 PromptContext。
- 保存本轮模型输出。
- 保存待执行的 ToolCallRequest。
- 保存所有迭代记录和工具执行结果。
- 保存最终 assistant 文本。

NonResponsibilities:
- 不调用模型。
- 不执行工具。
- 不做数据库落库。

Inputs:
- `RunRequest`
- `PromptContext`
- 配置中的 `maxIterations`

Outputs:
- 提供给 Orchestrator 与各执行器读取和修改。

Dependencies:
- 无直接外部依赖。

State:
- 可变。

RecommendedFields:
- `String sessionId`
- `String runId`
- `String agentId`
- `String userId`
- `ToolLoopState state`
- `int iterationCount`
- `int maxIterations`
- `PromptContext promptContext`
- `StringBuilder assistantTextBuffer`
- `String lastModelRawOutput`
- `ToolCallRequest pendingToolCall`
- `List<ToolLoopIteration> iterations`
- `List<ToolCallOutcome> toolOutcomes`

KeyMethods:
- `advanceState(ToolLoopState nextState)`
- `incrementIteration()`
- `setPendingToolCall(ToolCallRequest request)`
- `clearPendingToolCall()`
- `appendAssistantText(String delta)`
- `recordIteration(ToolLoopIteration iteration)`

Constraints:
- `iterationCount` 不得超过 `maxIterations`。
- 同一时刻只能有一个 `pendingToolCall`。
- 状态推进必须有序。

FailureModes:
- 状态推进非法。
- 迭代次数越界。
- 上下文关键字段缺失。

Persistence:
- 不直接持久化，但可被映射为调试快照。

Observability:
- 应作为日志上下文来源。

ImplementationNotes:
- 建议使用普通 class，不要使用 record。
- 建议加 `validateInvariant()` 方法用于调试。

### ClassName: `ToolLoopIteration`

Module:
- `agent-runtime.loop`

Type:
- Value Object

Purpose:
- 表示 Tool Loop 的单次迭代快照。

Responsibilities:
- 保存单次迭代的开始状态、工具请求、决策、结果和结束状态。

NonResponsibilities:
- 不推进状态。
- 不写日志。

Inputs:
- 来自 Orchestrator 在每轮结束时构造。

Outputs:
- 供审计、调试、问题回放使用。

Dependencies:
- `ToolCallRequest`
- `ToolCallDecision`
- `ToolCallOutcome`

State:
- 不可变。

RecommendedFields:
- `int iterationNo`
- `String modelRequestSummary`
- `ToolCallRequest toolCallRequest`
- `ToolCallDecision decision`
- `ToolCallOutcome outcome`
- `ToolLoopState startState`
- `ToolLoopState endState`

KeyMethods:
- record 默认访问器即可。

Constraints:
- `iterationNo` 从 1 开始递增。

FailureModes:
- 无自身失败逻辑。

Persistence:
- 可序列化到审计日志或 run_snapshot。

Observability:
- 适合问题排查时输出。

ImplementationNotes:
- `modelRequestSummary` 只保留摘要，不要保存过长原始 prompt。

### ClassName: `ToolLoopResult`

Module:
- `agent-runtime.loop`

Type:
- Value Object

Purpose:
- 表示 Tool Loop 执行结束后的整体结果。

Responsibilities:
- 返回最终 assistant 文本。
- 返回最终状态。
- 返回所有工具调用结果。
- 返回失败信息。

NonResponsibilities:
- 不负责把结果转成 HTTP/SSE。

Inputs:
- 由 Orchestrator 构造。

Outputs:
- 供 `AgentRunner` 消费。

Dependencies:
- `ToolCallOutcome`
- `ToolLoopIteration`

State:
- 不可变。

RecommendedFields:
- `boolean success`
- `ToolLoopState finalState`
- `String finalAssistantText`
- `List<ToolCallOutcome> toolOutcomes`
- `List<ToolLoopIteration> iterations`
- `String errorMessage`

KeyMethods:
- record 默认访问器即可。

Constraints:
- `success = true` 时 `finalState` 应为 `COMPLETED`。
- `success = false` 时必须有失败原因。

FailureModes:
- 无自身失败逻辑。

Persistence:
- 可映射到 run_record 与 audit。

Observability:
- 适合输出最终摘要日志。

ImplementationNotes:
- `finalAssistantText` 建议只保存最终可见文本，不保存工具内部中间文本。

---

## 6. 工具请求与决策对象

### ClassName: `ToolCallRequest`

Module:
- `agent-runtime.loop`

Type:
- Value Object

Purpose:
- 表示模型请求的一次工具调用。

Responsibilities:
- 提供标准化的工具名称、参数和原始片段。

NonResponsibilities:
- 不负责解析自身。
- 不负责执行工具。

Inputs:
- `ToolCallDetector`

Outputs:
- `ToolLoopPolicy` 与 `ToolExecutor`

Dependencies:
- 无

State:
- 不可变。

RecommendedFields:
- `String requestId`
- `String toolName`
- `String argumentsJson`
- `String rawModelFragment`

KeyMethods:
- record 默认访问器即可。

Constraints:
- `toolName` 不能为空。
- `argumentsJson` 必须是可验证的 JSON 字符串。

FailureModes:
- 无自身失败逻辑。

Persistence:
- 应写入 tool audit。

Observability:
- requestId 应出现在日志中。

ImplementationNotes:
- `requestId` 用于 tool audit 和 approval 关联。

### ClassName: `ToolCallDecision`

Module:
- `agent-runtime.loop`

Type:
- Value Object

Purpose:
- 表示策略层对 ToolCallRequest 的判断结果。

Responsibilities:
- 明确允许、拒绝或需要审批。
- 输出规范化后的工具名和参数。

NonResponsibilities:
- 不执行工具。
- 不做审批交互。

Inputs:
- `ToolLoopPolicy`

Outputs:
- `ToolExecutor` 或 approval 子系统

Dependencies:
- 无

State:
- 不可变。

RecommendedFields:
- `boolean allowed`
- `boolean approvalRequired`
- `String reason`
- `String normalizedToolName`
- `String normalizedArgumentsJson`

KeyMethods:
- record 默认访问器即可。

Constraints:
- `allowed = false` 时必须有 `reason`。
- `approvalRequired = true` 时不能直接执行工具。

FailureModes:
- 无自身失败逻辑。

Persistence:
- 应写入 tool audit。

Observability:
- 决策理由必须可被日志记录。

ImplementationNotes:
- 后续可扩展 `riskLevel`、`policyRuleId`。

### ClassName: `ToolCallOutcome`

Module:
- `agent-runtime.loop`

Type:
- Value Object

Purpose:
- 表示一次工具调用的最终执行结果。

Responsibilities:
- 保存请求、决策、执行结果、耗时和失败信息。

NonResponsibilities:
- 不负责再次回填 prompt。

Inputs:
- `ToolExecutor`

Outputs:
- `ToolResultAppender`
- 审计与 transcript

Dependencies:
- `ToolCallRequest`
- `ToolCallDecision`
- `ToolResult`

State:
- 不可变。

RecommendedFields:
- `ToolCallRequest request`
- `ToolCallDecision decision`
- `boolean executed`
- `boolean success`
- `ToolResult toolResult`
- `String errorMessage`
- `long durationMillis`

KeyMethods:
- record 默认访问器即可。

Constraints:
- `executed = false` 时 `toolResult` 可为空。
- `success = false` 时应提供错误信息。

FailureModes:
- 无自身失败逻辑。

Persistence:
- 应写入 tool_audit_log。

Observability:
- 应输出执行耗时。

ImplementationNotes:
- 失败时也要保留 request 和 decision。

---

## 7. Orchestrator 与执行器

### ClassName: `ToolLoopOrchestrator`

Module:
- `agent-runtime.loop`

Type:
- Interface

Purpose:
- 编排一次完整 Tool Loop。

Responsibilities:
- 初始化与推进 `ToolLoopContext`。
- 调用模型轮次执行器。
- 检测工具调用请求。
- 触发策略判断。
- 执行工具。
- 回填工具结果。
- 控制迭代次数。
- 生成最终 `ToolLoopResult`。

NonResponsibilities:
- 不关心 HTTP / SSE。
- 不直接依赖具体 Tool 实现类。

Inputs:
- `ToolLoopContext`

Outputs:
- `ToolLoopResult`

Dependencies:
- `ModelTurnExecutor`
- `ToolCallDetector`
- `ToolLoopPolicy`
- `ToolExecutor`
- `ToolResultAppender`

State:
- 无长期状态。

KeyMethods:
- `ToolLoopResult execute(ToolLoopContext context)`

Constraints:
- 必须串行执行。
- 必须检查最大迭代次数。
- 每次工具调用必须先过策略检查。

FailureModes:
- 达到最大迭代次数。
- Tool Call 被拒绝。
- Tool 执行异常。
- 模型恢复失败。

Persistence:
- 不直接持久化。

Observability:
- 应在关键阶段输出 debug/info 日志。

ImplementationNotes:
- 推荐由 `DefaultToolLoopOrchestrator` 实现。

### ClassName: `DefaultToolLoopOrchestrator`

Module:
- `agent-runtime.loop`

Type:
- Class

Purpose:
- ToolLoopOrchestrator 的默认实现。

Responsibilities:
- 维护串行 while-loop 或有限状态循环。
- 调用各子组件形成完整闭环。
- 记录每轮 `ToolLoopIteration`。

NonResponsibilities:
- 不做原始模型协议解析。
- 不直接从 Spring 容器里查 Tool Bean。

Inputs:
- `ToolLoopContext`

Outputs:
- `ToolLoopResult`

Dependencies:
- `ModelTurnExecutor`
- `ToolCallDetector`
- `ToolLoopPolicy`
- `ToolExecutor`
- `ToolResultAppender`

State:
- 无长期状态。

KeyMethods:
- `execute(ToolLoopContext context)`
- `shouldContinue(ToolLoopContext context)`
- `validateIterationLimit(ToolLoopContext context)`

Constraints:
- 循环出口必须明确。
- 不允许无条件无限重试。

FailureModes:
- 迭代次数超限。
- 非法状态。

Persistence:
- 不直接持久化。

Observability:
- 每轮开始、决策、执行、完成都应打日志。

ImplementationNotes:
- 首版建议同步实现；后续再改成响应式。

### ClassName: `ModelTurnExecutor`

Module:
- `agent-runtime.loop`

Type:
- Interface

Purpose:
- 执行一次“向模型发请求并收集响应”的动作。

Responsibilities:
- 接收当前 Context。
- 调用 `LlmProvider`。
- 聚合本轮模型输出。
- 返回 `ModelTurnResult`。

NonResponsibilities:
- 不检测 Tool Call。
- 不执行工具。

Inputs:
- `ToolLoopContext`

Outputs:
- `ModelTurnResult`

Dependencies:
- `LlmProvider`

State:
- 无长期状态。

KeyMethods:
- `ModelTurnResult executeTurn(ToolLoopContext context)`

Constraints:
- 输出必须是本轮完整结果。

FailureModes:
- Provider 超时。
- Provider 连接错误。
- 响应格式异常。

Persistence:
- 无

Observability:
- 应记录 provider 调用耗时。

ImplementationNotes:
- 建议把模型层原始事件也保存在 `ModelTurnResult` 中。

### ClassName: `ModelTurnResult`

Module:
- `agent-runtime.loop`

Type:
- Value Object

Purpose:
- 表示一次模型轮次的结果。

Responsibilities:
- 保存完整文本、原始事件、结束标记和结束原因。

NonResponsibilities:
- 不检测 Tool Call。

Inputs:
- `ModelTurnExecutor`

Outputs:
- `ToolCallDetector`
- `ToolLoopOrchestrator`

Dependencies:
- `LlmStreamEvent`

State:
- 不可变。

RecommendedFields:
- `String fullText`
- `List<LlmStreamEvent> rawEvents`
- `boolean finish`
- `String finishReason`

KeyMethods:
- record 默认访问器即可。

Constraints:
- `fullText` 必须是本轮可解析文本。

FailureModes:
- 无自身失败逻辑。

Persistence:
- 可用于 debug snapshot。

Observability:
- 适合在 debug 级别输出摘要。

ImplementationNotes:
- 若模型原生支持 structure tool_calls，可在这里扩展字段。

### ClassName: `ToolCallDetector`

Module:
- `agent-runtime.loop`

Type:
- Interface

Purpose:
- 从模型轮次结果中识别工具调用请求。

Responsibilities:
- 检查模型输出。
- 若存在工具调用，则构造 `ToolCallRequest`。
- 若不存在，则返回 empty。

NonResponsibilities:
- 不做策略判断。
- 不执行工具。

Inputs:
- `ModelTurnResult`

Outputs:
- `Optional<ToolCallRequest>`

Dependencies:
- 无

State:
- 无状态。

KeyMethods:
- `Optional<ToolCallRequest> detect(ModelTurnResult result)`

Constraints:
- 必须兼容文本协议与后续结构化协议。

FailureModes:
- JSON 解析失败。
- 工具名提取失败。

Persistence:
- 无

Observability:
- 检测失败时应输出调试日志。

ImplementationNotes:
- 不要把检测逻辑写进 `LlmProvider`。

### ClassName: `ToolLoopPolicy`

Module:
- `agent-runtime.loop`

Type:
- Interface

Purpose:
- 判断 Tool Call 是否允许进入执行阶段。

Responsibilities:
- 校验 `toolName` 是否存在。
- 结合 agent policy、risk level、arguments、iteration count 给出决策。
- 输出 `ToolCallDecision`。

NonResponsibilities:
- 不执行工具。
- 不负责审批交互实现。

Inputs:
- `ToolLoopContext`
- `ToolCallRequest`

Outputs:
- `ToolCallDecision`

Dependencies:
- `ToolPolicyService`
- 后续可依赖 approval resolver

State:
- 无长期状态。

KeyMethods:
- `ToolCallDecision evaluate(ToolLoopContext context, ToolCallRequest request)`

Constraints:
- 对所有请求必须返回明确决策，不允许返回 null。

FailureModes:
- policy 配置缺失。
- 工具未注册。

Persistence:
- 无

Observability:
- 决策理由必须能进日志。

ImplementationNotes:
- 不要只返回 boolean，必须带 reason。

### ClassName: `ToolExecutor`

Module:
- `agent-runtime.loop`

Type:
- Interface

Purpose:
- 统一执行工具调用。

Responsibilities:
- 根据工具名查找工具。
- 调用工具实现。
- 捕获异常并转换为 `ToolCallOutcome`。

NonResponsibilities:
- 不做策略检查。
- 不做 prompt 回填。

Inputs:
- `ToolLoopContext`
- `ToolCallRequest`
- `ToolCallDecision`

Outputs:
- `ToolCallOutcome`

Dependencies:
- `ToolRegistry`

State:
- 无长期状态。

KeyMethods:
- `ToolCallOutcome execute(ToolLoopContext context, ToolCallRequest request, ToolCallDecision decision)`

Constraints:
- 工具异常必须被结构化包装。

FailureModes:
- 工具不存在。
- 工具执行抛异常。
- 参数非法。

Persistence:
- 无直接持久化。

Observability:
- 应记录工具执行耗时。

ImplementationNotes:
- 后续可区分本地执行与远程 Worker 执行。

### ClassName: `ToolResultAppender`

Module:
- `agent-runtime.loop`

Type:
- Interface

Purpose:
- 将工具结果回填到下一轮模型上下文。

Responsibilities:
- 将 `ToolCallOutcome` 转换为稳定的 prompt 片段。
- 更新 `ToolLoopContext`。

NonResponsibilities:
- 不调用模型。
- 不执行工具。

Inputs:
- `ToolLoopContext`
- `ToolCallOutcome`

Outputs:
- 更新后的 Context

Dependencies:
- 无

State:
- 无长期状态。

KeyMethods:
- `void append(ToolLoopContext context, ToolCallOutcome outcome)`

Constraints:
- 回填内容必须简洁、稳定、结构化。
- 不得把冗长原始日志全部塞进 prompt。

FailureModes:
- 结果格式不合法。

Persistence:
- 无

Observability:
- 回填摘要可写 debug 日志。

ImplementationNotes:
- 推荐回填字段：tool name、summary、核心 data 摘要、error 摘要。

---

## 8. Tool Registry

### ClassName: `ToolRegistry`

Module:
- `agent-tool.registry`

Type:
- Interface

Purpose:
- 为 ToolExecutor 提供按名称查找工具的能力。

Responsibilities:
- 根据 `toolName` 返回工具实例。
- 支持列出所有已注册工具。

NonResponsibilities:
- 不执行工具。
- 不做权限判断。

Inputs:
- `String toolName`

Outputs:
- `Optional<AgentTool>` 或明确异常

Dependencies:
- `AgentTool`

State:
- 无长期状态。

KeyMethods:
- `Optional<AgentTool> find(String toolName)`
- `List<AgentTool> listAll()`

Constraints:
- 工具名称必须唯一。

FailureModes:
- 查找不到工具。

Persistence:
- 无

Observability:
- 启动时可输出已注册工具清单。

ImplementationNotes:
- 默认实现可在 Spring 容器启动时扫描 `AgentTool` Bean。

### ClassName: `DefaultToolRegistry`

Module:
- `agent-tool.registry`

Type:
- Class

Purpose:
- ToolRegistry 的默认 Spring 实现。

Responsibilities:
- 收集所有 `AgentTool` Bean。
- 构建 `toolName -> AgentTool` 映射。

NonResponsibilities:
- 不做工具权限控制。

Inputs:
- Spring 注入的 `List<AgentTool>`

Outputs:
- `ToolRegistry` 查询结果

Dependencies:
- `AgentTool`

State:
- 内存中的工具映射表。

KeyMethods:
- `find(String toolName)`
- `listAll()`

Constraints:
- 不允许重复名称。

FailureModes:
- 启动时发现重复工具名。

Persistence:
- 无

Observability:
- 启动时记录工具数量和名称。

ImplementationNotes:
- 启动阶段应 fail-fast 处理重复工具名。

---

## 9. 异常类

### ClassName: `ToolLoopException`

Module:
- `agent-runtime.loop`

Type:
- Base Runtime Exception

Purpose:
- Tool Loop 子系统基础异常类型。

Responsibilities:
- 统一承载 tool loop 相关异常。

ImplementationNotes:
- 后续子类全部继承它。

### ClassName: `MaxToolIterationExceededException`

Purpose:
- 表示超出最大工具迭代次数。

Responsibilities:
- 提供当前次数与最大限制信息。

### ClassName: `ToolCallRejectedException`

Purpose:
- 表示 Tool Call 被策略拒绝。

Responsibilities:
- 承载拒绝原因与请求信息。

### ClassName: `ToolExecutionFailedException`

Purpose:
- 表示工具执行失败。

Responsibilities:
- 承载工具名、请求参数摘要和原始异常。

---

## 10. 推荐状态推进顺序

首版 `DefaultToolLoopOrchestrator` 推荐流程：

1. 创建 `ToolLoopContext`
2. state = `INITIALIZING`
3. 校验初始上下文
4. 进入循环
5. state = `MODEL_REQUESTING`
6. 调 `ModelTurnExecutor.executeTurn()`
7. state = `MODEL_STREAMING`
8. 用 `ToolCallDetector.detect()` 检查工具调用
9. 若无工具调用：
   - 聚合最终 assistant 文本
   - state = `COMPLETED`
   - 返回 `ToolLoopResult`
10. 若检测到工具调用：
   - state = `TOOL_CALL_DETECTED`
   - state = `TOOL_POLICY_CHECKING`
   - 调 `ToolLoopPolicy.evaluate()`
11. 若拒绝：
   - 抛 `ToolCallRejectedException` 或组装失败结果
12. 若需要审批：
   - state = `WAITING_APPROVAL`
   - 结束本轮并返回等待状态
13. 若允许执行：
   - state = `TOOL_EXECUTING`
   - 调 `ToolExecutor.execute()`
14. 执行完成后：
   - state = `TOOL_RESULT_APPENDING`
   - 调 `ToolResultAppender.append()`
15. 迭代次数加一
16. state = `MODEL_RESUMING`
17. 进入下一轮
18. 超过最大次数则失败

---

## 11. 给 Codex 的生成顺序建议

建议按顺序生成：

### Step 1
- `ToolLoopState`
- `ToolLoopContext`
- `ToolLoopIteration`
- `ToolLoopResult`
- `ToolCallRequest`
- `ToolCallDecision`
- `ToolCallOutcome`

### Step 2
- `ToolLoopOrchestrator`
- `DefaultToolLoopOrchestrator`
- `ModelTurnExecutor`
- `ModelTurnResult`
- `ToolCallDetector`
- `ToolLoopPolicy`
- `ToolExecutor`
- `ToolResultAppender`

### Step 3
- `ToolRegistry`
- `DefaultToolRegistry`

### Step 4
- `ToolLoopException`
- `MaxToolIterationExceededException`
- `ToolCallRejectedException`
- `ToolExecutionFailedException`

---

## 12. 给 Codex 的全局约束

```text
GlobalConstraints:
  - Use Java 21.
  - Keep Tool Loop execution serial within a single run.
  - Do not implement parallel tool calls.
  - Do not put business logic into DTOs or entities.
  - Keep ToolLoopContext mutable and other value objects immutable.
  - Tool loop must have an explicit max iteration limit.
  - Every tool call must go through policy evaluation before execution.
  - Tool execution failures must be converted into structured outcomes.
  - Tool result appending must produce stable and concise prompt fragments.
  - ToolRegistry must fail fast on duplicate tool names.
```

---

## 13. 这份文件的用途

这份文件的用途非常明确：

- 给 Codex 分批生成 Tool Loop 子系统
- 控制各类边界，避免职责混乱
- 保证 Tool Loop 先以串行、可控、可审计的方式落地

下一份独立文件，最适合继续写的是：

- `Tool Loop 审计与 Transcript 设计说明`
- 或 `Tool Loop 与 LLM Provider 的协议映射说明`

