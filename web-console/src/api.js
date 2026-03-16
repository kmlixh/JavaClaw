const jsonHeaders = {
  "Content-Type": "application/json"
};

async function check(response) {
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }
  return response;
}

export async function listAgents() {
  return check(await fetch("/api/agents")).then((r) => r.json());
}

export async function listLlms() {
  return check(await fetch("/api/llms")).then((r) => r.json());
}

export async function listSessions(agentId) {
  const params = new URLSearchParams();
  if (agentId) params.set("agentId", agentId);
  const suffix = params.toString() ? `?${params.toString()}` : "";
  return check(await fetch(`/api/sessions${suffix}`)).then((r) => r.json());
}

export async function sendChat(payload) {
  return check(await fetch("/api/chat/send", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function getSession(sessionId) {
  return check(await fetch(`/api/sessions/${encodeURIComponent(sessionId)}`)).then((r) => r.json());
}

export async function renameSession(sessionId, title) {
  return check(await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/title`, {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify({ title })
  })).then((r) => r.json());
}

export async function deleteSession(sessionId) {
  return check(await fetch(`/api/sessions/${encodeURIComponent(sessionId)}`, {
    method: "DELETE"
  }));
}

export async function getRun(runId) {
  return check(await fetch(`/api/runs/${encodeURIComponent(runId)}`)).then((r) => r.json());
}

export async function getMemories(agentId) {
  return check(await fetch(`/api/agents/${encodeURIComponent(agentId)}/memories`)).then((r) => r.json());
}

export async function search({ q, sessionId, agentId, runId }) {
  const params = new URLSearchParams({ q });
  if (sessionId) params.set("sessionId", sessionId);
  if (agentId) params.set("agentId", agentId);
  if (runId) params.set("runId", runId);
  return check(await fetch(`/api/search?${params.toString()}`)).then((r) => r.json());
}

export async function getApproval(id) {
  return check(await fetch(`/api/approvals/${encodeURIComponent(id)}`)).then((r) => r.json());
}

export async function listApprovals({ agentId, sessionId, status } = {}) {
  const params = new URLSearchParams();
  if (agentId) params.set("agentId", agentId);
  if (sessionId) params.set("sessionId", sessionId);
  if (status) params.set("status", status);
  const suffix = params.toString() ? `?${params.toString()}` : "";
  return check(await fetch(`/api/approvals${suffix}`)).then((r) => r.json());
}

export async function listKnowledgeEntries(agentId) {
  return check(await fetch(`/api/admin/knowledge?agentId=${encodeURIComponent(agentId)}`)).then((r) => r.json());
}

export async function saveKnowledgeEntry(payload) {
  return check(await fetch("/api/admin/knowledge", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteKnowledgeEntry(id) {
  return check(await fetch(`/api/admin/knowledge/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function listToolDefinitions(agentId) {
  return check(await fetch(`/api/admin/tools?agentId=${encodeURIComponent(agentId)}`)).then((r) => r.json());
}

export async function saveToolDefinition(payload) {
  return check(await fetch("/api/admin/tools", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteToolDefinition(id) {
  return check(await fetch(`/api/admin/tools/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function listSkillDefinitions(agentId) {
  return check(await fetch(`/api/admin/skills?agentId=${encodeURIComponent(agentId)}`)).then((r) => r.json());
}

export async function saveSkillDefinition(payload) {
  return check(await fetch("/api/admin/skills", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(payload)
  })).then((r) => r.json());
}

export async function deleteSkillDefinition(id) {
  return check(await fetch(`/api/admin/skills/${encodeURIComponent(id)}`, { method: "DELETE" }));
}

export async function approve(id) {
  return check(await fetch(`/api/approvals/${encodeURIComponent(id)}/approve`, { method: "POST" })).then((r) => r.json());
}

export async function reject(id) {
  return check(await fetch(`/api/approvals/${encodeURIComponent(id)}/reject`, { method: "POST" })).then((r) => r.json());
}

export function createSseStream({ sessionId, agentId, llmConfigId, userId, message, runId, onEvent, onError }) {
  const params = new URLSearchParams({ sessionId, userId, message });
  if (agentId) params.set("agentId", agentId);
  if (llmConfigId) params.set("llmConfigId", llmConfigId);
  if (runId) params.set("runId", runId);
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

export function createWebSocketStream({ sessionId, agentId, llmConfigId, userId, message, runId, onEvent, onClose, onError }) {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const params = new URLSearchParams({ sessionId, userId, message });
  if (agentId) params.set("agentId", agentId);
  if (llmConfigId) params.set("llmConfigId", llmConfigId);
  if (runId) params.set("runId", runId);
  const socket = new WebSocket(`${protocol}//${window.location.host}/ws/chat?${params.toString()}`);

  socket.onmessage = (event) => onEvent(JSON.parse(event.data));
  socket.onerror = onError;
  socket.onclose = onClose;

  return socket;
}
