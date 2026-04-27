# JavaClaw Embed Integration

## 1. Include SDK

```html
<script src="https://your-host/embed/javaclaw-embed-sdk.js"></script>
```

## 2. Create Chat Window

OAuth `client_credentials` 模式 — SDK 直接拿 `clientId` + `clientSecret` 换 access_token，不走用户跳转。

```html
<div id="ai-container" style="width:420px;height:640px;"></div>
<script>
  const chat = JavaClawEmbed.initRightPanel({
    baseUrl: "https://your-host",
    clientId: "xmap",                          // 后端 oauth_client.client_id (== 租户标识)
    clientSecret: "xxxxxxxxxxxxxxxxxxxxxxxxx", // ⚠ 暴露在浏览器:见下文"安全说明"
    userId: "u-1001",                          // 宿主侧用户 id (我们用 (tenant, userId) 唯一识别)
    userName: "Alice",                         // 宿主侧用户显示名(可选,会被同步到 app_user.display_name)
    agentId: "dev-agent",                      // 可选,预选 agent
    onReady(info) { console.log("embed ready", info); },
    onSessionCreated(info) { console.log("session", info); },
    onEvent(event) { console.log("embed event", event); }
  });

  // 注册第三方方法（作为 AI 可调用能力）
  chat.registerMethods({
    openOrder(payload) {
      return { ok: true, orderId: payload.orderId };
    },
    queryCustomer(payload) {
      return fetch(`/api/customer/${payload.id}`).then((r) => r.json());
    }
  });

  chat.sendMessage("请查询客户 C1024 并打开订单 O7788");
</script>
```

### 安全说明

`clientSecret` 写在浏览器 JS 里任何人都看得到。OK 的场景：内网/可信第三方应用，且 OAuth 客户端只配了**有限权限**（例如只能创建会话、不能管理用户）。

**不 OK** 的场景：公网页面 + 高权限客户端。这种情况下推荐：

```js
// 第三方后端先调 POST /oauth/token 拿 token，把 token 嵌进自己的 HTML
const chat = JavaClawEmbed.initRightPanel({
  baseUrl: "https://your-host",
  accessToken: "<%= server_side_fetched_token %>",  // secret 从不进浏览器
  userId: "u-1001"
});
```

SDK 接到 `accessToken` 就跳过 `/oauth/token` 直接用。

## 3. AI-side Tool Contract

- Tool name: `host.invoke`
- Arguments:
  - `method` (string, required)
  - `payload` (object, optional)
  - `timeoutMs` (integer, optional)

The embed page automatically sends `host_methods.json` as message attachment so the model can see available method names.

## 4. Agent / LLM Selection

- Header keeps three selectors: `Agent` / `LLM` / `Model`.
- 可在 init 里传 `agentId` 预选；用户也可在 UI 内切换。
- 切换 Agent 会从下一条消息开始新会话。

## 5. OAuth 服务端配置（提醒）

在我们的「OAuth 应用」管理页面新建客户端时：

| 字段 | 说明 |
|---|---|
| `client_id` | 第三方接入标识 |
| `client_secret` | 创建/重新生成时只显示一次 |
| `tenant_id` | 客户端所属租户（自动落到创建者租户）—— SDK 传过来的 `userId` 都会落到这个租户 |
| `owner_user_id` | 可选。SDK 没传 `userId` 时退化为这个用户;有传 `userId` 时用宿主用户 |
| `redirect_uris` | client_credentials 用不上，authorization_code 模式才需要 |
| `scopes` | 信息字段，目前不强制校验 |

### 自动开通流程

SDK 调 `/oauth/token` 时带上 `user_id` + `user_name`，后端会：
1. 用 `client_id` 找出 `tenant_id`
2. 在 `app_user` 表按 `id = "ext:{tenantId}:{externalUserId}"` 查
3. 不存在就建一条新用户 + 绑到该租户的 `code='USER'` 角色
4. token 颁给那个用户，权限按 USER 角色解析

### 默认角色与菜单

新建租户时（不论是 UI 操作还是 API 调用），后端自动建好两个角色 + 默认菜单：

- `{tenantId}-user`（code=USER）—— `chat / agents / skills / knowledge / memory / datasources / approvals / search` + 私有资源管理 + `session.read.own` + `session.terminate`
- `{tenantId}-tenant-admin`（code=TENANT_ADMIN）—— USER 全部 + `users` / `oauth-clients` 管理 + `session.read.tenant` + `agent.edit`
- `tenant_menu` 默认开通：`chat / agents / skills / knowledge / memory / datasources / approvals / search / users / oauth-clients`

老租户也会在再次保存时被「补缺」（已有的角色/菜单不动，新加的项进来），实现幂等。

## 6. 调试

直接在浏览器打开 `http://your-host/embed/chat.html?access_token=xxx`，跳过 SDK 流程也能用，方便本地验 token。
