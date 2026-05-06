# JavaClaw

Java 实现的多 Agent 平台。核心是 **LLM Tool Loop + Skill-driven backend guardrail**：LLM 不是裸跑，所有 plan 推进 / SQL 执行 / artifact 产出都经后端确定性约束，避免 prompt-only 兜底失败。适合企业内部"自然语言问答 + 数据查询 + 报告生成"链路。

---

## 目录

1. [系统架构](#1-系统架构)
2. [核心运行机制](#2-核心运行机制)
3. [数据持久化](#3-数据持久化)
4. [认证 & 授权](#4-认证--授权)
5. [前端](#5-前端)
6. [LLM 集成](#6-llm-集成)
7. [内置工具清单](#7-内置工具清单)
8. [运行 & 测试](#8-运行--测试)
9. [生产部署](#9-生产部署)
10. [模块说明](#10-模块说明)

---

## 1. 系统架构

### 1.1 模块拓扑

```
┌────────────────────────────────────────────────────────────────┐
│  agent-app  (Spring Boot 启动, profile: postgres / prod)       │
└────────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌──────────────┐     ┌──────────────┐     ┌──────────────────┐
│  agent-web   │     │ agent-infra  │     │  agent-runtime   │
│ REST/WS/SSE  │     │  JPA / Tool  │     │ ToolLoop / Plan  │
│ 控制器        │     │  实现 / LLM   │     │ Skill / Guard    │
└──────────────┘     └──────────────┘     └──────────────────┘
        │                     │                     │
        └─────────────────────┼─────────────────────┘
                              ▼
                  ┌────────────────────┐
                  │ agent-domain / api │
                  │ (DTO / 领域对象)    │
                  └────────────────────┘

外置:
- web-console      Vue 3 + Vite 控制台 (主管理 UI)
- xmap-ol-front    GIS 应用 (通过 javaclaw-embed-sdk.js 嵌入 AI 面板)
```

### 1.2 部署拓扑

```
浏览器 / SDK
   │  http(s)://10.173.108.120:8888
   ▼
┌──────────────────────────────────────────────────────────────────┐
│  nginx (8888, 唯一公网入口)                                      │
│  ├─ /                → static  /data/javaClaw/web-console/...     │
│  ├─ /api/*           → 127.0.0.1:8080 (Spring)                    │
│  ├─ /api/chat/stream → SSE, proxy_buffering off, 1h read timeout  │
│  ├─ /oauth/*         → 127.0.0.1:8080 (含 token / authorize)      │
│  ├─ /ws/*            → 127.0.0.1:8080 (WebSocket upgrade)         │
│  └─ /embed/*         → 127.0.0.1:8080 (SDK + iframe HTML)         │
└──────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────────────┐
│  Spring Boot (server.address=127.0.0.1:8080) — 仅 loopback        │
│  - JwtAuthWebFilter        (cookie / Authorization)               │
│  - OauthCorsFilter         (/oauth, /api/bridge, /api/apps)       │
│  - ToolLoopOrchestrator    (Plan-driven loop)                     │
│  - PlanPreflightExecutor   (并行预跑独立 step)                    │
│  - SkillGuard              (whitelistTables + planStepRules)      │
│  - SimpleAgentRunner       (run lifecycle + event emit)           │
└──────────────────────────────────────────────────────────────────┘
        │                                        │
        ▼                                        ▼
┌─────────────────────────┐         ┌──────────────────────────────┐
│ meta DB (java_claw)     │         │ business DB (gis)            │
│ 10.173.108.120:5433     │         │ 10.174.238.4:5432            │
│ - session / run_record  │         │ - ott_temp.yunnan_*g_grid_*  │
│ - session_message       │         │ - xmap.layer_*               │
│ - tool_audit_log        │         │ - xmap_deal.* / xmap_ott.*   │
│ - skill_definition      │         │ (skill 通过 db_datasource    │
│ - llm_provider_config   │         │  表的 jdbc_url 寻址)         │
│ - db_datasource         │         └──────────────────────────────┘
│ - oauth_client / tenant │                        ▲
│ - user_role / scope_*   │                        │
└─────────────────────────┘                        │
        ▲                                          │
        │ DB 凭据写死在 application-prod.yml        │
        │ (LLM / scope 元数据在这)                  │
        │                                          │
        └──────────── DataSourceLocator ───────────┘
                       (按 jdbcUrl 路由到对应 db_datasource)
```

**关键设计**：
- nginx 是**唯一**对外端点，spring 8080 绑 127.0.0.1，从外部 `curl http://10.173.108.120:8080/...` 直接 connection refused
- 两套 PostgreSQL：`java_claw` 是平台元数据库，`gis` 是业务数据库；两者由 `db_datasource` 表登记，工具按 jdbc_url 自动选凭据
- DB 密码硬编码进 `application-prod.yml`（部署 zip 不带 secret 文件），LLM 配置全走 DB（admin UI 维护）

### 1.3 数据流（一次 chat 的全链路）

```
用户消息
  │
  ▼
ChatController (POST /api/chat/stream, SSE)
  │
  ▼
SimpleAgentRunner.executeRun(request)
  │
  ├─ 1. 解析 skill (SkillGuardResolver,合并启用的 strict skills)
  ├─ 2. 注册 SkillGuard (per runId, ToolLoopContext + SkillGuardStore)
  ├─ 3. SkillPlanSeeder 自动生成 plan (skill.planStepIds → PlanStep 列表)
  ├─ 4. PlanPreflightExecutor.runWave0() ── 并行预跑 ────┐
  │      检查用户消息含中文行政区名(市/区/县/...) → return 0
  │      不命中:按 sqlTemplatesNoFilter / sqlTemplatesGeoJson
  │      多 wave 推进,wave N 解锁 wave N+1 的 dependsOn
  │      成功 step 自动 COMPLETED + 挂 CompletedToolSummary
  ▼
ToolLoopOrchestrator.execute(context)
  │
  ▼ 循环:
  ├─ 拼 prompt (含 [Plan] [Next Action] [Plan Discipline] [Completed Queries])
  ├─ 调 LLM (OpenAiCompatibleLlmProvider, SSE 流)
  ├─ 解析 LLM 输出 (token / tool_call_request / finish)
  ├─ 工具调用前:DefaultToolLoopPolicy 校验
  │    - whitelistTables (DatabaseQueryTool 解析 SQL FROM/JOIN)
  │    - dependency barrier (PlanUpdateTool 拒 dependsOn 未满足)
  │    - artifact 类型限制
  ├─ 工具执行 (ToolExecutor)
  ├─ 工具结果回填 (ToolCallOutcome → session_message + tool_audit_log)
  ├─ plan.update COMPLETED 时:PlanStepRuleEvaluator 校验
  │    - minQueries / requiresSuccess
  │    - requiredTables (查到的表必须覆盖)
  │    - acceptance (requireNonZeroData / requiredColumns)
  │    - reportSection (heading + placeholder anchors)
  └─ LLM 想终止时:buildRunEndGateNudge
       检查所有 required step 都 COMPLETED;否则返回 NUDGE 让 LLM 接着跑
  ▼
artifact.markdown 成功 + 所有 step COMPLETED → run 结束
  ▼
最终 assistant 文本 + 渲染产物 → SSE event 流回前端
```

---

## 2. 核心运行机制

### 2.1 Plan + Tool Loop

每个 run 对应一个 `RunPlan`：有序 `PlanStep` 列表，每个 step 有状态机 `PENDING → IN_PROGRESS → COMPLETED / SKIPPED / FAILED`。

LLM 通过两个工具操作 plan：
- `plan.create` — 起手时声明 plan（必须跟 skill 的 `planStepIds` 精确匹配，否则被拒）
- `plan.update` — 推进 step 状态、写 `resultNote`

每条 plan 决策都过 `DefaultToolLoopPolicy.evaluate()`：
- 当前 IN_PROGRESS step 的 `dependsOn` 未全部 COMPLETED → 拒
- artifact.* 工具在不允许的 step 上 → 拒

### 2.2 Skill Guardrail Pipeline

Skill 配置在 `skill_definition.config_json`（JSON），字段：

| 字段 | 含义 | 校验位置 |
|---|---|---|
| `whitelistTables` | 允许查询的 schema.table 集合 | `DatabaseQueryTool` 解析 SQL FROM/JOIN |
| `planStepIds` | plan.create 必须用这些 id | `PlanCreateTool` |
| `planStepRules.<stepId>` | 单 step 完成校验 | `PlanStepRuleEvaluator` (plan.update COMPLETED) |
| `planStepRules.<stepId>.requiredTables` | 该 step 必须在某些表上跑过成功 db.query | 同上 |
| `planStepRules.<stepId>.minQueries` | 最少成功查询次数 | 同上 |
| `planStepRules.<stepId>.acceptance.requiredColumns` | 查询结果必须含某些列 | 同上 |
| `planStepRules.<stepId>.acceptance.requireNonZeroData` | 至少有一行非全 0 数据 | 同上 |
| `planStepRules.<stepId>.dependsOn` | 该 step 依赖的上游 step 列表 | `RunPlan.unmetDependencies()` |
| `planStepRules.<stepId>.reportSection` | artifact step 的目录章节 + 占位符 | `PlanStepRuleEvaluator` 校 markdown anchor |
| `planStepRules.<stepId>.sqlTemplates` | LLM 应当 verbatim 复制的 SQL（含 `{{city}}/{{county}}/{{geometry_json}}` 占位符） | hint，LLM 自行替换 |
| `planStepRules.<stepId>.sqlTemplatesGeoJson` | 几何过滤变体 | preflight 选用 |
| `planStepRules.<stepId>.sqlTemplatesNoFilter` | 无过滤（全量）变体 | preflight fallback |

**核心代码**：

```
agent-runtime/src/main/java/com/janyee/agent/runtime/skill/
  ├─ SkillConstraint.java         # config_json 解析 record
  ├─ PlanStepRule.java            # 单 step 规则
  ├─ SkillGuard.java              # 聚合视图(checkTable / stepRule)
  ├─ SkillGuardStore.java         # Map<runId, SkillGuard>
  ├─ SkillGuardResolver.java      # 合并多个启用的 strict skill
  └─ PlanStepRuleEvaluator.java   # stateless violation checker

agent-infra/src/main/java/com/janyee/agent/infra/
  ├─ skill/SkillPlanSeeder.java                       # 自动生成 plan
  ├─ skill/DatabaseSkillConstraintService.java        # JPA 加载 skill
  └─ tool/                                             # 工具实现 + guard 落地
       ├─ PlanCreateTool / PlanUpdateTool
       ├─ DatabaseQueryTool / DatabaseSchemaInspectTool
       └─ SqlTableExtractor / PartitionInspector
```

### 2.3 Plan Preflight（并行预跑）

在 LLM 进入 tool loop *之前*，`PlanPreflightExecutor.runWave0()` 自动并发跑符合条件的 step：

- **触发时机**：`SimpleAgentRunner` 在 `skillPlanSeeder.seedIfNeeded()` 之后、`toolLoopOrchestrator.execute()` 之前
- **判定 eligible**：step PENDING + `dependsOn` 已 COMPLETED + 模板可用 + toolHint 不是 artifact.*
- **多 wave 推进**：每轮挑 ready step 并发跑（`DEFAULT_CONCURRENCY=3`），成功 step 解锁下游，最多 16 wave
- **变体选择**：用户消息附件含 GeoJSON Polygon → `Variant.GEOJSON`；否则 `Variant.NO_FILTER`
- **跳过 preflight 的场景**：用户消息含中文行政区后缀（市/区/县/州/盟/地区）→ 整段 return 0，让 LLM 按 prompt 决策树走 `sqlTemplates`(admin filter)。否则 preflight 会用 NoFilter 跑成全省，跟用户意图不符
- **失败处理**：失败 step 留 PENDING，LLM 接管按原路径处理；不强制追完

### 2.4 Artifact + run-end gate

LLM 想结束 tool loop（不再调用任何工具，纯输出文本）时，`DefaultToolLoopOrchestrator.buildRunEndGateNudge()` 检查：

1. 所有 required plan step 都 COMPLETED
2. 如果 LLM 文本里出现 `.md / .docx / .pdf / .xlsx` 等"成品已生成"措辞，必须有对应 `artifact.*` 工具调用成功
3. 否则返回 NUDGE 信息塞回 prompt，让 LLM 接着跑

这是兜底"LLM 偷工" 的最后一道闸——在它之前的 PlanStepRule 已经在 plan.update 时拦过一次。

---

## 3. 数据持久化

### 3.1 Meta DB（`java_claw` on 10.173.108.120:5433）

平台元数据 + 运行时状态：

| 表 | 作用 |
|---|---|
| `session` | 会话 |
| `session_message` | 会话消息（user / assistant / tool） |
| `run_record` | 单次 run（status: PENDING / MODEL_RUNNING / FAILED / CANCELLED / SUCCEEDED） |
| `tool_audit_log` | 每次 tool 调用的策略决策 + 执行结果（POLICY_DECISION / EXECUTION_OUTCOME） |
| `approval_request` | 审批请求 |
| `artifact_file` | 生成的 markdown / word / excel / ppt 元数据 |
| `memory_note` | agent 长期记忆 |
| `skill_definition` | skill 定义（含 `config_json`） |
| `tool_definition` | tool 定义 |
| `knowledge_entry` | 知识库 |
| `agent_definition` | agent 定义 |
| `llm_provider_config` | LLM 接入配置 |
| `db_datasource` | 业务库连接（jdbc_url + 凭据） |
| `oauth_client` | OAuth 客户端（client_id / client_secret 哈希 / redirect_uris） |
| `user / tenant / role / user_role / *_scope` | 多租户 RBAC |

### 3.2 Business DB（`gis` on 10.174.238.4:5432）

业务数据。skill 的 `whitelistTables` 都在这里，用 `gisuser` 账号（`postgres` 受 pg_hba 限制）。

skill 通过 `planStepRules.<stepId>.jdbcUrl` 指定数据源；运行时 `DataSourceLocator` 用 jdbc_url 反查 `db_datasource` 表的凭据自动注入。

**分区表自动改写**：白名单中很多是 RANGE 分区父表（`layer_wy_*_cell_info_section_yn` 等）。LLM 写 `FROM xmap.layer_wy_5g_cell_info_section_yn`，`PartitionInspector` 在执行前查 `pg_inherits` 找最新子分区并改写为 `FROM xmap.layer_wy_5g_cell_info_section_yn_partition_2026_04_20`，`metadata.partitionRewrite` 记录映射。

### 3.3 Flyway

Schema 由 `agent-infra/src/main/resources/db/migration/V1..VN__*.sql` 管理。

**注意**：本地开发和生产**共用同一套 meta DB**。修改 skill（tweak `config_json`）通过直接 `UPDATE skill_definition SET config_json = jsonb_set(...)` 进行，**不写 flyway migration**——避免重复执行 / 历史污染。新增表 / 列 / 全新 skill 才用 migration。

---

## 4. 认证 & 授权

### 4.1 三种认证通道

| 通道 | 路径 | 用途 |
|---|---|---|
| **JWT cookie** | `/api/auth/login` 设置，后续请求带 cookie | web-console 登录态 |
| **JWT Authorization header** | `Bearer <token>` | API 直调 |
| **OAuth client_credentials** | POST `/oauth/token` 拿 `access_token` | 第三方页面嵌入 SDK 跨域调用 |

### 4.2 关键过滤器

- `JwtAuthWebFilter`：检查 cookie / Authorization；放行匿名路径（`/api/auth/login`、`/oauth/token`、`/api/apps/*` 应用启用查询）
- `OauthCorsFilter`：给 `/oauth/*`、`/api/bridge/*`、`/api/apps/*` 三组路径做 CORS（当前实现是 echo origin，无白名单——靠 OAuth secret 校验作为真正访问控制；改严白名单的方向：读 `oauth_client.redirect_uris` 解析 origin 集合）

### 4.3 多租户 + RBAC

资源横切字段（贯穿 `skill_definition` / `db_datasource` / `llm_provider_config` / `agent_definition` / `knowledge_entry` 等）：
- `scope_type`: `SYSTEM` / `TENANT` / `USER`
- `scope_tenant_id` / `scope_user_id`：归属租户 / 用户
- `app_id`：归属应用（OAuth client）

`ResourceScopeFilter` 在每次 db.query 时拼额外 `WHERE` 强制 scope 过滤，跨租户数据零泄漏。

### 4.4 嵌入端流程（xmap-ol-front 等）

```
1. 嵌入端 SPA 加载 javaclaw-embed-sdk.js (来自 nginx /embed/)
2. SDK 用 client_credentials grant 直调 /oauth/token 拿 access_token
3. SDK 注入 iframe 指向 nginx / (web-console SPA 的 chat 视图,带 token)
4. iframe 内调 /api/chat/stream (Bearer token) 跑 chat
5. Tool 执行结果可通过 /api/bridge/invoke-result 回写到嵌入页 (绕开 postMessage)
```

---

## 5. 前端

### 5.1 web-console（主管理控制台）

Vue 3 + Vite + Pinia + Element Plus + ECharts。

**路由**：

| 路径 | 用途 |
|---|---|
| `/chat` / `/chat/:sessionId` | 聊天主窗口 |
| `/search` | 跨会话搜索 |
| `/approvals` | 审批中心 |
| `/memory` | Agent 记忆管理 |
| `/knowledge` | 知识库 |
| `/tools` | 工具定义 |
| `/skills` | Skill 定义 |
| `/llms` | LLM 配置 |
| `/datasources` | DB 数据源 |
| `/oauth-clients` | OAuth 应用 |
| `/users` / `/tenants` | 多租户 |

**特性**：
- 输入框 `{` 触发引用选择（知识库 / Skill / 工具定义）
- 文本附件上传作为消息上下文
- 表格 / ECharts / Artifact 内联渲染
- 图表本地切换（柱 / 折 / 饼）
- Plan 面板实时同步（PLAN_UPDATED 事件）

### 5.2 xmap-ol-front（GIS 嵌入端）

OpenLayers + Vue 3 GIS 应用，独立仓库（git submodule，远端 `http://192.168.110.240/it/xmap-ol-front.git`）。通过 `javaclaw-embed-sdk.js` 嵌入 AI 聊天面板。

**配置**：`.env.production` / `.env.development`

```
VITE_AI_BASE_URL=http://10.173.108.120:8888
VITE_AI_CLIENT_ID=xmap
VITE_AI_CLIENT_SECRET=<OAuth secret>
```

⚠ `VITE_AI_CLIENT_SECRET` 会被 vite 编译进 dist JS，浏览器 F12 可见。这是 `client_credentials` grant 在浏览器场景的固有限制。生产部署如果上公网，建议改 `authorization_code + PKCE` 或在 nginx 加 IP 白名单兜底。

---

## 6. LLM 集成

### 6.1 配置驱动

LLM 配置全部走 DB（`llm_provider_config` 表），`application-prod.yml` 不写 `agent.llm.*` 段。Admin UI `/llms` 维护。

字段：

| 列 | 说明 |
|---|---|
| `provider` | `openai-compatible` / `dify` / `jiutian` / `stub` |
| `display_name` / `model` / `model_mapping_json` | 模型选择 |
| `base_url` / `chat_path` / `api_key` | 端点 + 凭据 |
| `stream_enabled` / `enabled` / `is_default` | 启用控制 |
| `scope_type` / `scope_tenant_id` / `scope_user_id` / `app_id` | 多租户横切 |

### 6.2 Provider 实现

- `OpenAiCompatibleLlmProvider`：OpenAI 兼容端点（含 SiliconFlow GLM-4.7、自建 Moma 网关、本地 Ollama 等）。SSE 流，支持 `reasoning_content`（DeepSeek-R1 / o1 / Qwen-thinking 思考链分通道）
- `DifyLlmProvider`：Dify chat-messages 接口
- `JiutianLlmProvider`：九天 LLM
- `StubLlmProvider`：fallback / 测试用
- `DelegatingLlmProvider`：根据 `provider` 字段路由到上述实现

### 6.3 Endpoint 解析

`OpenAiCompatibleLlmProvider.resolveEndpoint(llm)`：

- 如果 `base_url` 已包含 `/chat/completions` → 整串原样作为请求 URL
- 否则按 `/v1` 后缀智能拼接

所以"完整 URL 直接塞 `base_url`"是合法用法（例如自建 Moma 网关 `http://10.173.6.238:39030/largemodel/moma/api/v3/chat/completions`）。`chat_path` 字段在这条路径上未被使用，是历史遗留。

---

## 7. 内置工具清单

| 工具名 | 用途 |
|---|---|
| `echo` | 调试 |
| `workspace.path` | 列 workspace 根 |
| `file.list / file.read / file.write` | 工作区文件读写 |
| `artifact.save` | 通用产物保存 |
| `http.fetch` | HTTP 请求 |
| `shell.exec` | shell 命令（默认需审批） |
| `db.query` | SELECT（白名单 + 分区改写 + auto-repair 错列名） |
| `db.execute` | INSERT/UPDATE/DELETE/DDL（默认需审批） |
| `db.schema.inspect` | 表 schema 探针（白名单内） |
| `table.render` | 结构化结果 → 前端表格块 |
| `chart.echarts` | 聚合结果 → ECharts 图表（柱 / 折 / 饼） |
| `plan.create / plan.update` | Plan 操作 |
| `artifact.markdown` | 生成 markdown 报告（受 reportSection 校验） |
| `artifact.word / artifact.excel / artifact.ppt` | 其他文档产物（skill 可在 SkillGuard 里限制） |

---

## 8. 运行 & 测试

### 8.1 本地启动后端

```powershell
$env:DB_HOST="10.173.108.120"
$env:DB_PORT="5433"
$env:DB_NAME="java_claw"
$env:DB_USER="postgres"
$env:DB_PASSWORD="..."
.\gradlew.bat :agent-app:bootRun --args='--spring.profiles.active=postgres'
```

profile 默认走 `postgres`（`application-postgres.yml` 读环境变量）。生产用 `prod`（`application-prod.yml` 凭据硬编码）。

### 8.2 本地启动前端

```powershell
cd web-console
npm install
npm run dev          # 默认 http://localhost:5173
```

### 8.3 编译 / 测试

```powershell
.\gradlew.bat compileJava
.\gradlew.bat build
.\gradlew.bat :agent-app:test
```

集成测试任务：

```powershell
.\gradlew.bat :agent-app:postgresFlowTest         # 真实 PG profile 端到端
.\gradlew.bat :agent-app:siliconflowIntegrationTest
.\gradlew.bat :agent-app:realFlowTest
```

---

## 9. 生产部署

部署目标：`http://10.173.108.120:8888/`

### 9.1 一键安装

```bash
unzip javaclaw-deploy-<date>.zip
cd javaclaw-deploy-<date>
sudo ./install.sh                # 默认 INSTALL_DIR=/data/javaClaw
sudo systemctl enable --now javaclaw-backend
```

`install.sh` 会：
1. 创建 `javaclaw` 系统用户
2. 拷 jar 到 `/data/javaClaw/agent-app.jar`
3. 拷 web-console dist 到 `/data/javaClaw/web-console/`
4. `chmod 0701 /data/javaClaw`（让 nginx 能穿过该目录但不 ls 列敏感文件名）
5. nginx 配置已存在且无 diff → 跳过；否则 install + `nginx -t` + reload
6. 装 systemd unit `javaclaw-backend.service`

### 9.2 仅热更新 jar

```bash
sudo install -m 0644 -o javaclaw -g javaclaw \
    agent-app.jar /data/javaClaw/agent-app.jar
sudo systemctl restart javaclaw-backend
sudo journalctl -u javaclaw-backend -f -n 100   # 看到 "Started AgentApp" 即成功
```

### 9.3 部署文件清单

`deploy/` 目录入仓，含：
- `install.sh` — 一键安装脚本
- `nginx-javaclaw.conf` — nginx server block
- `javaclaw-backend.service` — systemd unit
- `README.md` — 详细部署 + 故障排查（拓扑 / 端到端验证 / 常见问题 / 升级回滚）

### 9.4 端到端验证

```bash
curl http://10.173.108.120:8888/                              # 200 + index.html
curl http://10.173.108.120:8888/api/apps/xmap/enabled         # 200 + JSON
curl --max-time 3 http://10.173.108.120:8080/api/...          # 期望 connection refused
curl -X POST http://10.173.108.120:8888/oauth/token \
    -d 'grant_type=client_credentials&client_id=xmap&client_secret=...'
```

---

## 10. 模块说明

| 模块 | 职责 |
|---|---|
| `agent-api` | HTTP / 前端 DTO |
| `agent-domain` | 领域对象（AgentEvent / ChatAttachment / ToolSchema ...） |
| `agent-runtime` | Tool Loop 状态机、运行时边界、Skill / Plan 数据结构、Orchestrator 接口 |
| `agent-infra` | JPA、Tool 实现、LLM Provider、Workspace 读取、Skill 落地、SkillPlanSeeder、PlanPreflightExecutor、SimpleAgentRunner |
| `agent-web` | REST 控制器、WebSocket 端点、SSE 流、JwtAuthWebFilter、OauthCorsFilter |
| `agent-security` | 加密 / Token / 凭据工具 |
| `agent-tool` | 工具基础类型 |
| `agent-workspace` | Workspace 读取 |
| `agent-app` | Spring Boot 启动 + profile 配置 |
| `web-console` | Vue 3 前端控制台 |
| `xmap-ol-front` | GIS 应用（git submodule，独立仓库） |

---

## 附：常见排查

| 现象 | 可能原因 | 排查 |
|---|---|---|
| 浏览器访问 8888 → 403 / 500 | `/data/javaClaw` 是 0750 nginx 进不去 | `chmod 0701 /data/javaClaw`，或 `usermod -aG javaclaw nginx` |
| LLM 输出 "scope=NO_FILTER" 但用户问的是某区县 | preflight 抢先用 NoFilter 跑掉 | 检查 `PlanPreflightExecutor.hasAdminScopeIntent` 是否命中你的输入；不命中可能是地名特殊 |
| `plan.update COMPLETED` 被拒：requires N db.query / requiredTables | step 数据未齐 | 看 `tool_audit_log` 该 step 的 `EXECUTION_OUTCOME` 是不是有失败的 db.query |
| `artifact.markdown` 被 SkillGuard POLICY_DECISION 拒：占位符未填 | LLM 写了 "待补充" 类字面值 | 这是 SkillGuard 兜底，行为正确；要让 LLM 修，让它先把数据查完再写 |
| `nginx: ... is successful` 但 install.sh 仍 WARN | reload/restart 失败但旧脚本文案归罪 nginx -t | 已修，新脚本独立判断每一步 |
| OTT 表查询返回 0 行 | 表在 yunnan 全省，加错 city 后缀 | 模板里 `REGEXP_REPLACE(city, '(市|省|自治区)$', '')` 双边归一化已处理；自查 city 字段实际值 |
