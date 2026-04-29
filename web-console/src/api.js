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
    throw new Error(text || `HTTP ${response.status}`);
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

export async function listSessions(agentId) {
  const params = new URLSearchParams();
  if (agentId) params.set("agentId", agentId);
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

export function createSseStream({ sessionId, agentId, llmConfigId, llmModel, userId, message, runId, onEvent, onError }) {
  const params = new URLSearchParams({ sessionId, userId, message });
  if (agentId) params.set("agentId", agentId);
  if (llmConfigId) params.set("llmConfigId", llmConfigId);
  if (llmModel) params.set("llmModel", llmModel);
  if (runId) params.set("runId", runId);
  if (embedAccessToken) params.set("access_token", embedAccessToken);
  // SSE EventSource 不能设 header,token 走 query。后端 JwtAuthWebFilter 会读 access_token。
  const source = new EventSource(`/api/chat/stream?${params.toString()}`);
  let terminalReceived = false;

  [
    "RUN_STARTED",
    "RUN_STATUS",
    "TOKEN_DELTA",
    "TOOL_REQUESTED",
    "TOOL_STARTED",
    "TOOL_COMPLETED",
    "APPROVAL_REQUIRED",
    "PLAN_UPDATED",
    "RUN_COMPLETED",
    "RUN_FAILED"
  ].forEach((type) => {
    source.addEventListener(type, (event) => {
      if (type === "RUN_COMPLETED" || type === "RUN_FAILED") {
        terminalReceived = true;
      }
      onEvent({
        type,
        runId: event.lastEventId,
        content: event.data,
        timestamp: new Date().toISOString()
      });
    });
  });

  source.onerror = (error) => {
    if (terminalReceived || source.readyState === EventSource.CLOSED) {
      source.close();
      return;
    }
    onError(error);
    source.close();
  };

  return source;
}

export function createWebSocketStream({ sessionId, agentId, llmConfigId, llmModel, userId, message, runId, attach, onEvent, onClose, onError, onOpen }) {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const params = new URLSearchParams({ sessionId, userId, message });
  if (agentId) params.set("agentId", agentId);
  if (llmConfigId) params.set("llmConfigId", llmConfigId);
  if (llmModel) params.set("llmModel", llmModel);
  if (runId) params.set("runId", runId);
  if (attach) params.set("attach", "true");
  if (embedAccessToken) params.set("access_token", embedAccessToken);
  const wsUrl = `${protocol}//${window.location.host}/ws/chat?${params.toString()}`;
  console.log("[ws.connecting]", {
    url: wsUrl,
    sessionId,
    agentId: agentId || "",
    llmConfigId: llmConfigId || "",
    llmModel: llmModel || "",
    runId: runId || "",
    attach: !!attach
  });
  const socket = new WebSocket(wsUrl);

  socket.onopen = () => {
    console.log("[ws.open]", {
      url: wsUrl,
      readyState: socket.readyState
    });
    onOpen?.();
  };

  socket.onmessage = (event) => {
    console.log("[ws.raw.text]", event.data);
    let payload = null;
    try {
      payload = JSON.parse(event.data);
    } catch (error) {
      console.error("[ws.raw.invalid]", event.data, error);
      return;
    }
    console.log("[ws.raw]", payload);
    onEvent(payload);
  };
  socket.onerror = (error) => {
    console.error("[ws.error]", error);
    onError?.(error);
  };
  socket.onclose = (event) => {
    console.log("[ws.close]", {
      code: event.code,
      reason: event.reason,
      wasClean: event.wasClean
    });
    onClose?.(event);
  };

  return socket;
}
