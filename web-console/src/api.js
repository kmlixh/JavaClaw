const jsonHeaders = {
  "Content-Type": "application/json"
};

// embed 模式:父 SDK 通过 postMessage 投来 access_token,存在这里被所有 fetch 读取,
// 同时给 WebSocket URL 提供 attachWsToken 用。非 embed 模式下永远是空字符串。
let embedAccessToken = "";
export function setEmbedAccessToken(token) {
  embedAccessToken = token || "";
}
export function getEmbedAccessToken() {
  return embedAccessToken;
}
export function isEmbedMode() {
  return !!embedAccessToken;
}

function authHeaders(extra) {
  const headers = Object.assign({}, extra || {});
  if (embedAccessToken) headers["Authorization"] = `Bearer ${embedAccessToken}`;
  return headers;
}

// embed 模式下后端跨域,不带 cookie;非 embed 走同源 cookie。
function authedFetch(url, options) {
  const opts = Object.assign({}, options || {});
  opts.headers = authHeaders(opts.headers);
  if (embedAccessToken) {
    opts.credentials = "omit";
  } else if (!opts.credentials) {
    opts.credentials = "same-origin";
  }
  return fetch(url, opts);
}

// 401 来自 JwtAuthWebFilter(anonymous-enabled=false 时):派一个全局事件,
// App.vue 监听后清掉 authUser + router.replace('/login')。
// embed 模式下不跳登录,父页面会重新发 token 或显示错误。
function dispatchUnauthorized() {
  if (typeof window !== "undefined" && !embedAccessToken) {
    window.dispatchEvent(new CustomEvent("auth:unauthorized"));
  }
}

async function check(response) {
  if (response.status === 401) {
    dispatchUnauthorized();
    throw new Error("UNAUTHENTICATED");
  }
  if (!response.ok) {
    const text = await response.text();
    const err = new Error(text || `HTTP ${response.status}`);
    err.status = response.status;
    throw err;
  }
  return response;
}

// ----- Auth (P2) ----------------------------------------------------------
// Cookie is HttpOnly + Lax so the browser auto-attaches AGENT_TOKEN on every request
// under the same origin. Frontend code never touches the token directly.
export async function login(username, password) {
  const res = await authedFetch("/api/auth/login", {
    method: "POST",
    headers: jsonHeaders,
    credentials: "same-origin",
    body: JSON.stringify({ username, password })
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: `HTTP ${res.status}` }));
    const err = new Error(body.message || "login failed");
    err.code = body.code;
    throw err;
  }
  return res.json();
}

export async function logout() {
  await authedFetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
}

/**
 * 把 iframe 里拿到的 OAuth access_token 换成主控台用的 session cookie。
 * 专门给"在新窗口打开主控台"按钮路径用 —— iframe 内是 Bearer,只在内存有效,
 * 刷新就丢回登录页;换成 cookie 后浏览器自动持久化,跨刷新都能用同一身份。
 *
 * 这里不能走 authedFetch:那条在设置了 embedAccessToken 后会 omit credentials,
 * 我们这里恰恰要 credentials:include 让 Set-Cookie 落到浏览器。
 */
export async function exchangeOauthToSession(oauthToken) {
  const res = await fetch("/api/auth/exchange-oauth", {
    method: "POST",
    credentials: "include",
    headers: { "Authorization": `Bearer ${oauthToken}` }
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(text || `exchange-oauth failed: HTTP ${res.status}`);
  }
  return res.json();
}

export async function whoami() {
  const res = await authedFetch("/api/auth/whoami", { credentials: "same-origin" });
  // anonymous-enabled=false 时,filter 也会让 /api/auth/whoami 通过(带匿名 principal),
  // controller 会回 {anonymous:true,...},不会 401。这里 401 真的就是异常。
  if (res.status === 401) {
    dispatchUnauthorized();
    return { anonymous: true };
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export async function switchTenant(tenantId) {
  const res = await authedFetch("/api/auth/switch-tenant", {
    method: "POST",
    headers: jsonHeaders,
    credentials: "same-origin",
    body: JSON.stringify({ tenantId })
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: `HTTP ${res.status}` }));
    throw new Error(body.message || "switch-tenant failed");
  }
  return res.json();
}

export async function changePassword(oldPassword, newPassword) {
  const res = await authedFetch("/api/auth/change-password", {
    method: "POST",
    headers: jsonHeaders,
    credentials: "same-origin",
    body: JSON.stringify({ oldPassword, newPassword })
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: `HTTP ${res.status}` }));
    throw new Error(body.message || "change password failed");
  }
  return res.json();
}

/**
 * 大文件附件流式上传 —— FormData multipart,后端 transferTo 直接落工作区,
 * 不走 base64 内联。返回 {name, path, contentType, size, sessionId, agentId}。
 */
export async function uploadChatAttachment(file, { sessionId, agentId } = {}) {
  const fd = new FormData();
  fd.append("file", file, file.name);
  const params = new URLSearchParams();
  if (sessionId) params.set("sessionId", sessionId);
  if (agentId) params.set("agentId", agentId);
  const url = `/api/chat/attachments${params.toString() ? "?" + params.toString() : ""}`;
  // 注意:不设 Content-Type,让浏览器自己加 multipart/form-data; boundary=...
  const response = await authedFetch(url, { method: "POST", body: fd });
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body?.message) detail = body.message;
    } catch (_) { /* 非 JSON 错误,保持 status text */ }
    throw new Error(detail);
  }
  return response.json();
}

export async function listAgents() {
  return check(await authedFetch("/api/agents")).then((r) => r.json());
}

export async function listLlms() {
  return check(await authedFetch("/api/llms")).then((r) => r.json());
}

export async function listAdminLlms() {
  return check(await authedFetch("/api/admin/llms")).then((r) => r.json());
}

export async function saveAdminLlm(payload) {
  return check(await authedFetch("/api/admin/llms", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteAdminLlm(id) {
  return check(await authedFetch(`/api/admin/llms/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function listSessions(agentId, limit) {
  const params = new URLSearchParams();
  if (agentId) params.set("agentId", agentId);
  if (limit && Number.isFinite(limit) && limit > 0) params.set("limit", String(limit));
  const suffix = params.toString() ? `?${params.toString()}` : "";
  return check(await authedFetch(`/api/sessions${suffix}`)).then((r) => r.json());
}

export async function sendChat(payload) {
  return check(await authedFetch("/api/chat/send", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function getSession(sessionId) {
  return check(await authedFetch(`/api/sessions/${encodeURIComponent(sessionId)}`)).then((r) => r.json());
}

export async function renameSession(sessionId, title) {
  return check(await authedFetch(`/api/sessions/${encodeURIComponent(sessionId)}/title`, {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify({ title })
  })).then((r) => r.json());
}

export async function deleteSession(sessionId) {
  return check(await authedFetch(`/api/sessions/${encodeURIComponent(sessionId)}`, {
    method: "DELETE"
  }));
}

export async function getRun(runId) {
  return check(await authedFetch(`/api/runs/${encodeURIComponent(runId)}`)).then((r) => r.json());
}

/**
 * 拉一个进行中 run 的事件历史快照(内存里累积的)。
 * 用于"刷新页面后恢复 in-progress run"流程:先 GET 这个端点把已发生的事件回灌 UI,再开 WS attach。
 * 已结束 / 没权限 / 不存在都返回 [],前端按 type|runId|timestamp|content 自动 dedup。
 */
export async function getRunEventSnapshot(runId) {
  const response = await authedFetch(`/api/runs/${encodeURIComponent(runId)}/event-snapshot`);
  await check(response);
  return response.json();
}

export async function getActiveRun(sessionId) {
  const response = await check(await authedFetch(`/api/sessions/${encodeURIComponent(sessionId)}/active-run`));
  return response.status === 204 ? null : response.json();
}

export async function listRunsBySession(sessionId) {
  return check(await authedFetch(`/api/sessions/${encodeURIComponent(sessionId)}/runs`)).then((r) => r.json());
}

export async function listActiveRuns() {
  return check(await authedFetch("/api/runs/active")).then((r) => r.json());
}

export async function cancelRun(runId) {
  const response = await authedFetch(`/api/runs/${encodeURIComponent(runId)}/cancel`, {
    method: "POST"
  });
  // 404 is a legitimate response when the run is already gone — return body either way.
  if (!response.ok && response.status !== 404) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }
  return response.json();
}

// ── Memory notes (admin CRUD) ────────────────────────────────────────────
export async function listMemoryNotesAdmin(agentId) {
  return check(await authedFetch(`/api/admin/memory-notes?agentId=${encodeURIComponent(agentId)}`))
    .then((r) => r.json());
}

export async function saveMemoryNoteAdmin(payload) {
  return check(await authedFetch(`/api/admin/memory-notes`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteMemoryNoteAdmin(id) {
  return check(await authedFetch(`/api/admin/memory-notes/${encodeURIComponent(id)}`, {
    method: "DELETE"
  }));
}

// ── Agent definitions (admin CRUD) ─────────────────────────────────────
export async function listAgentDefinitionsAdmin() {
  return check(await authedFetch(`/api/admin/agents`)).then((r) => r.json());
}

export async function saveAgentDefinitionAdmin(payload) {
  return check(await authedFetch(`/api/admin/agents`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteAgentDefinitionAdmin(agentId) {
  return check(await authedFetch(`/api/admin/agents/${encodeURIComponent(agentId)}`, {
    method: "DELETE"
  }));
}

export function artifactDownloadUrl(artifactId) {
  return `/api/artifacts/${encodeURIComponent(artifactId)}/download`;
}

export async function getMemories(agentId) {
  return check(await authedFetch(`/api/agents/${encodeURIComponent(agentId)}/memories`)).then((r) => r.json());
}

export async function search({ q, sessionId, agentId, runId }) {
  const params = new URLSearchParams({ q });
  if (sessionId) params.set("sessionId", sessionId);
  if (agentId) params.set("agentId", agentId);
  if (runId) params.set("runId", runId);
  return check(await authedFetch(`/api/search?${params.toString()}`)).then((r) => r.json());
}

export async function getApproval(id) {
  return check(await authedFetch(`/api/approvals/${encodeURIComponent(id)}`)).then((r) => r.json());
}

export async function listApprovals({ agentId, sessionId, status } = {}) {
  const params = new URLSearchParams();
  if (agentId) params.set("agentId", agentId);
  if (sessionId) params.set("sessionId", sessionId);
  if (status) params.set("status", status);
  const suffix = params.toString() ? `?${params.toString()}` : "";
  return check(await authedFetch(`/api/approvals${suffix}`)).then((r) => r.json());
}

export async function listKnowledgeEntries(agentId) {
  return check(await authedFetch(`/api/admin/knowledge?agentId=${encodeURIComponent(agentId)}`)).then((r) => r.json());
}

export async function saveKnowledgeEntry(payload) {
  return check(await authedFetch("/api/admin/knowledge", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteKnowledgeEntry(id) {
  return check(await authedFetch(`/api/admin/knowledge/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function listToolDefinitions(agentId) {
  return check(await authedFetch(`/api/admin/tools?agentId=${encodeURIComponent(agentId)}`)).then((r) => r.json());
}

export async function saveToolDefinition(payload) {
  return check(await authedFetch("/api/admin/tools", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteToolDefinition(id) {
  return check(await authedFetch(`/api/admin/tools/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function listSkillDefinitions(agentId) {
  return check(await authedFetch(`/api/admin/skills?agentId=${encodeURIComponent(agentId)}`)).then((r) => r.json());
}

export async function saveSkillDefinition(payload) {
  return check(await authedFetch("/api/admin/skills", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteSkillDefinition(id) {
  return check(await authedFetch(`/api/admin/skills/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function listDatasources() {
  return check(await authedFetch("/api/admin/datasources")).then((r) => r.json());
}

export async function saveDatasource(payload) {
  return check(await authedFetch("/api/admin/datasources", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteDatasource(id) {
  return check(await authedFetch(`/api/admin/datasources/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

// ── Admin: users / tenants / roles / oauth-clients (P4-A) ────────────────
export async function adminListUsers() {
  return check(await authedFetch("/api/admin/users")).then((r) => r.json());
}
export async function adminCreateUser(payload) {
  return check(await authedFetch("/api/admin/users", {
    method: "POST", headers: jsonHeaders, body: JSON.stringify(payload)
  })).then((r) => r.json());
}
export async function adminUpdateUser(id, payload) {
  return check(await authedFetch(`/api/admin/users/${encodeURIComponent(id)}`, {
    method: "PUT", headers: jsonHeaders, body: JSON.stringify(payload)
  })).then((r) => r.json());
}
export async function adminDeleteUser(id) {
  return check(await authedFetch(`/api/admin/users/${encodeURIComponent(id)}`, { method: "DELETE" }));
}
export async function adminResetUserPassword(id) {
  return check(await authedFetch(`/api/admin/users/${encodeURIComponent(id)}/reset-password`, {
    method: "POST"
  })).then((r) => r.json());
}
export async function adminImportUsers(rows) {
  return check(await authedFetch("/api/admin/users/import", {
    method: "POST", headers: jsonHeaders, body: JSON.stringify(rows)
  })).then((r) => r.json());
}
export async function adminAssignRoles(id, tenantId, roleIds) {
  return check(await authedFetch(`/api/admin/users/${encodeURIComponent(id)}/roles`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({ tenantId, roleIds })
  })).then((r) => r.json());
}
export async function adminListUserTenantRoles(id) {
  return check(await authedFetch(`/api/admin/users/${encodeURIComponent(id)}/tenant-roles`)).then((r) => r.json());
}

export async function adminListTenants() {
  return check(await authedFetch("/api/admin/tenants")).then((r) => r.json());
}
export async function adminSaveTenant(payload) {
  return check(await authedFetch("/api/admin/tenants", {
    method: "POST", headers: jsonHeaders, body: JSON.stringify(payload)
  })).then((r) => r.json());
}
export async function adminDeleteTenant(id) {
  return check(await authedFetch(`/api/admin/tenants/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function adminListRoles(tenantId) {
  const q = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : "";
  return check(await authedFetch(`/api/admin/roles${q}`)).then((r) => r.json());
}
export async function adminSaveRole(payload) {
  return check(await authedFetch("/api/admin/roles", {
    method: "POST", headers: jsonHeaders, body: JSON.stringify(payload)
  })).then((r) => r.json());
}
export async function adminDeleteRole(id) {
  return check(await authedFetch(`/api/admin/roles/${encodeURIComponent(id)}`, { method: "DELETE" }));
}
export async function adminListRolePermissions(id) {
  return check(await authedFetch(`/api/admin/roles/${encodeURIComponent(id)}/permissions`)).then((r) => r.json());
}
export async function adminUpdateRolePermissions(id, permissionCodes) {
  return check(await authedFetch(`/api/admin/roles/${encodeURIComponent(id)}/permissions`, {
    method: "PUT", headers: jsonHeaders, body: JSON.stringify({ permissionCodes })
  })).then((r) => r.json());
}

export async function adminListPermissions() {
  return check(await authedFetch("/api/admin/permissions")).then((r) => r.json());
}

export async function adminListUserPermissions(userId, tenantId) {
  const params = new URLSearchParams({ userId, tenantId });
  return check(await authedFetch(`/api/admin/user-permissions?${params}`)).then((r) => r.json());
}
export async function adminUpsertUserPermission(payload) {
  return check(await authedFetch("/api/admin/user-permissions", {
    method: "POST", headers: jsonHeaders, body: JSON.stringify(payload)
  })).then((r) => r.json());
}
export async function adminDeleteUserPermission(userId, tenantId, permissionCode) {
  const params = new URLSearchParams({ userId, tenantId, permissionCode });
  return check(await authedFetch(`/api/admin/user-permissions?${params}`, { method: "DELETE" }));
}

export async function adminListOauthClients() {
  return check(await authedFetch("/api/admin/oauth-clients")).then((r) => r.json());
}
export async function adminCreateOauthClient(payload) {
  return check(await authedFetch("/api/admin/oauth-clients", {
    method: "POST", headers: jsonHeaders, body: JSON.stringify(payload)
  })).then((r) => r.json());
}
export async function adminUpdateOauthClient(clientId, payload) {
  return check(await authedFetch(`/api/admin/oauth-clients/${encodeURIComponent(clientId)}`, {
    method: "PUT", headers: jsonHeaders, body: JSON.stringify(payload)
  })).then((r) => r.json());
}
export async function adminRotateOauthClientSecret(clientId) {
  return check(await authedFetch(`/api/admin/oauth-clients/${encodeURIComponent(clientId)}/rotate-secret`, {
    method: "POST"
  })).then((r) => r.json());
}
export async function adminDeleteOauthClient(clientId) {
  return check(await authedFetch(`/api/admin/oauth-clients/${encodeURIComponent(clientId)}`, { method: "DELETE" }));
}

export async function approve(id) {
  return check(await authedFetch(`/api/approvals/${encodeURIComponent(id)}/approve`, { method: "POST" })).then((r) => r.json());
}

export async function reject(id) {
  return check(await authedFetch(`/api/approvals/${encodeURIComponent(id)}/reject`, { method: "POST" })).then((r) => r.json());
}

/**
 * 用户级单连接 WebSocket。登录之后建一条到 /ws/events 的长连接,后端 UserEventHub 按用户权限
 * fan-out 所有可见 session 的事件流到这一条 socket。每个浏览器 tab 一条,断线指数退避重连。
 *
 * 旧的 createSseStream / createWebSocketStream(每发一条消息或每打开一个 session 就开一条新 WS)
 * 已经完全删掉:消息发送走 HTTP POST /api/chat/send,事件接收走这条单 socket。
 *
 * @param {{onEvent, onOpen, onClose, onError}} cbs - 仅 onEvent 必填。
 * @returns 一个 { close() } 句柄,调用 close 永久断开本连接(不自动重连)。
 */
export function openGlobalEventSocket({ onEvent, onOpen, onClose, onError } = {}) {
  if (typeof onEvent !== "function") {
    throw new Error("openGlobalEventSocket: onEvent is required");
  }
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  let socket = null;
  let pingTimer = null;
  let pongWatchdog = null;
  let nextPingId = 1;
  let lastPingId = 0;
  let manuallyClosed = false;
  let reconnectAttempts = 0;
  let reconnectTimer = null;

  const PING_INTERVAL_MS = 15_000;
  const PONG_TIMEOUT_MS = 10_000;
  const RECONNECT_BASE_MS = 1_000;
  const RECONNECT_MAX_MS = 30_000;

  function buildUrl() {
    // embed 模式必须把 token 拼到 URL(浏览器 WebSocket API 不能设 header)。同源 cookie 模式
    // 不需要 access_token,AGENT_TOKEN cookie 会被浏览器自动带上 handshake。
    const params = new URLSearchParams();
    if (embedAccessToken) params.set("access_token", embedAccessToken);
    const qs = params.toString();
    return `${protocol}//${window.location.host}/ws/events${qs ? `?${qs}` : ""}`;
  }

  function stopHeartbeat() {
    if (pingTimer) { clearInterval(pingTimer); pingTimer = null; }
    if (pongWatchdog) { clearTimeout(pongWatchdog); pongWatchdog = null; }
  }

  function startHeartbeat() {
    stopHeartbeat();
    pingTimer = setInterval(() => {
      if (!socket || socket.readyState !== WebSocket.OPEN) return;
      const id = nextPingId++;
      lastPingId = id;
      try {
        socket.send(JSON.stringify({ type: "ping", id, ts: Date.now() }));
      } catch (sendError) {
        console.warn("[global.ws.ping.send_failed]", sendError);
        return;
      }
      if (pongWatchdog) clearTimeout(pongWatchdog);
      pongWatchdog = setTimeout(() => {
        if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
          console.warn("[global.ws.pong.timeout_force_close]", { lastPingId });
          try { socket.close(4000, "heartbeat_timeout"); } catch (_) { /* ignore */ }
        }
      }, PONG_TIMEOUT_MS);
    }, PING_INTERVAL_MS);
  }

  function scheduleReconnect() {
    if (manuallyClosed) return;
    if (reconnectTimer) return;
    const delay = Math.min(RECONNECT_BASE_MS * (2 ** reconnectAttempts), RECONNECT_MAX_MS);
    reconnectAttempts += 1;
    console.log("[global.ws.reconnect.scheduled]", { attempt: reconnectAttempts, delayMs: delay });
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      if (manuallyClosed) return;
      connect();
    }, delay);
  }

  function connect() {
    const url = buildUrl();
    console.log("[global.ws.connecting]", url);
    try {
      socket = new WebSocket(url);
    } catch (error) {
      console.error("[global.ws.construct_failed]", error);
      scheduleReconnect();
      return;
    }
    socket.onopen = () => {
      console.log("[global.ws.open]", url);
      reconnectAttempts = 0;
      startHeartbeat();
      onOpen?.();
    };
    socket.onmessage = (event) => {
      let payload = null;
      try {
        payload = JSON.parse(event.data);
      } catch (parseError) {
        console.error("[global.ws.invalid]", event.data, parseError);
        return;
      }
      // 心跳响应,业务不感知
      if (payload && payload.type === "pong") {
        if (pongWatchdog) { clearTimeout(pongWatchdog); pongWatchdog = null; }
        return;
      }
      // 后端的鉴权拒绝消息:走 401 流程跳登录
      if (payload && payload.type === "AUTH_REJECTED") {
        console.warn("[global.ws.auth_rejected]", payload.message);
        manuallyClosed = true;
        stopHeartbeat();
        try { socket.close(4001, "auth_rejected"); } catch (_) { /* ignore */ }
        dispatchUnauthorized();
        return;
      }
      onEvent(payload);
    };
    socket.onerror = (error) => {
      console.warn("[global.ws.error]", error);
      onError?.(error);
    };
    socket.onclose = (event) => {
      stopHeartbeat();
      console.log("[global.ws.close]", {
        code: event.code,
        reason: event.reason,
        wasClean: event.wasClean
      });
      onClose?.(event);
      socket = null;
      if (!manuallyClosed) {
        scheduleReconnect();
      }
    };
  }

  connect();

  return {
    close() {
      manuallyClosed = true;
      stopHeartbeat();
      if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
      if (socket) {
        try { socket.close(1000, "client_close"); } catch (_) { /* ignore */ }
        socket = null;
      }
    }
  };
}

// ----- Token Usage --------------------------------------------------------
// 三组接口用于"Token 用量"页面;权限过滤后端做(SessionVisibility 三档)。
// 时间参数支持 ISO-8601(2026-05-09T00:00:00Z) 或 epoch millis。

export async function fetchUsageSummary({ from, to, groupBy } = {}) {
  const params = new URLSearchParams();
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  if (groupBy) params.set("groupBy", groupBy);
  const response = await authedFetch(`/api/usage/summary?${params}`);
  await check(response);
  return response.json();
}

export async function fetchUsageTimeseries({ from, to, interval, groupBy } = {}) {
  const params = new URLSearchParams();
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  if (interval) params.set("interval", interval);
  if (groupBy) params.set("groupBy", groupBy);
  const response = await authedFetch(`/api/usage/timeseries?${params}`);
  await check(response);
  return response.json();
}

export async function fetchUsageRecent({ limit = 50 } = {}) {
  const response = await authedFetch(`/api/usage/recent?limit=${limit}`);
  await check(response);
  return response.json();
}

// ----- Agent 实验室 -------------------------------------------------------
// 仅系统管理员可见,后端要求 permission session.read.all 或 agent.lab.use 之一,
// 匿名期(anonymous-enabled=true)无障碍。

export async function labCreateTask(payload) {
  const response = await authedFetch("/api/lab/tasks", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  });
  await check(response);
  return response.json();
}

export async function labListTasks() {
  const response = await authedFetch("/api/lab/tasks");
  await check(response);
  return response.json();
}

export async function labTaskDetail(taskId) {
  const response = await authedFetch(`/api/lab/tasks/${encodeURIComponent(taskId)}`);
  await check(response);
  return response.json();
}

export async function labCancelTask(taskId) {
  const response = await authedFetch(`/api/lab/tasks/${encodeURIComponent(taskId)}/run`, {
    method: "DELETE"
  });
  await check(response);
  return response.json();
}

export async function labRestartTask(taskId) {
  const response = await authedFetch(`/api/lab/tasks/${encodeURIComponent(taskId)}/restart`, {
    method: "POST"
  });
  await check(response);
  return response.json();
}

/** 改写约束规则后再次迭代。body: {constraintRules, referenceDocuments?} */
export async function labRefineConstraints(taskId, payload) {
  const response = await authedFetch(`/api/lab/tasks/${encodeURIComponent(taskId)}/refine`, {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  });
  await check(response);
  return response.json();
}

/**
 * "AI 帮我完善目标描述":一次 LLM 调用 + 多轮对话历史。
 * 后端无持久化 —— 客户端自己在 Lab modal 里维护对话历史。
 */
export async function labRefineGoal(payload) {
  const response = await authedFetch("/api/lab/refine-goal", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  });
  await check(response);
  return response.json();
}

/** 拉所有 skill(用于 Agent 实验室的 Skill 选择下拉)。 */
export async function labListAllSkills() {
  const response = await authedFetch("/api/admin/skills");
  await check(response);
  return response.json();
}

// ----- 管理员会话审计 -----------------------------------------------------
// 系统管理员/租户管理员看到所有(或本租户)会话列表 + 单 run 复盘。
// 后端权限走 SessionVisibility 三档,匿名兜底。

export async function adminListSessions({ userId, tenantId, appId, agentId, from, to, page = 0, size = 20 } = {}) {
  const params = new URLSearchParams();
  if (userId) params.set("userId", userId);
  if (tenantId) params.set("tenantId", tenantId);
  if (appId) params.set("appId", appId);
  if (agentId) params.set("agentId", agentId);
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  params.set("page", String(page));
  params.set("size", String(size));
  const response = await authedFetch(`/api/admin/sessions?${params}`);
  await check(response);
  return response.json();
}

export async function adminGetRunReplay(runId) {
  const response = await authedFetch(`/api/admin/runs/${encodeURIComponent(runId)}/replay`);
  await check(response);
  return response.json();
}

/**
 * V42 行级复盘端点。后端 SQL 服务端按 categories 过滤,前端不用 parse 整段 JSON。
 * categories 为空 / null = 不过滤,返回全量。
 * 返回 AdminRunStep[]:每条带 seq / ts / category / eventType / toolName / summary / payloadJson。
 */
export async function adminGetRunSteps(runId, categories) {
  const params = new URLSearchParams();
  if (Array.isArray(categories) && categories.length > 0) {
    params.set("category", categories.join(","));
  }
  const qs = params.toString();
  const url = qs
    ? `/api/admin/runs/${encodeURIComponent(runId)}/steps?${qs}`
    : `/api/admin/runs/${encodeURIComponent(runId)}/steps`;
  const response = await authedFetch(url);
  await check(response);
  return response.json();
}

export async function adminListSessionRuns(sessionId) {
  const response = await authedFetch(`/api/admin/sessions/${encodeURIComponent(sessionId)}/runs`);
  await check(response);
  return response.json();
}

/**
 * 当前登录用户可见的"租户过滤"下拉数据源。
 * 返回 { rows: [{id, code, name, kind, status}], canFilter: bool }。
 * canFilter=false 时前端应把下拉禁用 —— 非 sysadmin 没权限跨租户筛。
 */
export async function listFilterableTenants() {
  const response = await authedFetch("/api/me/filterable-tenants");
  await check(response);
  return response.json();
}

/**
 * 当前登录用户可见的"应用过滤"下拉数据源。
 * 返回 { rows: [{appId, displayName, tenantId}], canFilter: bool }。
 * 永远包含一条 system-default(主控台直登的会话 app_id 是它)。
 */
export async function listFilterableApps() {
  const response = await authedFetch("/api/me/filterable-apps");
  await check(response);
  return response.json();
}

/** 真分页 session 列表。返回 { rows, total, page, size }。
 *  支持多维过滤:agentId / userId / tenantId / appId / runStatus(running/idle/all)/ keyword。
 *  权限由后端 SessionVisibility 兜底,这里只是前端透传。 */
export async function listSessionsPaged({ agentId, userId, tenantId, appId, runStatus, page = 0, size = 15, keyword } = {}) {
  const params = new URLSearchParams();
  if (agentId) params.set("agentId", agentId);
  if (userId) params.set("userId", userId);
  if (tenantId) params.set("tenantId", tenantId);
  if (appId) params.set("appId", appId);
  if (runStatus && runStatus !== "all") params.set("runStatus", runStatus);
  params.set("page", String(page));
  params.set("size", String(size));
  if (keyword && keyword.trim()) params.set("keyword", keyword.trim());
  const response = await authedFetch(`/api/sessions/paged?${params}`);
  await check(response);
  return response.json();
}
