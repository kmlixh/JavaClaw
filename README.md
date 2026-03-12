# ai-agent-platform

OpenClaw-like Java Agent 平台 Phase 1 工程骨架。

## 技术栈

- Java 21
- Spring Boot 3
- Spring WebFlux
- Reactor
- Gradle Multi-Module

## 当前阶段

- 完成 Gradle 多模块项目结构
- 完成基础领域模型与 Runtime 边界
- 提供最小 Web Chat + SSE 闭环
- 提供默认 PromptAssembler / LlmProvider 占位实现
- 已接入最小 Tool Loop 状态机骨架
- 已支持 `/tool echo ...` 演示工具调用
- 已支持 workspace 文件化加载 `AGENT.md / SOUL.md / MEMORY.md / TOOLS.yaml / knowledge/*`
- 已支持 session / run / tool audit / approval 持久化与查询接口

## 下一步

1. 引入 Session 持久化与 Transcript 落库
2. 引入 Redis Session 锁
3. 接入 OpenAI-compatible Provider
4. 接入 Tool Registry 与审计日志

## 本地运行

启动：

```bash
.\gradlew.bat :agent-app:bootRun
```

普通聊天：

```bash
curl "http://localhost:8080/api/chat/stream?sessionId=s1&message=hello"
```

工具调用演示：

```bash
curl "http://localhost:8080/api/chat/stream?sessionId=s2&message=/tool%20echo%20hello-tool"
```

当使用 stub provider 时，第二个请求会触发 `echo` 工具并返回完整的 Tool Loop 事件流。

## Workspace 约定

默认 agent 工作目录位于 `workspaces/dev-agent/`：

- `AGENT.md`：Agent 说明
- `SOUL.md`：系统行为与人格约束
- `MEMORY.md`：显式长期记忆
- `TOOLS.yaml`：工具白名单/黑名单策略
- `knowledge/*.md`：附加知识文档，会被截断后拼入 prompt
