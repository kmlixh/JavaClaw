# ai-agent-platform

OpenClaw-like Java Agent 平台。当前仓库已经不再只是 Phase 1 骨架，而是一套可运行的多模块 Java Agent 控制面，包含多 Agent、Tool Loop、审批、数据库持久化、Vue 3 前端控制台、数据库工具、图表渲染和显式上下文引用能力。

## 技术栈

- Java 21
- Spring Boot 3.3
- Spring WebFlux
- Spring Data JPA / PostgreSQL
- Reactor
- Gradle Multi-Module
- Vue 3 + Vite + Vue Router + ECharts

## 当前能力

- 多模块 Gradle 工程
- 多 Agent 路由与 workspace 文件化加载
- Chat + SSE / WebSocket 双通道
- Tool Loop、Tool Policy、Tool Audit、Approval + Resume
- Session / Transcript / Run / Artifact / Memory Note / Approval 持久化
- 数据库持久化的知识库、工具定义、Skill 定义
- Vue 3 前端控制台
- 显式上下文引用：
  - 输入 `{` 弹出选择对话框
  - 可引用知识库 / Skill / 工具定义
  - 可上传文本附件
- 数据库工具：
  - `db.query`
  - `db.execute`
  - `table.render`
  - `chart.echarts`
- 前端内联渲染：
  - 表格结果
  - ECharts 图表结果

## 模块说明

- `agent-api`：HTTP / 前端 DTO
- `agent-domain`：领域对象
- `agent-runtime`：Tool Loop、运行时边界、查询服务
- `agent-infra`：JPA、LLM provider、tool 实现、workspace 读取
- `agent-web`：REST / WebSocket 控制器
- `agent-app`：Spring Boot 启动模块
- `web-console`：Vue 3 前端控制台

## 运行时能力

### Agent / Workspace

默认 workspace：

- `workspaces/dev-agent`
- `workspaces/ops-agent`

workspace 中可包含：

- `AGENT.md`
- `SOUL.md`
- `MEMORY.md`
- `TOOLS.yaml`
- `knowledge/*`

数据库中也可维护：

- `knowledge_entry`
- `tool_definition`
- `skill_definition`

### 当前内置工具

- `echo`
- `workspace.path`
- `file.list`
- `file.read`
- `file.write`
- `artifact.save`
- `http.fetch`
- `shell.exec`
- `db.query`
- `db.execute`
- `table.render`
- `chart.echarts`

其中：

- `db.query` 支持显式传数据库连接信息
- `db.execute` 支持增删改和 DDL，默认应审批
- `table.render` 用于把结构化结果渲染成前端表格块
- `chart.echarts` 用于把聚合结果渲染成 ECharts 图表

## 前端控制台

前端路由：

- `/chat`
- `/chat/:sessionId`
- `/search`
- `/approvals`
- `/memory`
- `/knowledge`
- `/tools`
- `/skills`

当前前端特性：

- 左侧最近会话 + 新建会话
- 聊天主窗口
- WebSocket 默认通道
- 顶部紧凑配置栏
- 审批内联显示
- 知识库 / 工具定义 / Skills 管理页
- 图表卡片支持本地切换：
  - 柱状图
  - 折线图
  - 饼图

## LLM

默认 profile 为 `postgres`。

当前支持：

- 数据库中的 `llm_provider_config`
- OpenAI-compatible provider
- SiliconFlow `Pro/zai-org/GLM-4.7`

PostgreSQL profile 配置示例见：

- `agent-app/src/main/resources/application-postgres.yml`

## PostgreSQL 启动

```powershell
$env:DB_HOST="10.173.108.120"
$env:DB_PORT="5433"
$env:DB_NAME="java_claw"
$env:DB_USER="postgres"
$env:DB_PASSWORD="..."
.\gradlew.bat :agent-app:bootRun --args='--spring.profiles.active=postgres'
```

如果只是在 IDEA 中直接运行 `AgentApplication`，默认 profile 也会落到 `postgres`。

## 前端启动

```powershell
cd web-console
npm install
npm run dev
```

## 显式上下文引用

会话输入框支持：

- 输入 `{` 打开引用选择对话框
- 选择知识库 / Skill / 工具定义后，在输入框内插入高亮 token
- 上传文本文件作为当前消息附件

发送时会把：

- 显式选择的知识库
- 显式选择的 Skill
- 显式选择的工具定义
- 上传的文本附件

作为本次消息上下文一并传给后端 prompt assembler。

## 查询与图表

支持这类请求：

- 指定数据库连接信息
- 查询某个表
- `group by` 聚合统计
- 渲染表格
- 渲染图表

推荐链路：

1. `db.query`
2. `table.render` 或 `chart.echarts`
3. assistant 只输出简短总结

当前系统已对这条链做了展示收口：

- 如果已有表格 / 图表渲染块，不再重复显示原始 `db.query`
- assistant 不再重复输出大段 markdown 表格

## 测试

### 编译 / 构建

```powershell
.\gradlew.bat compileJava
.\gradlew.bat build
```

### 真实集成测试

```powershell
.\gradlew.bat :agent-app:postgresFlowTest
.\gradlew.bat :agent-app:siliconflowIntegrationTest
.\gradlew.bat :agent-app:realFlowTest
```

### JUnit 集成测试

```powershell
.\gradlew.bat :agent-app:test --tests com.janyee.agent.app.PostgresFlowIntegrationTest
.\gradlew.bat :agent-app:test --tests com.janyee.agent.app.SiliconFlowGlm47IntegrationTest
```

其中已覆盖：

- PostgreSQL profile 下真实 HTTP 流程
- 数据库中的 SiliconFlow GLM-4.7 配置加载
- 中文 workspace 工具调用
- 显式知识引用
- 分组统计并生成图表的专项路径

## 说明

- 当前本地环境里可能存在：
  - Gradle wrapper 锁文件占用
  - Vite / esbuild `spawn EPERM`
- 这类问题属于本机运行环境问题，不是仓库业务逻辑本身
