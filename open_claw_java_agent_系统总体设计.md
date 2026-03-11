# OpenClaw-like Java Agent 系统总体设计

## 1. 文档目标

本文档定义一个基于 Java 21 + Spring Boot 3 + Spring WebFlux 的 OpenClaw-like Agent 系统总体设计。目标不是实现一个普通聊天应用，而是实现一个常驻运行、支持多入口、多 Agent、多工具、多会话、带长期记忆与安全隔离能力的 Agent 控制平面。

本文档聚焦以下内容：

- 系统目标与边界
- 总体架构与核心模块
- Runtime 执行逻辑
- Session / Memory / Tool / Workspace 设计
- 安全与审计设计
- 数据存储设计
- API 与事件模型
- 部署拓扑与演进路线

---

## 2. 系统定位

### 2.1 系统定义

该系统定义为：

**一个面向多入口、多 Agent、多工具执行场景的本地优先 / 服务器优先 AI Agent 控制平面。**

其职责包括：

1. 接收来自 Web、CLI、Webhook、Telegram、Slack 等通道的消息。
2. 根据路由规则将消息分配给目标 Agent。
3. 装配 Prompt、记忆、工具定义、运行上下文。
4. 驱动 LLM 执行 Agent Loop。
5. 处理工具调用、结果回填、流式输出。
6. 维护 Session、Run、Memory、Artifacts。
7. 对高风险能力实施权限控制、审批与审计。

### 2.2 不属于首期范围的能力

以下能力不列为 MVP 首期必须项：

- 完整多租户 SaaS 计费体系
- 复杂 BI 报表
- 大规模分布式任务编排平台
- 自研向量数据库
- 完整低代码 UI 设计器

---

## 3. 设计原则

### 3.1 模块化单体优先

首期采用模块化单体架构，以降低系统复杂度，保证 Runtime、Session、Tool、安全控制位于同一进程内可直接协作。高风险执行能力通过外部 Worker 隔离。

### 3.2 Session 串行执行

同一 Session 任一时刻只允许一个 Run 执行，避免：

- Prompt 上下文错位
- 工具竞争
- 会话日志乱序
- 记忆写入冲突

### 3.3 Gateway 与 Runtime 分离

Gateway 负责接入、认证、会话入口、事件推送；Runtime 负责 Prompt 装配、模型对话、工具循环、状态推进。

### 3.4 工具执行与模型调用解耦

工具引擎必须独立于模型调用层，以支持：

- 工具权限策略
- 审批机制
- 审计日志
- 多种工具执行后端

### 3.5 双状态存储

系统状态同时存在于：

- **数据库**：结构化、可查询、可审计
- **Workspace 文件系统**：Agent 配置、知识文件、可读记忆

### 3.6 本地优先，可扩展到远程

系统默认适配本地部署或单机服务部署，同时支持未来扩展到多节点模式。

---

## 4. 总体架构

### 4.1 逻辑分层

```text
Client / Channels
  ├─ Web Console
  ├─ CLI
  ├─ Telegram / Slack / Webhook
  └─ Future Node Clients

Agent Gateway
  ├─ Auth / Session Bootstrap
  ├─ Channel Adapter
  ├─ Event Stream (SSE / WebSocket)
  └─ Agent Routing

Agent Runtime
  ├─ Prompt Assembler
  ├─ Model Adapter
  ├─ Agent Loop State Machine
  └─ Tool Invocation Coordinator

Execution Engines
  ├─ Tool Engine
  ├─ Memory Engine
  ├─ Workspace Engine
  └─ Artifact Engine

Persistence Layer
  ├─ PostgreSQL
  ├─ Redis
  ├─ pgvector
  └─ Local FS / MinIO
```

### 4.2 部署视图

```text
┌────────────────────────────────────────────┐
│                Main JVM App                │
│ Spring Boot + WebFlux                      │
│                                            │
│  Gateway / Router / Runtime / Session      │
│  Memory / Tool Policy / Audit / API        │
└──────────────────────┬─────────────────────┘
                       │
         ┌─────────────┼─────────────┐
         │             │             │
         ▼             ▼             ▼
   PostgreSQL        Redis       Workspace FS
         │
         ▼
      pgvector

Optional External Workers
  - Browser Worker
  - Code Runner Worker
  - Sandboxed Shell Worker
```

---

## 5. 核心业务对象

### 5.1 Agent

Agent 是独立的智能体定义，包含：

- 标识与显示名称
- System Prompt
- Persona / Soul
- 允许使用的工具集合
- 默认模型配置
- Workspace 路径
- 安全策略

### 5.2 Session

Session 表示与某个 Agent 的连续对话上下文。其职责：

- 绑定一个 Agent
- 维护消息序列
- 管理执行锁
- 管理队列中的 follow-up 消息

### 5.3 Run

Run 表示 Session 中一次实际执行过程。一次 Run 从接收到新消息开始，到最终回复完成或失败结束。

### 5.4 Tool

Tool 是可由 Agent 调用的结构化能力，必须具有：

- 名称
- 参数 Schema
- 执行策略
- 权限级别
- 审计记录能力

### 5.5 Workspace

Workspace 是 Agent 的工作目录，包含：

- AGENT.md
- SOUL.md
- TOOLS.yaml
- MEMORY.md
- knowledge/
- artifacts/
- temp/

### 5.6 Memory

Memory 分为：

- Session 短期上下文
- 长期摘要记忆
- 可检索知识记忆
- 文件式显式记忆

---

## 6. 核心模块设计

### 6.1 agent-gateway

**职责**

- 接收外部消息
- 认证调用方
- 创建或查找 Session
- 将消息交给 Router
- 将执行事件回推给前端 / 渠道

**接口类型**

- REST：发送消息、查询会话、获取 Agent 配置
- SSE：流式输出 Run 事件
- WebSocket：双向事件通道
- Webhook：接收第三方平台消息

**关键要求**

- 统一事件模型
- 不承载复杂业务判断
- 对外只暴露 Gateway 协议，不暴露内部 Runtime 细节

### 6.2 agent-router

**职责**

- 根据 channel / user / session / route binding 选择目标 Agent
- 支持默认 Agent
- 支持规则优先级

**路由条件示例**

- channel = web && userId = admin -> dev-agent
- channel = telegram && chatId = family-group -> family-agent
- path = /api/ops/* -> ops-agent

### 6.3 agent-runtime

**职责**

- 加载 Agent 定义
- 组装 Prompt
- 执行 Agent Loop
- 处理 Tool Call
- 处理输出流
- 记录 Run 生命周期

**核心要求**

- Runtime 纯粹聚焦执行过程
- 与 Gateway 解耦
- 与具体模型供应商解耦

### 6.4 prompt-assembler

**职责**

将以下内容组装为最终 PromptContext：

- System Prompt
- Soul Prompt
- 工具定义
- 最近消息
- 检索到的 Memory
- 当前时间 / 会话元信息
- Workspace 明示文件内容

### 6.5 model-adapter

**职责**

- 统一封装 OpenAI / Anthropic / Gemini / Ollama / vLLM 等模型服务
- 向 Runtime 返回统一流式事件模型

**标准输出事件**

- token
- tool_call_request
- finish
- usage
- error

### 6.6 tool-engine

**职责**

- 工具注册
- 参数校验
- 权限检查
- 审批请求
- 工具执行
- 工具审计

**工具分级**

- Safe
- Guarded
- Privileged
- Human Approval Required

### 6.7 session-engine

**职责**

- 创建 / 查询 Session
- Session 锁管理
- 消息入队
- Run 生命周期维护
- Follow-up 处理

### 6.8 memory-engine

**职责**

- 检索相关记忆
- 写入记忆候选
- 做摘要 / 压缩
- 管理向量索引

### 6.9 workspace-engine

**职责**

- 管理 Agent 工作目录
- 读写 Agent 配置文件
- 限制工具文件访问边界
- 管理 artifacts

### 6.10 policy-security-engine

**职责**

- 路径白名单与黑名单
- Shell 命令控制
- 出网策略
- 审批策略
- 审计日志落库

---

## 7. Agent Loop 设计

### 7.1 状态机

```text
RECEIVED
 -> CONTEXT_BUILT
 -> MODEL_RUNNING
 -> TOOL_REQUESTED
 -> TOOL_EXECUTING
 -> TOOL_RESULT_APPENDED
 -> MODEL_RESUMED
 -> COMPLETED
 or FAILED
 or WAITING_APPROVAL
```

### 7.2 单次 Run 流程

1. 接收用户消息
2. 获取 Session 锁
3. 读取 Agent 配置与 Workspace 文件
4. 检索近期消息与相关记忆
5. 组装 PromptContext
6. 调用模型
7. 若模型请求工具，则校验与执行工具
8. 将工具结果写回上下文
9. 继续模型执行，直至完成
10. 记录 transcript / usage / audit
11. 释放 Session 锁

### 7.3 关键原则

- 同一 Session 串行执行
- Tool 调用必须可审计
- Tool Result 回填必须结构化
- 每次 Run 有完整生命周期记录

---

## 8. Session 设计

### 8.1 目标

Session 不是简单聊天窗口，而是 Runtime 的隔离边界。它承载：

- 上下文序列
- 执行锁
- follow-up 队列
- Memory flush 时机
- Artifacts 归属

### 8.2 Session Queue 模式

可支持三种模式：

- steer：强制插入当前轮
- followup：当前轮完成后执行
- collect：多条消息合并为下一轮输入

MVP 首期可先支持 followup。

---

## 9. Memory 设计

### 9.1 Memory 分层

#### A. 短期消息记忆
直接来自最近 N 条 Session Message。

#### B. 长期摘要记忆
由系统根据消息或人工维护形成 Memory Note。

#### C. 检索式知识记忆
来自 workspace 文档、上传文件、外部知识源。

#### D. 文件式显式记忆
如 MEMORY.md，适合稳定、重要、可读性强的内容。

### 9.2 技术实现

- PostgreSQL：结构化记忆元数据
- pgvector：Embedding 检索
- Workspace 文件：显式长期记忆

### 9.3 Memory Flush

当 Session 达到一定消息长度或摘要阈值时，将候选内容写入长期记忆。

---

## 10. Workspace 设计

### 10.1 目录结构建议

```text
/workspaces/
  dev-agent/
    AGENT.md
    SOUL.md
    TOOLS.yaml
    MEMORY.md
    knowledge/
    artifacts/
    temp/
  family-agent/
    ...
```

### 10.2 约束

- 工具只能访问当前 Agent 允许的路径
- artifacts 必须可追溯到 Session / Run
- 允许未来支持 Git 管理 Workspace

---

## 11. Tool 设计

### 11.1 Tool 接口要求

每个 Tool 必须定义：

- name
- description
- json schema
- risk level
- execute()

### 11.2 推荐首批工具

- file.read
- file.write
- file.list
- shell.exec
- http.fetch
- search.memory
- search.workspace
- artifact.save
- browser.open
- browser.extract
- db.query

### 11.3 Tool Result 格式

统一返回：

```json
{
  "ok": true,
  "summary": "Read 3 files",
  "data": {},
  "artifacts": [],
  "error": null
}
```

---

## 12. 安全设计

### 12.1 安全目标

防止模型调用工具造成：

- 非授权文件访问
- 危险命令执行
- 数据泄露
- 未审批高风险操作

### 12.2 控制策略

- Tool 白名单
- 路径访问白名单
- Shell 命令策略
- HTTP 出网目标限制
- 审批机制
- 审计日志

### 12.3 审批流程

对 Privileged / High Risk 操作：

1. 生成审批请求
2. Run 进入 WAITING_APPROVAL
3. 用户或管理员批准 / 拒绝
4. Runtime 恢复或终止

---

## 13. 审计设计

必须记录：

- 谁触发了 Run
- Run 使用了哪个 Agent
- 调用了什么 Tool
- Tool 参数与结果
- 是否经过审批
- 最终状态
- 错误原因

审计日志至少应支持：

- 按 Session 查询
- 按 Tool 查询
- 按 Agent 查询
- 按时间范围查询

---

## 14. 数据存储设计

### 14.1 PostgreSQL 表建议

- agent_definition
- session
- session_message
- run_record
- memory_note
- memory_embedding
- tool_audit_log
- artifact_file
- approval_request

### 14.2 Redis 用途

- Session 分布式锁
- 短期队列
- 事件中转
- 限流

### 14.3 文件存储

- 本地文件系统或 MinIO
- 用于 artifacts、上传文件、导出文件

---

## 15. API 设计

### 15.1 REST API

- POST /api/chat/send
- GET /api/chat/stream
- GET /api/sessions/{id}
- GET /api/agents
- POST /api/approvals/{id}/approve
- POST /api/approvals/{id}/reject

### 15.2 事件流模型

事件类型建议包括：

- run.started
- run.status
- token.delta
- tool.requested
- tool.started
- tool.completed
- approval.required
- run.completed
- run.failed

### 15.3 WebSocket

适合：

- 控制台实时控制
- Agent 状态订阅
- 多事件统一推送

---

## 16. 部署建议

### 16.1 MVP 单机部署

- 1 个 Spring Boot 主进程
- PostgreSQL
- Redis
- 本地 Workspace + 本地文件系统

### 16.2 增强部署

- 主进程多实例
- Redis 分布式锁
- MinIO 文件存储
- 外部 Worker 池

### 16.3 Worker 外置建议

以下能力建议单独 Worker：

- 浏览器自动化
- 高风险 shell
- 代码执行沙箱
- 图像 / PDF / Office 文档处理

---

## 17. 演进路线

### Phase 1

- 单 Agent
- Web 聊天
- 单一模型接入
- SSE 流式输出
- 基础工具 3 个

### Phase 2

- Session 持久化
- Run 生命周期
- Memory Summary
- Redis 锁

### Phase 3

- 多 Agent
- Workspace 配置文件
- 路由规则
- 审计日志

### Phase 4

- 审批流
- 高风险工具隔离
- 外部 Worker

### Phase 5

- Telegram / Slack / Webhook
- 多客户端控制台
- Node 能力接入

---

## 18. Java 技术栈建议

- Java 21
- Spring Boot 3.x
- Spring WebFlux
- Spring Security
- Spring Data JPA 或 MyBatis
- PostgreSQL
- Redis
- pgvector
- Flyway
- Reactor
- Jackson
- Playwright（浏览器自动化）

### 18.1 不建议首期过度引入

- Kafka
- 复杂服务网格
- 过度微服务拆分
- 过重 DDD 基础设施

---

## 19. 关键接口草图

```java
public interface AgentRunner {
    Flux<AgentEvent> run(RunRequest request);
}
```

```java
public interface LlmProvider {
    Flux<LlmStreamEvent> chatStream(LlmChatRequest request);
}
```

```java
public interface AgentTool {
    String name();
    ToolSchema schema();
    ToolResult execute(ToolInvocation invocation);
}
```

```java
public interface PromptAssembler {
    PromptContext assemble(RunRequest request);
}
```

```java
public interface SessionLockManager {
    boolean tryLock(String sessionId, Duration timeout);
    void unlock(String sessionId);
}
```

---

## 20. 总结

该系统的本质不是聊天页面，而是一个常驻运行的 Agent 控制平面。其核心架构必须围绕以下几点建立：

- 统一 Gateway 接入
- 独立 Runtime 执行内核
- 独立 Tool Engine
- Session 串行化执行
- Memory 分层设计
- Workspace 文件化配置
- 安全隔离、审批、审计
- 模块化单体优先，逐步外置高风险 Worker

如果后续进入编码阶段，推荐先实现 Phase 1 的可运行闭环：

**Web Chat -> Session -> Runtime -> LLM -> Tool -> SSE Stream -> Persistence**

这将是整个系统最小但正确的架构起点。