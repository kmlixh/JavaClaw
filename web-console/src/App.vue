<script setup>
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import EchartsBlock from "./components/EchartsBlock.vue";
import {
  approve,
  createSseStream,
  createWebSocketStream,
  artifactDownloadUrl,
  getApproval,
  getMemories,
  getRun,
  getSession,
  listApprovals,
  listKnowledgeEntries,
  listAgents,
  listLlms,
  listSessions,
  listSkillDefinitions,
  listToolDefinitions,
  deleteSession,
  deleteKnowledgeEntry,
  deleteSkillDefinition,
  deleteToolDefinition,
  renameSession,
  reject,
  saveKnowledgeEntry,
  saveSkillDefinition,
  saveToolDefinition,
  search,
  sendChat
} from "./api";

const agents = ref([]);
const llms = ref([]);
const approvals = ref([]);
const currentStream = ref(null);
const transport = ref("websocket");
const activeMenu = ref("chat");
const router = useRouter();
const route = useRoute();
const inspector = reactive({
  summary: true,
  timeline: true,
  audit: false
});
const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  session: null
});
const titleModal = reactive({
  visible: false,
  sessionId: "",
  value: ""
});
const deleteModal = reactive({
  visible: false,
  session: null
});
const catalogModal = reactive({
  visible: false,
  type: "",
  mode: "create"
});
const fileInput = ref(null);
const composerEditor = ref(null);
const mentionPicker = reactive({
  visible: false,
  query: "",
  triggerIndex: -1
});
const chartOverrides = reactive({});
let sessionRefreshTimer = null;
let runRefreshTimer = null;
const recentSessions = ref([]);
const loading = reactive({
  sending: false,
  session: false,
  run: false,
  search: false,
  memories: false,
  approval: false,
  catalog: false
});

const form = reactive({
  sessionId: "",
  userId: "anonymous",
  agentId: "",
  llmConfigId: "",
  message: "",
  references: [],
  attachments: []
});

const searchForm = reactive({
  q: "",
  agentId: "",
  sessionId: "",
  runId: ""
});

const state = reactive({
  currentRunId: "",
  resolvedAgentId: "",
  events: [],
  pendingMessages: [],
  liveAssistant: {
    runId: "",
    content: ""
  },
  session: null,
  run: null,
  memories: [],
  search: {
    messages: [],
    memories: [],
    artifacts: []
  },
  catalog: {
    knowledge: [],
    tools: [],
    skills: []
  },
  error: ""
});

const menuItems = [
  { id: "chat", label: "会话操作", hint: "发送消息与接收反馈" },
  { id: "search", label: "搜索检索", hint: "查询消息、记忆、artifact" },
  { id: "approval", label: "审批记录", hint: "查看历史审批记录" },
  { id: "memory", label: "长期记忆", hint: "浏览 memory note" },
  { id: "knowledge", label: "知识库", hint: "管理数据库知识条目" },
  { id: "tools", label: "工具定义", hint: "管理数据库工具白名单" },
  { id: "skills", label: "Skills", hint: "管理数据库 skill 提示词" }
];
const knowledgeForm = reactive({ id: "", title: "", content: "", contentType: "markdown", source: "database", tagsJson: "[]", enabled: true });
const toolForm = reactive({ id: "", toolName: "", displayName: "", description: "", schemaJson: "{}", toolType: "builtin", configJson: "{}", enabled: true, approvalRequired: false });
const skillForm = reactive({ id: "", skillName: "", description: "", promptTemplate: "", configJson: "{}", enabled: true });

const recentEvents = computed(() => state.events.slice().reverse().slice(0, 18));
const transcriptMessages = computed(() => {
  const persisted = state.session?.messages || [];
  if (!state.pendingMessages.length) {
    return persisted;
  }
  const merged = [...persisted];
  for (const pending of state.pendingMessages) {
    const exists = persisted.some((message) =>
      message.runId === pending.runId
      && message.role === pending.role
      && message.content === pending.content
    );
    if (!exists) {
      merged.push(pending);
    }
  }
  return merged.sort((a, b) => {
    const left = Number(a.seqNo || 0);
    const right = Number(b.seqNo || 0);
    if (left !== right) {
      return left - right;
    }
    return String(a.id).localeCompare(String(b.id));
  });
});
const hasAvailableLlms = computed(() => llms.value.length > 0);
const llmWarning = computed(() => hasAvailableLlms.value
  ? ""
  : "后端当前没有加载到任何可用 LLM。请确认 agent-app 使用了正确的 profile，并且 llm_provider_config 表中存在 enabled=true 的配置。");

function hasConcreteRunResult(runId) {
  if (!runId) return false;
  if (state.liveAssistant.runId === runId && state.liveAssistant.content) {
    return true;
  }
  if (transcriptMessages.value.some((message) => message.runId === runId && ["assistant", "tool"].includes(message.role))) {
    return true;
  }
  return state.events.some((event) =>
    event.runId === runId
    && ["TOOL_COMPLETED", "APPROVAL_REQUIRED", "RUN_FAILED"].includes(event.type)
  );
}

const transientStatusHint = computed(() => {
  const runId = state.currentRunId;
  if (!runId || hasConcreteRunResult(runId)) {
    return "";
  }
  const candidate = [...state.events]
    .reverse()
    .find((event) => event.runId === runId && event.type === "RUN_STATUS");
  return candidate?.content || "";
});

function toolDisplayPayload(message) {
  return parseJson(message?.toolResultJson);
}

function isRenderedDisplay(payload) {
  return payload && ["table", "echarts"].includes(payload.displayType);
}

function findRenderableToolForRun(runId) {
  return transcriptMessages.value.find((message) =>
    message.runId === runId
    && message.role === "tool"
    && isRenderedDisplay(toolDisplayPayload(message))
  );
}

function sanitizeAssistantContent(content, runId) {
  const text = String(content || "").trim();
  if (!text) return "";
  if (!findRenderableToolForRun(runId)) {
    return text;
  }
  const markers = ["| 序号 |", "|------|", "\n|"];
  const cutIndex = markers
    .map((marker) => text.indexOf(marker))
    .filter((index) => index >= 0)
    .sort((left, right) => left - right)[0];
  const summary = cutIndex === undefined ? text : text.slice(0, cutIndex).trim();
  const firstParagraph = summary.split(/\n\s*\n/)[0]?.trim() || summary;
  return firstParagraph.length > 220
    ? `${firstParagraph.slice(0, 220).trim()}...`
    : firstParagraph;
}

function renderBlockTitle(message) {
  const payload = toolDisplayPayload(message);
  if (!payload) return message.toolName || "结果";
  if (payload.displayType === "table") return payload.title || "表格结果";
  if (payload.displayType === "echarts") return payload.title || "图表结果";
  return message.toolName || "结果";
}

function artifactPayload(message) {
  const payload = toolDisplayPayload(message);
  return payload?.displayType === "artifact" ? payload : null;
}

function chartOverrideKey(message) {
  return `${message.runId || "run"}-${message.id}`;
}

function selectedChartType(message) {
  return chartOverrides[chartOverrideKey(message)] || inferChartType(message) || "bar";
}

function setChartType(message, chartType) {
  chartOverrides[chartOverrideKey(message)] = chartType;
}

function inferChartType(message) {
  const payload = toolDisplayPayload(message);
  const series = payload?.option?.series;
  if (!Array.isArray(series) || !series.length) {
    return "bar";
  }
  return series[0]?.type || "bar";
}

function chartMeta(message) {
  const payload = toolDisplayPayload(message);
  const option = payload?.option || {};
  const categories = Array.isArray(option.xAxis?.data) ? option.xAxis.data : [];
  const series = Array.isArray(option.series) ? option.series : [];
  return {
    dimension: categories.length ? "xAxis" : "category",
    metrics: series.map((item) => item?.name).filter(Boolean),
    points: categories.length || (Array.isArray(series[0]?.data) ? series[0].data.length : 0)
  };
}

function displayChartOption(message) {
  const payload = toolDisplayPayload(message);
  const option = payload?.option || {};
  const chartType = selectedChartType(message);
  if (!Array.isArray(option.series)) {
    return option;
  }
  if (chartType === "pie") {
    const categories = Array.isArray(option.xAxis?.data) ? option.xAxis.data : [];
    const primary = option.series[0] || {};
    const values = Array.isArray(primary.data) ? primary.data : [];
    return {
      ...option,
      tooltip: { trigger: "item" },
      legend: { top: "bottom" },
      xAxis: undefined,
      yAxis: undefined,
      series: [{
        name: primary.name || "value",
        type: "pie",
        radius: ["35%", "68%"],
        data: categories.map((name, index) => ({
          name,
          value: values[index]
        }))
      }]
    };
  }
  return {
    ...option,
    tooltip: { trigger: "axis" },
    series: option.series.map((item) => ({
      ...item,
      type: chartType
    }))
  };
}

function shouldHideToolMessage(message, runId) {
  return message.role === "tool"
    && message.toolName === "db.query"
    && !!findRenderableToolForRun(runId);
}

function shouldShowMessageText(message, runId) {
  if (message.role === "tool" && isRenderedDisplay(toolDisplayPayload(message))) {
    return false;
  }
  return !!displayMessageContent(message, runId);
}

function shouldShowToolArgs(message, runId) {
  if (shouldHideToolMessage(message, runId)) {
    return false;
  }
  return message.role === "tool" && !isRenderedDisplay(toolDisplayPayload(message));
}

function displayMessageContent(message, runId) {
  if (message.role === "assistant") {
    return sanitizeAssistantContent(message.content, runId);
  }
  return message.content || "";
}

const groupedRunTimeline = computed(() => {
  const orderedRuns = [];
  const runMap = new Map();
  const messagesByRun = new Map();

  for (const message of transcriptMessages.value) {
    const runId = message.runId || "unknown";
    if (!messagesByRun.has(runId)) {
      messagesByRun.set(runId, []);
      orderedRuns.push(runId);
    }
    messagesByRun.get(runId).push(message);
  }

  for (const runId of orderedRuns) {
    const blocks = [];
    for (const message of messagesByRun.get(runId) || []) {
      if (shouldHideToolMessage(message, runId)) {
        continue;
      }
      const normalized = {
        ...message,
        content: displayMessageContent(message, runId)
      };
      if (normalized.role === "assistant" && !normalized.content && findRenderableToolForRun(runId)) {
        continue;
      }
      blocks.push({
        kind: "message",
        key: `message-${message.id}`,
        message: normalized
      });
    }
    runMap.set(runId, blocks);
  }

  for (const event of state.events) {
    const runId = event.runId || state.currentRunId || "current";
    if (!runMap.has(runId)) {
      runMap.set(runId, []);
      orderedRuns.push(runId);
    }
    if (
      [
        "APPROVAL_REQUIRED",
        "RUN_FAILED"
      ].includes(event.type)
    ) {
      runMap.get(runId).push({
        kind: "event",
        key: event.id,
        event
      });
    }
  }

  if (state.liveAssistant.runId && state.liveAssistant.content) {
    if (!runMap.has(state.liveAssistant.runId)) {
      runMap.set(state.liveAssistant.runId, []);
      orderedRuns.push(state.liveAssistant.runId);
    }
    runMap.get(state.liveAssistant.runId).push({
      kind: "live",
      key: `live-${state.liveAssistant.runId}`,
      content: state.liveAssistant.content
    });
  }

  return orderedRuns.map((runId) => ({
    runId,
    blocks: runMap.get(runId)
  }));
});
const activeAgentLabel = computed(() => {
  const id = state.resolvedAgentId || form.agentId;
  if (!id) return "未选择";
  const agent = agents.value.find((item) => item.agentId === id);
  return agent ? `${agent.displayName} · ${agent.agentId}` : id;
});
const currentSessionTitle = computed(() => {
  if (state.session?.title) return state.session.title;
  const current = recentSessions.value.find((item) => item.sessionId === form.sessionId);
  if (current?.title) return current.title;
  const firstUserMessage = transcriptMessages.value.find((item) => item.role === "user" && item.content);
  if (!firstUserMessage?.content) return "New chat";
  return firstUserMessage.content.length <= 40
    ? firstUserMessage.content
    : `${firstUserMessage.content.slice(0, 40)}...`;
});
const pendingApprovals = computed(() => approvals.value.filter((item) => item.status === "PENDING"));
const approvalHistory = computed(() => approvals.value);
const referenceOptions = computed(() => {
  const rawQuery = mentionPicker.query || "";
  const showAll = rawQuery === "" || /^\s/.test(rawQuery);
  const query = rawQuery.trim().toLowerCase();
  const items = [
    ...state.catalog.knowledge.map((item) => ({
      type: "knowledge",
      id: item.id,
      label: item.title,
      subtitle: item.source || "knowledge"
    })),
    ...state.catalog.skills.map((item) => ({
      type: "skill",
      id: item.id,
      label: item.skillName,
      subtitle: item.description || "skill"
    })),
    ...state.catalog.tools.map((item) => ({
      type: "tool",
      id: item.id,
      label: item.displayName || item.toolName,
      subtitle: item.toolName
    }))
  ];
  return items
    .filter((item) => {
      if (showAll || !query) return true;
      return [item.label, item.subtitle, item.type]
        .filter(Boolean)
        .some((value) => value.toLowerCase().includes(query));
    })
    .slice(0, 12);
});
const stageAction = computed(() => {
  switch (activeMenu.value) {
    case "knowledge":
      return {
        count: state.catalog.knowledge.length,
        label: "添加知识",
        action: createKnowledge
      };
    case "tools":
      return {
        count: state.catalog.tools.length,
        label: "添加工具",
        action: createTool
      };
    case "skills":
      return {
        count: state.catalog.skills.length,
        label: "添加 Skill",
        action: createSkill
      };
    default:
      return null;
  }
});

function approvalKey(item) {
  return item?.approvalRequestId || item?.id || "";
}

function upsertApproval(detail) {
  const key = approvalKey(detail);
  if (!key) return;
  const index = approvals.value.findIndex((item) => approvalKey(item) === key);
  if (index >= 0) approvals.value[index] = { ...approvals.value[index], ...detail };
  else approvals.value.unshift(detail);
}

function extractApprovalId(content) {
  const matched = String(content || "").match(/([0-9a-fA-F-]{8,})$/);
  return matched?.[1] || "";
}

function escapeHtml(value) {
  return String(value || "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function renderComposerHtml(message) {
  let html = escapeHtml(message).replace(/\n/g, "<br>");
  for (const reference of form.references) {
    const token = escapeHtml(`{${reference.label}}`);
    html = html.replaceAll(
      token,
      `<span class="composer-token" contenteditable="false">${token}</span>`
    );
  }
  return html;
}

function moveCaretToEnd(element) {
  const selection = window.getSelection();
  if (!selection) return;
  const range = document.createRange();
  range.selectNodeContents(element);
  range.collapse(false);
  selection.removeAllRanges();
  selection.addRange(range);
}

function syncComposerEditor(moveCaret = false) {
  if (!composerEditor.value) return;
  composerEditor.value.innerHTML = renderComposerHtml(form.message || "");
  if (moveCaret) {
    moveCaretToEnd(composerEditor.value);
  }
}

function syncMessageFromComposer() {
  form.message = composerEditor.value?.innerText?.replace(/\u00a0/g, " ") || "";
}

function updateReferencePicker() {
  syncMessageFromComposer();
}

function replaceTriggerText(label) {
  const triggerIndex = mentionPicker.triggerIndex >= 0
    ? mentionPicker.triggerIndex
    : form.message.lastIndexOf("{");
  if (triggerIndex >= 0) {
    const prefix = form.message.slice(0, triggerIndex);
    const suffix = form.message.slice(triggerIndex + 1);
    form.message = `${prefix}{${label}}${suffix}`;
  }
  mentionPicker.triggerIndex = -1;
  syncComposerEditor(true);
}

function handleComposerKeyup(event) {
  syncMessageFromComposer();
  if (event.key === "{") {
    mentionPicker.visible = true;
    mentionPicker.query = "";
    mentionPicker.triggerIndex = form.message.lastIndexOf("{");
    return;
  }
  if (event.key === "Escape") {
    mentionPicker.visible = false;
    mentionPicker.query = "";
    mentionPicker.triggerIndex = -1;
    return;
  }
  if (mentionPicker.visible) {
    updateReferencePicker();
  }
}

function addReference(option) {
  if (form.references.some((item) => item.type === option.type && item.id === option.id)) {
    replaceTriggerText(option.label);
    mentionPicker.visible = false;
    mentionPicker.query = "";
    mentionPicker.triggerIndex = -1;
    return;
  }
  form.references.push({
    type: option.type,
    id: option.id,
    label: option.label
  });
  replaceTriggerText(option.label);
  mentionPicker.visible = false;
  mentionPicker.query = "";
  mentionPicker.triggerIndex = -1;
}

function removeReference(reference) {
  form.references = form.references.filter((item) => !(item.type === reference.type && item.id === reference.id));
}

function openFilePicker() {
  fileInput.value?.click();
}

async function handleAttachmentChange(event) {
  const files = Array.from(event.target.files || []);
  for (const file of files) {
    if (file.size > 200 * 1024) {
      state.error = `文件过大: ${file.name}，当前仅支持 200KB 以内文本文件`;
      continue;
    }
    const content = await file.text();
    form.attachments.push({
      name: file.name,
      contentType: file.type || "text/plain",
      content
    });
  }
  event.target.value = "";
}

function removeAttachment(attachment) {
  form.attachments = form.attachments.filter((item) => item !== attachment);
}

function appendMessageText(value) {
  const text = String(value || "").trim();
  if (!text) return;
  const current = (form.message || "").trim();
  form.message = current ? `${current}\n${text}` : text;
  syncComposerEditor(true);
}

function presetDocumentRequest(outputType) {
  const templates = {
    markdown: "请基于当前会话上下文、已有数据和图表，生成一份结构化 Markdown 报告，并提供可下载文件。",
    word: "请基于当前会话上下文、已有数据和图表，生成一份正式 Word 报告，并提供可下载文件。",
    excel: "请基于当前会话上下文和已有查询结果，生成一份可下载的 Excel 文件，保留结构化表格数据。",
    ppt: "请基于当前会话上下文、已有数据和图表，生成一份用于汇报的 PPT，并提供可下载文件。"
  };
  appendMessageText(templates[outputType]);
}

function formatSessionTime(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const now = new Date();
  const sameYear = date.getFullYear() === now.getFullYear();
  const sameDay = sameYear
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
  const hh = `${date.getHours()}`.padStart(2, "0");
  const mm = `${date.getMinutes()}`.padStart(2, "0");
  if (sameDay) {
    return `${hh}:${mm}`;
  }
  const month = `${date.getMonth() + 1}`.padStart(2, "0");
  const day = `${date.getDate()}`.padStart(2, "0");
  return sameYear ? `${month}-${day} ${hh}:${mm}` : `${date.getFullYear()}-${month}-${day}`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function approvalForEvent(event) {
  const id = extractApprovalId(event?.content);
  return approvals.value.find((item) => approvalKey(item) === id) || null;
}

async function bootstrap() {
  agents.value = await listAgents();
  llms.value = await listLlms();
  if (!form.agentId && agents.value.length > 0) {
    form.agentId = agents.value[0].agentId;
    searchForm.agentId = agents.value[0].agentId;
  }
  if (!form.llmConfigId) {
    form.llmConfigId = llms.value.find((item) => item.defaultConfig)?.configId || llms.value[0]?.configId || "";
  }
  await refreshRecentSessions();
  await refreshApprovals();
}

function pushEvent(event) {
  state.events.push({
    ...event,
    id: `${event.type}-${state.events.length + 1}`
  });

  if (event.type === "RUN_STARTED") {
    state.liveAssistant.runId = event.runId || state.currentRunId;
    state.liveAssistant.content = "";
  }

  if (event.type === "TOKEN_DELTA") {
    state.liveAssistant.runId = event.runId || state.currentRunId;
    state.liveAssistant.content += event.content || "";
  }

  if (event.type === "RUN_COMPLETED" && state.liveAssistant.runId && state.liveAssistant.content) {
    appendPendingMessage({
      id: `pending-assistant-${event.runId || state.currentRunId}`,
      runId: event.runId || state.currentRunId,
      role: "assistant",
      messageType: "text",
      content: state.liveAssistant.content,
      toolName: null,
      toolArgsJson: null,
      toolResultJson: null,
      seqNo: (transcriptMessages.value.at(-1)?.seqNo || 0) + 1,
      createdAt: new Date().toISOString()
    });
    state.liveAssistant.runId = "";
    state.liveAssistant.content = "";
  }

  if (event.type === "RUN_FAILED") {
    state.liveAssistant.runId = "";
    state.liveAssistant.content = "";
  }

  if (event.type === "APPROVAL_REQUIRED") {
    const approvalId = extractApprovalId(event.content);
    if (approvalId) {
      upsertApproval({
        id: approvalId,
        status: "PENDING",
        reason: event.content
      });
      loadApproval(approvalId);
    }
  }

  if (["TOOL_COMPLETED", "APPROVAL_REQUIRED", "RUN_COMPLETED", "RUN_FAILED"].includes(event.type)) {
    scheduleSessionRefresh(250);
  }

  if (["RUN_STATUS", "TOOL_COMPLETED", "RUN_COMPLETED", "RUN_FAILED"].includes(event.type)) {
    scheduleRunRefresh(250);
  }
}

function reconcileLiveAssistant() {
  const runId = state.liveAssistant.runId;
  if (!runId || !state.liveAssistant.content) {
    return;
  }
  if (hasAssistantMessageForRun(state.session, runId)) {
    state.liveAssistant.runId = "";
    state.liveAssistant.content = "";
  }
}

function syncPendingMessagesWithSession() {
  if (!state.pendingMessages.length || !state.session?.messages?.length) {
    return;
  }
  state.pendingMessages = state.pendingMessages.filter((pending) =>
    !state.session.messages.some((message) =>
      message.runId === pending.runId
      && message.role === pending.role
      && message.content === pending.content
    )
  );
}

function appendPendingMessage(message) {
  state.pendingMessages.push(message);
}

function pushSystemEvent(type, runId, content) {
  if (!runId) return;
  pushEvent({
    type,
    runId,
    content,
    timestamp: new Date().toISOString()
  });
}

function syncStateFromRoute() {
  const name = String(route.name || "chat");
  activeMenu.value = menuItems.some((item) => item.id === name) ? name : "chat";
  const sessionId = typeof route.params.sessionId === "string" ? route.params.sessionId : "";
  if (activeMenu.value === "chat") {
    form.sessionId = sessionId;
    searchForm.sessionId = sessionId;
  }
  const agentId = typeof route.query.agentId === "string" ? route.query.agentId : "";
  if (agentId) {
    form.agentId = agentId;
    searchForm.agentId = agentId;
    state.resolvedAgentId = agentId;
  }
}

function navigateToMenu(menu, options = {}) {
  const query = {};
  const agentId = options.agentId ?? state.resolvedAgentId ?? form.agentId;
  if (agentId) {
    query.agentId = agentId;
  }
  if (menu === "chat") {
    router.push({
      name: "chat",
      params: {
        sessionId: options.sessionId ?? form.sessionId ?? undefined
      },
      query
    });
    return;
  }
  router.push({ name: menu, query });
}

function parseToolEventContent(content) {
  const text = String(content || "");
  const separatorIndex = text.indexOf("\n{");
  if (separatorIndex < 0) {
    return { summary: text, payload: "" };
  }
  return {
    summary: text.slice(0, separatorIndex).trim(),
    payload: text.slice(separatorIndex + 1).trim()
  };
}

function parseJson(value) {
  try {
    return value ? JSON.parse(value) : null;
  } catch {
    return null;
  }
}

async function refreshRecentSessions() {
  try {
    recentSessions.value = await listSessions(state.resolvedAgentId || form.agentId || undefined);
  } catch (error) {
    state.error = error.message;
  }
}

function closeContextMenu() {
  contextMenu.visible = false;
  contextMenu.session = null;
}

function clearStream() {
  if (currentStream.value?.close) {
    currentStream.value.close();
  }
  if (currentStream.value instanceof WebSocket) {
    currentStream.value.close();
  }
  currentStream.value = null;
}

function scheduleSessionRefresh(delay = 300) {
  if (sessionRefreshTimer) {
    clearTimeout(sessionRefreshTimer);
  }
  sessionRefreshTimer = setTimeout(() => {
    sessionRefreshTimer = null;
    refreshSession();
    refreshRecentSessions();
  }, delay);
}

function scheduleRunRefresh(delay = 300) {
  if (runRefreshTimer) {
    clearTimeout(runRefreshTimer);
  }
  runRefreshTimer = setTimeout(() => {
    runRefreshTimer = null;
    refreshRun();
  }, delay);
}

async function runChat() {
  if (!hasAvailableLlms.value) {
    state.error = llmWarning.value;
    return;
  }
  loading.sending = true;
  state.error = "";
  clearStream();
  try {
    const outgoingMessage = form.message;
    const accepted = await sendChat({
      sessionId: form.sessionId || undefined,
      userId: form.userId || undefined,
      agentId: form.agentId || undefined,
      llmConfigId: form.llmConfigId || undefined,
      message: outgoingMessage,
      references: form.references,
      attachments: form.attachments
    });

    form.sessionId = accepted.sessionId;
    form.message = "";
    form.references = [];
    form.attachments = [];
    mentionPicker.visible = false;
    mentionPicker.query = "";
    mentionPicker.triggerIndex = -1;
    syncComposerEditor();
    state.currentRunId = accepted.runId;
    state.resolvedAgentId = accepted.agentId;
    state.liveAssistant.runId = accepted.runId;
    state.liveAssistant.content = "";
    searchForm.sessionId = accepted.sessionId;
    searchForm.runId = accepted.runId;
    searchForm.agentId = accepted.agentId;
    state.events = [];
    appendPendingMessage({
      id: `pending-user-${accepted.runId}`,
      runId: accepted.runId,
      role: "user",
      messageType: "text",
      content: outgoingMessage,
      toolName: null,
      toolArgsJson: null,
      toolResultJson: null,
      seqNo: (transcriptMessages.value.at(-1)?.seqNo || 0) + 1,
      createdAt: new Date().toISOString()
    });
    await refreshRecentSessions();

    const streamPayload = {
      sessionId: accepted.sessionId,
      agentId: accepted.agentId,
      llmConfigId: accepted.llmConfigId || form.llmConfigId || undefined,
      userId: form.userId || "anonymous",
      message: outgoingMessage,
      runId: accepted.runId
    };

    if (transport.value === "websocket") {
      currentStream.value = createWebSocketStream({
        ...streamPayload,
        onEvent: (event) => {
          pushEvent(event);
        },
        onClose: () => {
          currentStream.value = null;
          refreshRun();
          refreshSession();
          refreshMemories();
          refreshRecentSessions();
        },
        onError: () => {
          state.error = "WebSocket 流中断";
        }
      });
    } else {
      currentStream.value = createSseStream({
        ...streamPayload,
        onEvent: (event) => {
          pushEvent(event);
          if (event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED") {
            refreshRun();
            refreshSession();
            refreshMemories();
            refreshRecentSessions();
          }
        },
        onError: () => {
          state.error = "SSE 流中断";
          refreshRun();
          refreshSession();
          refreshMemories();
          refreshRecentSessions();
        }
      });
    }

    navigateToMenu("chat", { sessionId: accepted.sessionId, agentId: accepted.agentId });
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.sending = false;
  }
}

async function refreshSession() {
  if (!form.sessionId) return;
  loading.session = true;
  try {
    state.session = await getSession(form.sessionId);
    syncPendingMessagesWithSession();
    reconcileLiveAssistant();
    await refreshRecentSessions();
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.session = false;
  }
}

async function renameSessionTitle(session) {
  closeContextMenu();
  titleModal.visible = true;
  titleModal.sessionId = session.sessionId;
  titleModal.value = session.title || "";
}

async function submitSessionTitle() {
  try {
    const detail = await renameSession(titleModal.sessionId, titleModal.value);
    if (form.sessionId === titleModal.sessionId) {
      state.session = detail;
    }
    await refreshRecentSessions();
    closeTitleModal();
  } catch (error) {
    state.error = error.message;
  }
}

function closeTitleModal() {
  titleModal.visible = false;
  titleModal.sessionId = "";
  titleModal.value = "";
}

function openSessionMenu(event, session) {
  contextMenu.visible = true;
  contextMenu.x = event.clientX;
  contextMenu.y = event.clientY;
  contextMenu.session = session;
}

async function removeSession(session) {
  closeContextMenu();
  deleteModal.visible = true;
  deleteModal.session = session;
}

function closeDeleteModal() {
  deleteModal.visible = false;
  deleteModal.session = null;
}

async function confirmDeleteSession() {
  const session = deleteModal.session;
  if (!session) {
    return;
  }
  try {
    await deleteSession(session.sessionId);
    if (form.sessionId === session.sessionId) {
      form.sessionId = "";
      state.session = null;
      state.run = null;
      state.currentRunId = "";
      state.events = [];
      state.pendingMessages = [];
      state.liveAssistant.runId = "";
      state.liveAssistant.content = "";
    }
    await refreshRecentSessions();
    closeDeleteModal();
  } catch (error) {
    state.error = error.message;
  }
}

async function refreshRun() {
  if (!state.currentRunId) return;
  loading.run = true;
  try {
    state.run = await getRun(state.currentRunId);
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.run = false;
  }
}

async function refreshMemories() {
  const agentId = state.resolvedAgentId || form.agentId;
  if (!agentId) return;
  loading.memories = true;
  try {
    state.memories = await getMemories(agentId);
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.memories = false;
  }
}

async function refreshApprovals() {
  loading.approval = true;
  try {
    approvals.value = await listApprovals({
      agentId: state.resolvedAgentId || form.agentId || undefined,
      sessionId: form.sessionId || undefined
    });
  } catch (error) {
    if (!String(error.message || "").includes("/api/approvals") && !String(error.message || "").includes("404")) {
      state.error = error.message;
    }
  } finally {
    loading.approval = false;
  }
}

async function refreshCatalog() {
  const agentId = state.resolvedAgentId || form.agentId;
  if (!agentId) return;
  loading.catalog = true;
  try {
    const [knowledge, tools, skills] = await Promise.all([
      listKnowledgeEntries(agentId),
      listToolDefinitions(agentId),
      listSkillDefinitions(agentId)
    ]);
    state.catalog.knowledge = knowledge;
    state.catalog.tools = tools;
    state.catalog.skills = skills;
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.catalog = false;
  }
}

async function runSearch() {
  if (!searchForm.q) return;
  loading.search = true;
  try {
    state.search = await search(searchForm);
    navigateToMenu("search");
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.search = false;
  }
}

async function loadApproval(id) {
  loading.approval = true;
  try {
    const detail = await getApproval(id);
    upsertApproval(detail);
    return detail;
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.approval = false;
  }
}

function sessionMessageCount(session) {
  return Array.isArray(session?.messages) ? session.messages.length : 0;
}

function hasAssistantMessageForRun(session, runId) {
  return Array.isArray(session?.messages)
    && session.messages.some((message) => message.runId === runId && message.role === "assistant" && message.content);
}

async function settleApprovalView(id, decision) {
  const baselineMessageCount = sessionMessageCount(state.session);
  const detail = await loadApproval(id);
  const runId = detail?.runId || state.currentRunId;
  const sessionId = detail?.sessionId || form.sessionId;
  if (runId) {
    state.currentRunId = runId;
    searchForm.runId = runId;
  }
  if (sessionId) {
    form.sessionId = sessionId;
    searchForm.sessionId = sessionId;
  }
  navigateToMenu("chat", { sessionId, agentId: detail?.agentId || state.resolvedAgentId || form.agentId });
  if (decision === "approve") {
    pushSystemEvent("RUN_STATUS", runId, "审批已通过，继续执行中");
  } else {
    pushSystemEvent("RUN_STATUS", runId, "审批已拒绝");
  }
  let lastObservedRunStatus = state.run?.status || "";

  for (let attempt = 0; attempt < 20; attempt += 1) {
    const [approvalDetail, runDetail, sessionDetail] = await Promise.all([
      loadApproval(id),
      runId ? getRun(runId).catch(() => null) : Promise.resolve(null),
      sessionId ? getSession(sessionId).catch(() => null) : Promise.resolve(null)
    ]);

    if (runDetail) {
      state.run = runDetail;
    }
    if (sessionDetail) {
      state.session = sessionDetail;
    }

    await refreshRecentSessions();

    const approvalStatus = approvalDetail?.status || "";
    const runStatus = state.run?.status || "";
    const currentMessageCount = sessionMessageCount(state.session);
    const assistantReturned = runId ? hasAssistantMessageForRun(state.session, runId) : false;
    const sessionAdvanced = currentMessageCount > baselineMessageCount;

    if (runStatus && runStatus !== lastObservedRunStatus) {
      pushSystemEvent("RUN_STATUS", runId, `当前执行状态: ${runStatus}`);
      lastObservedRunStatus = runStatus;
    }

    if (decision === "reject") {
      if (approvalStatus === "REJECTED" || decisionTerminal(runStatus)) {
        break;
      }
    } else if (approvalStatus === "APPROVED" && (assistantReturned || sessionAdvanced || decisionTerminal(runStatus))) {
      break;
    }

    await sleep(800);
  }

  await refreshApprovals();
  await refreshRun();
  await refreshSession();
  await refreshMemories();
}

function decisionTerminal(status) {
  return [
    "COMPLETED",
    "FAILED",
    "REJECTED"
  ].includes(status);
}

async function decideApproval(id, decision) {
  loading.approval = true;
  try {
    if (decision === "approve") {
      upsertApproval({ id, status: "APPROVING" });
      await approve(id);
      upsertApproval({ id, status: "APPROVED" });
    } else {
      upsertApproval({ id, status: "REJECTING" });
      await reject(id);
      upsertApproval({ id, status: "REJECTED" });
    }
    await settleApprovalView(id, decision);
  } catch (error) {
    state.error = error.message;
    await loadApproval(id);
  } finally {
    loading.approval = false;
  }
}

onMounted(async () => {
  syncStateFromRoute();
  await bootstrap();
  if (form.sessionId) {
    await refreshSession();
    await refreshApprovals();
  }
  await refreshMemories();
  await refreshCatalog();
  window.addEventListener("click", closeContextMenu);
  syncComposerEditor();
});

watch(
  () => route.fullPath,
  async () => {
    const previousSessionId = form.sessionId;
    const previousMenu = activeMenu.value;
    syncStateFromRoute();
    if (activeMenu.value !== previousMenu) {
      closeCatalogModal();
    }
    if (activeMenu.value === "chat" && form.sessionId && form.sessionId !== previousSessionId) {
      state.currentRunId = "";
      state.pendingMessages = [];
      state.liveAssistant.runId = "";
      state.liveAssistant.content = "";
      await refreshSession();
      await refreshApprovals();
    }
    if (activeMenu.value !== "chat" && previousMenu === "chat") {
      clearStream();
    }
  }
);

watch(
  () => state.resolvedAgentId || form.agentId,
  async (agentId, previous) => {
    if (!agentId || agentId === previous) {
      return;
    }
    searchForm.agentId = agentId;
    await refreshCatalog();
    await refreshMemories();
    await refreshRecentSessions();
  }
);

function openSession(session) {
  form.sessionId = session.sessionId;
  form.agentId = session.agentId;
  state.resolvedAgentId = session.agentId;
  searchForm.sessionId = session.sessionId;
  searchForm.agentId = session.agentId;
  state.currentRunId = "";
  state.pendingMessages = [];
  state.liveAssistant.runId = "";
  state.liveAssistant.content = "";
  navigateToMenu("chat", { sessionId: session.sessionId, agentId: session.agentId });
  refreshSession();
  refreshApprovals();
}

function createNewSession() {
  form.sessionId = "";
  form.message = "";
  form.references = [];
  form.attachments = [];
  searchForm.sessionId = "";
  searchForm.runId = "";
  state.currentRunId = "";
  state.events = [];
  state.pendingMessages = [];
  state.session = null;
  state.run = null;
  state.liveAssistant.runId = "";
  state.liveAssistant.content = "";
  syncComposerEditor();
  navigateToMenu("chat", { sessionId: undefined });
}

function toggleInspector(section) {
  inspector[section] = !inspector[section];
}

function resetKnowledgeForm() {
  Object.assign(knowledgeForm, { id: "", title: "", content: "", contentType: "markdown", source: "database", tagsJson: "[]", enabled: true });
}

function resetToolForm() {
  Object.assign(toolForm, { id: "", toolName: "", displayName: "", description: "", schemaJson: "{}", toolType: "builtin", configJson: "{}", enabled: true, approvalRequired: false });
}

function resetSkillForm() {
  Object.assign(skillForm, { id: "", skillName: "", description: "", promptTemplate: "", configJson: "{}", enabled: true });
}

function openCatalogModal(type, mode = "create") {
  catalogModal.visible = true;
  catalogModal.type = type;
  catalogModal.mode = mode;
}

function closeCatalogModal() {
  catalogModal.visible = false;
  catalogModal.type = "";
  catalogModal.mode = "create";
}

function createKnowledge() {
  resetKnowledgeForm();
  openCatalogModal("knowledge", "create");
}

function editKnowledge(item) {
  Object.assign(knowledgeForm, item);
  openCatalogModal("knowledge", "edit");
}

async function submitKnowledge() {
  try {
    await saveKnowledgeEntry({ ...knowledgeForm, agentId: state.resolvedAgentId || form.agentId });
    resetKnowledgeForm();
    closeCatalogModal();
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

async function removeKnowledge(id) {
  try {
    await deleteKnowledgeEntry(id);
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

function createTool() {
  resetToolForm();
  openCatalogModal("tools", "create");
}

function editTool(item) {
  Object.assign(toolForm, item);
  openCatalogModal("tools", "edit");
}

async function submitTool() {
  try {
    await saveToolDefinition({ ...toolForm, agentId: state.resolvedAgentId || form.agentId });
    resetToolForm();
    closeCatalogModal();
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

async function removeTool(id) {
  try {
    await deleteToolDefinition(id);
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

function createSkill() {
  resetSkillForm();
  openCatalogModal("skills", "create");
}

function editSkill(item) {
  Object.assign(skillForm, item);
  openCatalogModal("skills", "edit");
}

async function submitSkill() {
  try {
    await saveSkillDefinition({ ...skillForm, agentId: state.resolvedAgentId || form.agentId });
    resetSkillForm();
    closeCatalogModal();
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

async function removeSkill(id) {
  try {
    await deleteSkillDefinition(id);
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

function eventTitle(type) {
  switch (type) {
    case "TOOL_REQUESTED":
      return "Tool Requested";
    case "TOOL_STARTED":
      return "Tool Started";
    case "TOOL_COMPLETED":
      return "Tool Completed";
    case "APPROVAL_REQUIRED":
      return "Approval Required";
    case "RUN_COMPLETED":
      return "Run Completed";
    case "RUN_FAILED":
      return "Run Failed";
    default:
      return type;
  }
}

function eventTone(type) {
  if (type === "APPROVAL_REQUIRED" || type === "RUN_FAILED") return "warning";
  if (type === "TOOL_COMPLETED" || type === "RUN_COMPLETED") return "success";
  return "neutral";
}
</script>

<template>
  <div class="workspace-shell">
    <aside class="left-rail">
      <div class="brand-block">
        <p class="eyebrow">Java Claw</p>
        <h1>Operator Console</h1>
        <p class="brand-copy">左侧决定工作模式，中间完成操作，右侧持续观察系统状态。</p>
      </div>

      <nav class="menu-stack">
        <button
          v-for="item in menuItems"
          :key="item.id"
          class="menu-card"
          :class="{ active: activeMenu === item.id }"
          @click="navigateToMenu(item.id)"
        >
          <strong>{{ item.label }}</strong>
          <span>{{ item.hint }}</span>
        </button>
      </nav>

      <div class="rail-section">
        <div class="rail-head">
          <h3>Agents</h3>
        </div>
        <div class="agent-list">
          <button
            v-for="agent in agents"
            :key="agent.agentId"
            class="agent-pill"
            :class="{ active: (state.resolvedAgentId || form.agentId) === agent.agentId }"
            @click="form.agentId = agent.agentId; state.resolvedAgentId = agent.agentId; refreshRecentSessions()"
          >
            {{ agent.displayName }}
          </button>
        </div>
      </div>

      <div class="rail-section">
        <div class="rail-head">
          <h3>最近会话</h3>
          <button class="rail-action" @click="createNewSession">新建会话</button>
        </div>
        <div class="session-list">
          <button
            v-for="session in recentSessions"
            :key="session.sessionId"
            class="session-link"
            @click="openSession(session)"
            @contextmenu.prevent="openSessionMenu($event, session)"
          >
            <div class="session-link-head">
              <strong>{{ session.title || session.sessionId }}</strong>
              <time class="session-link-time">{{ formatSessionTime(session.updatedAt || session.createdAt) }}</time>
            </div>
            <span>{{ session.agentId }} · {{ session.sessionId }}</span>
          </button>
          <div v-if="!recentSessions.length" class="empty-state compact">运行后会在这里保留最近会话。</div>
        </div>
      </div>

      <div class="left-footer">
        <div class="mini-stat">
          <span>Agents</span>
          <strong>{{ agents.length }}</strong>
        </div>
        <div class="mini-stat">
          <span>Transport</span>
          <strong>{{ transport.toUpperCase() }}</strong>
        </div>
        <div class="mini-stat">
          <span>Current</span>
          <strong>{{ activeAgentLabel }}</strong>
        </div>
      </div>
    </aside>

    <section class="center-stage" :class="{ 'chat-mode': activeMenu === 'chat' }">
      <header v-if="activeMenu !== 'chat'" class="stage-header">
        <div>
          <p class="eyebrow">Workspace</p>
          <h2>{{ menuItems.find((item) => item.id === activeMenu)?.label }}</h2>
        </div>
        <div v-if="stageAction" class="stage-header-actions">
          <span>{{ stageAction.count }}</span>
          <button class="primary rail-action" @click="stageAction.action()">{{ stageAction.label }}</button>
        </div>
      </header>

      <section v-if="activeMenu === 'chat'" class="workspace-panel chat-workspace">
        <div class="chat-session-tag">
          <strong class="chat-session-title">{{ currentSessionTitle }}</strong>
          <span class="chat-session-id">{{ form.sessionId || "auto" }}</span>
        </div>
        <div class="chat-config-bar floating">
          <label class="bar-field bar-field-plain">
            <select v-model="form.agentId" data-testid="agent-select">
              <option value="">自动路由</option>
              <option v-for="agent in agents" :key="agent.agentId" :value="agent.agentId">
                {{ agent.displayName }} · {{ agent.agentId }}
              </option>
            </select>
          </label>
          <label class="bar-field bar-field-plain">
            <select v-model="form.llmConfigId" data-testid="llm-select">
              <option value="">自动默认</option>
              <option v-for="llm in llms" :key="llm.configId" :value="llm.configId">
                {{ llm.displayName }} · {{ llm.model }}
              </option>
            </select>
          </label>
          <label class="bar-field bar-field-compact bar-field-plain">
            <select v-model="transport">
              <option value="sse">SSE</option>
              <option value="websocket">WebSocket</option>
            </select>
          </label>
        </div>

        <p v-if="llmWarning" class="error">{{ llmWarning }}</p>
        <p v-if="state.error" class="error">{{ state.error }}</p>

        <div class="conversation-panel chat-panel">
          <div v-if="transientStatusHint" class="status-hint">
            {{ transientStatusHint }}
          </div>
          <div class="conversation-stream">
            <section v-for="group in groupedRunTimeline" :key="group.runId" class="run-group">
              <div class="run-divider">
                <span>Run</span>
                <strong>{{ group.runId }}</strong>
              </div>
              <template v-for="block in group.blocks" :key="block.key">
                <article v-if="block.kind === 'message'" class="bubble" :class="block.message.role">
                  <div class="bubble-meta">
                    <strong>{{ block.message.role }}</strong>
                    <span>{{ block.message.messageType }} · #{{ block.message.seqNo }}</span>
                  </div>
                  <p v-if="shouldShowMessageText(block.message, group.runId)">{{ displayMessageContent(block.message, group.runId) }}</p>
                  <div v-if="block.message.toolName || block.message.toolResultJson" class="inline-tool">
                    <span v-if="block.message.toolName">tool: {{ block.message.toolName }}</span>
                  </div>
                  <pre v-if="shouldShowToolArgs(block.message, group.runId) && block.message.toolArgsJson" class="tool-payload">{{ block.message.toolArgsJson }}</pre>
                  <template v-if="parseJson(block.message.toolResultJson)?.displayType === 'table'">
                    <div class="render-block table-block">
                      <div class="render-block-head">
                        <strong>{{ renderBlockTitle(block.message) }}</strong>
                        <span>{{ block.message.toolName || "table.render" }}</span>
                      </div>
                      <table>
                        <thead>
                          <tr>
                            <th v-for="column in parseJson(block.message.toolResultJson)?.columns || []" :key="column">{{ column }}</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="(row, index) in parseJson(block.message.toolResultJson)?.rows || []" :key="index">
                            <td v-for="column in parseJson(block.message.toolResultJson)?.columns || []" :key="column">{{ row[column] }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </template>
                  <template v-else-if="parseJson(block.message.toolResultJson)?.displayType === 'echarts'">
                    <div class="render-block chart-block">
                      <div class="render-block-head">
                        <div class="render-block-heading">
                          <strong>{{ renderBlockTitle(block.message) }}</strong>
                          <span>{{ block.message.toolName || "chart.echarts" }}</span>
                        </div>
                        <div class="chart-switcher">
                          <button :class="{ active: selectedChartType(block.message) === 'bar' }" @click="setChartType(block.message, 'bar')">柱状</button>
                          <button :class="{ active: selectedChartType(block.message) === 'line' }" @click="setChartType(block.message, 'line')">折线</button>
                          <button :class="{ active: selectedChartType(block.message) === 'pie' }" @click="setChartType(block.message, 'pie')">饼图</button>
                        </div>
                      </div>
                      <div class="chart-meta">
                        <span>维度: {{ chartMeta(block.message).dimension }}</span>
                        <span>指标: {{ chartMeta(block.message).metrics.join(" / ") || "value" }}</span>
                        <span>数据点: {{ chartMeta(block.message).points }}</span>
                      </div>
                      <EchartsBlock :option="displayChartOption(block.message)" />
                    </div>
                  </template>
                  <template v-else-if="artifactPayload(block.message)">
                    <div class="render-block artifact-block">
                      <div class="render-block-head">
                        <div class="render-block-heading">
                          <strong>{{ artifactPayload(block.message)?.name || renderBlockTitle(block.message) }}</strong>
                          <span>{{ artifactPayload(block.message)?.contentType || block.message.toolName }}</span>
                        </div>
                        <a class="download-link" :href="artifactDownloadUrl(artifactPayload(block.message)?.artifactId)" target="_blank" rel="noopener">下载</a>
                      </div>
                    </div>
                  </template>
                  <pre v-else-if="block.message.toolResultJson" class="tool-payload">{{ block.message.toolResultJson }}</pre>
                </article>
                <article v-else-if="block.kind === 'live'" class="bubble assistant live">
                  <div class="bubble-meta">
                    <strong>assistant</strong>
                    <span>streaming</span>
                  </div>
                  <p>{{ block.content }}</p>
                </article>
                <article
                  v-else
                  class="execution-block"
                  :class="eventTone(block.event.type)"
                >
                  <div class="bubble-meta">
                    <strong>{{ eventTitle(block.event.type) }}</strong>
                    <span>{{ block.event.timestamp }}</span>
                  </div>
                  <template v-if="block.event.type === 'APPROVAL_REQUIRED'">
                    <p>{{ approvalForEvent(block.event)?.reason || block.event.content }}</p>
                    <div class="inline-approval-card">
                      <div class="inline-approval-meta">
                        <strong>{{ approvalForEvent(block.event)?.toolName || "待审批工具" }}</strong>
                        <span>{{ approvalForEvent(block.event)?.status || "PENDING" }}</span>
                      </div>
                      <p v-if="approvalForEvent(block.event)?.argumentsJson" class="inline-approval-args">{{ approvalForEvent(block.event)?.argumentsJson }}</p>
                      <div class="inline-approval-actions">
                        <button @click="loadApproval(approvalForEvent(block.event)?.approvalRequestId || approvalForEvent(block.event)?.id || extractApprovalId(block.event.content))">刷新</button>
                        <button
                          class="primary"
                          :disabled="loading.approval || ['APPROVED', 'REJECTED', 'APPROVING', 'REJECTING'].includes(approvalForEvent(block.event)?.status)"
                          @click="decideApproval(approvalForEvent(block.event)?.approvalRequestId || approvalForEvent(block.event)?.id || extractApprovalId(block.event.content), 'approve')"
                        >
                          {{ approvalForEvent(block.event)?.status === "APPROVING" ? "批准中..." : "批准" }}
                        </button>
                        <button
                          :disabled="loading.approval || ['APPROVED', 'REJECTED', 'APPROVING', 'REJECTING'].includes(approvalForEvent(block.event)?.status)"
                          @click="decideApproval(approvalForEvent(block.event)?.approvalRequestId || approvalForEvent(block.event)?.id || extractApprovalId(block.event.content), 'reject')"
                        >
                          {{ approvalForEvent(block.event)?.status === "REJECTING" ? "拒绝中..." : "拒绝" }}
                        </button>
                      </div>
                    </div>
                  </template>
                  <template v-else-if="block.event.type === 'TOOL_COMPLETED'">
                    <p>{{ parseToolEventContent(block.event.content).summary }}</p>
                    <pre v-if="parseToolEventContent(block.event.content).payload" class="tool-payload">{{ parseToolEventContent(block.event.content).payload }}</pre>
                  </template>
                  <p v-else>{{ block.event.content }}</p>
                </article>
              </template>
            </section>
            <section v-if="!transcriptMessages.length && state.events.some((event) => !['RUN_STATUS', 'RUN_COMPLETED', 'TOOL_REQUESTED', 'TOOL_STARTED', 'TOOL_COMPLETED'].includes(event.type))" class="run-group">
              <div class="run-divider">
                <span>Run</span>
                <strong>{{ state.currentRunId || "current" }}</strong>
              </div>
              <article
                v-for="event in state.events.filter((item) => !['RUN_STATUS', 'RUN_COMPLETED', 'TOOL_REQUESTED', 'TOOL_STARTED', 'TOOL_COMPLETED'].includes(item.type))"
                :key="event.id"
                class="execution-block"
                :class="eventTone(event.type)"
              >
                <div class="bubble-meta">
                  <strong>{{ eventTitle(event.type) }}</strong>
                  <span>{{ event.timestamp }}</span>
                </div>
                <p>{{ event.content }}</p>
              </article>
            </section>
            <div v-if="!transcriptMessages.length && !state.events.length" class="empty-state">
              开始一个新对话。
            </div>
          </div>
        </div>

        <div class="composer-dock">
          <div v-if="form.references.length || form.attachments.length" class="composer-context">
            <button
              v-for="reference in form.references"
              :key="`${reference.type}-${reference.id}`"
              class="context-chip"
              @click="removeReference(reference)"
            >
              {{ reference.type }} · {{ reference.label }}
            </button>
            <button
              v-for="attachment in form.attachments"
              :key="attachment.name"
              class="context-chip attachment"
              @click="removeAttachment(attachment)"
            >
              文件 · {{ attachment.name }}
            </button>
          </div>
          <label class="dock-field">
            <span>Message</span>
            <div
              ref="composerEditor"
              class="composer-editor"
              contenteditable="true"
              data-placeholder="在这里描述任务、追问结果、或者要求 agent 调用工具"
              data-testid="message-input"
              @input="updateReferencePicker"
              @keyup="handleComposerKeyup"
            ></div>
          </label>
          <div class="dock-actions">
            <button @click="openFilePicker">上传文件</button>
            <button class="primary" :disabled="loading.sending || !form.message || !hasAvailableLlms" @click="runChat" data-testid="send-button">发送并开始执行</button>
            <button @click="refreshSession">刷新会话</button>
            <button @click="refreshRun">刷新运行</button>
            <button @click="refreshMemories">刷新记忆</button>
          </div>
          <div class="dock-actions dock-actions-secondary">
            <button @click="presetDocumentRequest('markdown')">导出 Markdown</button>
            <button @click="presetDocumentRequest('word')">导出 Word</button>
            <button @click="presetDocumentRequest('excel')">导出 Excel</button>
            <button @click="presetDocumentRequest('ppt')">导出 PPT</button>
          </div>
          <input ref="fileInput" type="file" multiple hidden @change="handleAttachmentChange" />
        </div>
      </section>

      <section v-if="activeMenu === 'search'" class="workspace-panel">
        <div class="compose-grid search-grid">
          <label>
            <span>Query</span>
            <input v-model="searchForm.q" placeholder="keyword" />
          </label>
          <label>
            <span>Agent</span>
            <input v-model="searchForm.agentId" placeholder="dev-agent" />
          </label>
          <label>
            <span>Session</span>
            <input v-model="searchForm.sessionId" />
          </label>
          <label>
            <span>Run</span>
            <input v-model="searchForm.runId" />
          </label>
        </div>
        <div class="action-row">
          <button class="primary" :disabled="loading.search || !searchForm.q" @click="runSearch">执行搜索</button>
        </div>
        <div class="search-columns">
          <div class="search-column">
            <h3>Messages</h3>
            <p v-for="item in state.search.messages" :key="item.id" class="result-card">{{ item.content }}</p>
          </div>
          <div class="search-column">
            <h3>Memories</h3>
            <p v-for="item in state.search.memories" :key="item.id" class="result-card">{{ item.content }}</p>
          </div>
          <div class="search-column">
            <h3>Artifacts</h3>
            <p v-for="item in state.search.artifacts" :key="item.id" class="result-card">{{ item.name }} · {{ item.path }}</p>
          </div>
        </div>
      </section>

      <section v-if="activeMenu === 'approval'" class="workspace-panel">
        <div class="panel-title">
          <h3>审批历史</h3>
          <span>{{ approvalHistory.length }} records</span>
        </div>
        <div class="approval-list">
          <article v-for="approval in approvalHistory" :key="approval.id || approval.approvalRequestId" class="approval-row">
            <div>
              <strong>{{ approval.toolName || approval.id }}</strong>
              <p>{{ approval.reason || approval.argumentsJson }}</p>
            </div>
            <div class="approval-actions">
              <span class="status-chip">{{ approval.status || "PENDING" }}</span>
              <button @click="loadApproval(approval.id || approval.approvalRequestId)">查看</button>
              <button
                class="primary"
                :disabled="loading.approval || approval.status !== 'PENDING'"
                @click="decideApproval(approval.id || approval.approvalRequestId, 'approve')"
              >
                批准
              </button>
              <button
                :disabled="loading.approval || approval.status !== 'PENDING'"
                @click="decideApproval(approval.id || approval.approvalRequestId, 'reject')"
              >
                拒绝
              </button>
            </div>
          </article>
          <div v-if="!approvalHistory.length" class="empty-state">当前没有审批记录。</div>
        </div>
      </section>

      <section v-if="activeMenu === 'memory'" class="workspace-panel">
        <div class="panel-title">
          <h3>长期记忆</h3>
          <span>{{ state.memories.length }} notes</span>
        </div>
        <div class="memory-grid">
          <article v-for="note in state.memories" :key="note.id" class="memory-note">
            <div class="bubble-meta">
              <strong>{{ note.source }}</strong>
              <span>{{ note.createdAt }}</span>
            </div>
            <p>{{ note.content }}</p>
          </article>
          <div v-if="!state.memories.length" class="empty-state">当前 agent 还没有持久化 memory note。</div>
        </div>
      </section>

      <section v-if="activeMenu === 'knowledge'" class="workspace-panel catalog-page">
        <div class="catalog-list">
          <article v-for="item in state.catalog.knowledge" :key="item.id" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.title }}</strong>
                <span>{{ item.updatedAt }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="editKnowledge(item)">编辑</button>
                <button @click="removeKnowledge(item.id)">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">{{ item.content }}</p>
          </article>
          <div v-if="!state.catalog.knowledge.length" class="empty-state">当前没有知识条目。</div>
        </div>
      </section>

      <section v-if="activeMenu === 'tools'" class="workspace-panel catalog-page">
        <div class="catalog-list">
          <article v-for="item in state.catalog.tools" :key="item.id" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.displayName }} · {{ item.toolName }}</strong>
                <span>{{ item.updatedAt }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="editTool(item)">编辑</button>
                <button @click="removeTool(item.id)">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">{{ item.description }}</p>
          </article>
          <div v-if="!state.catalog.tools.length" class="empty-state">当前没有工具定义。</div>
        </div>
      </section>

      <section v-if="activeMenu === 'skills'" class="workspace-panel catalog-page">
        <div class="catalog-list">
          <article v-for="item in state.catalog.skills" :key="item.id" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.skillName }}</strong>
                <span>{{ item.updatedAt }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="editSkill(item)">编辑</button>
                <button @click="removeSkill(item.id)">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">{{ item.description }}</p>
          </article>
          <div v-if="!state.catalog.skills.length" class="empty-state">当前没有 Skill 定义。</div>
        </div>
      </section>
    </section>

    <aside class="right-rail">
      <section class="status-card">
        <button class="inspector-head" @click="toggleInspector('summary')">
          <h3>运行状态</h3>
          <span>{{ state.run?.status || "idle" }}</span>
        </button>
        <div v-if="inspector.summary" class="status-list">
          <div class="kv"><span>Run</span><strong data-testid="run-id">{{ state.currentRunId || "N/A" }}</strong></div>
          <div class="kv"><span>Session</span><strong data-testid="session-id">{{ form.sessionId || "N/A" }}</strong></div>
          <div class="kv"><span>Agent</span><strong data-testid="agent-id">{{ state.resolvedAgentId || form.agentId || "auto" }}</strong></div>
          <div class="kv"><span>LLM</span><strong data-testid="llm-model">{{ state.run?.llmModel || llms.find((item) => item.configId === form.llmConfigId)?.model || "default" }}</strong></div>
          <div class="kv"><span>Artifacts</span><strong>{{ state.run?.artifacts?.length || 0 }}</strong></div>
          <div class="kv"><span>Audits</span><strong>{{ state.run?.toolAudits?.length || 0 }}</strong></div>
          <div v-if="state.run?.artifacts?.length" class="artifact-links">
            <a
              v-for="artifact in state.run?.artifacts || []"
              :key="artifact.id"
              class="artifact-link"
              :href="artifactDownloadUrl(artifact.id)"
              target="_blank"
              rel="noopener"
            >
              {{ artifact.name }}
            </a>
          </div>
        </div>
      </section>

      <section class="status-card">
        <button class="inspector-head" @click="toggleInspector('timeline')">
          <h3>事件时间线</h3>
          <span>{{ recentEvents.length }}</span>
        </button>
        <div v-if="inspector.timeline" class="timeline">
          <article v-for="event in recentEvents" :key="event.id" class="timeline-item">
            <strong>{{ event.type }}</strong>
            <p>{{ event.content }}</p>
          </article>
        </div>
      </section>

      <section class="status-card">
        <button class="inspector-head" @click="toggleInspector('audit')">
          <h3>Tool Audit</h3>
          <span>{{ state.run?.toolAudits?.length || 0 }}</span>
        </button>
        <div v-if="inspector.audit" class="timeline">
          <article v-for="audit in state.run?.toolAudits || []" :key="audit.id" class="timeline-item">
            <strong>{{ audit.toolName }} · {{ audit.phase }}</strong>
            <p>{{ audit.reason || audit.resultSummary || audit.errorMessage }}</p>
          </article>
          <div v-if="!(state.run?.toolAudits?.length)" class="empty-state compact">暂无工具审计。</div>
        </div>
      </section>
    </aside>

    <div
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
    >
      <button @click="renameSessionTitle(contextMenu.session)">编辑标题</button>
      <button class="danger" @click="removeSession(contextMenu.session)">删除</button>
    </div>

    <div v-if="titleModal.visible" class="modal-backdrop" @click.self="closeTitleModal">
      <div class="modal-card">
        <div class="modal-head">
          <h3>编辑会话标题</h3>
          <button @click="closeTitleModal">关闭</button>
        </div>
        <label class="dock-field">
          <span>Title</span>
          <input v-model="titleModal.value" placeholder="输入新的会话标题" />
        </label>
        <div class="dock-actions">
          <button class="primary" @click="submitSessionTitle">保存</button>
          <button @click="closeTitleModal">取消</button>
        </div>
      </div>
    </div>

    <div v-if="deleteModal.visible" class="modal-backdrop" @click.self="closeDeleteModal">
      <div class="modal-card">
        <div class="modal-head">
          <h3>删除会话</h3>
          <button @click="closeDeleteModal">关闭</button>
        </div>
        <p>确认删除会话“{{ deleteModal.session?.title || deleteModal.session?.sessionId }}”？</p>
        <p class="modal-copy">这会删除该会话关联的消息、run、tool audit、artifact、approval 和 memory note。</p>
        <div class="dock-actions">
          <button class="danger-action" @click="confirmDeleteSession">确认删除</button>
          <button @click="closeDeleteModal">取消</button>
        </div>
      </div>
    </div>

    <div v-if="catalogModal.visible" class="modal-backdrop" @click.self="closeCatalogModal">
      <div class="modal-card catalog-modal">
        <div class="modal-head">
          <h3>
            {{
              catalogModal.type === "knowledge"
                ? (catalogModal.mode === "create" ? "添加知识" : "编辑知识")
                : catalogModal.type === "tools"
                  ? (catalogModal.mode === "create" ? "添加工具" : "编辑工具")
                  : (catalogModal.mode === "create" ? "添加 Skill" : "编辑 Skill")
            }}
          </h3>
          <button @click="closeCatalogModal">关闭</button>
        </div>

        <template v-if="catalogModal.type === 'knowledge'">
          <div class="compose-grid search-grid">
            <label><span>Title</span><input v-model="knowledgeForm.title" /></label>
            <label><span>Content Type</span><input v-model="knowledgeForm.contentType" /></label>
            <label><span>Source</span><input v-model="knowledgeForm.source" /></label>
            <label><span>Tags JSON</span><input v-model="knowledgeForm.tagsJson" /></label>
          </div>
          <label><span>Content</span><textarea v-model="knowledgeForm.content" rows="10"></textarea></label>
          <div class="dock-actions">
            <button class="primary" @click="submitKnowledge">保存知识</button>
            <button @click="closeCatalogModal">取消</button>
          </div>
        </template>

        <template v-else-if="catalogModal.type === 'tools'">
          <div class="compose-grid search-grid">
            <label><span>Tool Name</span><input v-model="toolForm.toolName" /></label>
            <label><span>Display Name</span><input v-model="toolForm.displayName" /></label>
            <label><span>Tool Type</span><input v-model="toolForm.toolType" /></label>
            <label><span>Approval</span><select v-model="toolForm.approvalRequired"><option :value="false">false</option><option :value="true">true</option></select></label>
          </div>
          <label><span>Description</span><textarea v-model="toolForm.description" rows="4"></textarea></label>
          <label><span>Schema JSON</span><textarea v-model="toolForm.schemaJson" rows="8"></textarea></label>
          <label><span>Config JSON</span><textarea v-model="toolForm.configJson" rows="6"></textarea></label>
          <div class="dock-actions">
            <button class="primary" @click="submitTool">保存工具</button>
            <button @click="closeCatalogModal">取消</button>
          </div>
        </template>

        <template v-else-if="catalogModal.type === 'skills'">
          <div class="compose-grid search-grid">
            <label><span>Skill Name</span><input v-model="skillForm.skillName" /></label>
            <label><span>Enabled</span><select v-model="skillForm.enabled"><option :value="true">true</option><option :value="false">false</option></select></label>
          </div>
          <label><span>Description</span><textarea v-model="skillForm.description" rows="4"></textarea></label>
          <label><span>Prompt Template</span><textarea v-model="skillForm.promptTemplate" rows="10"></textarea></label>
          <label><span>Config JSON</span><textarea v-model="skillForm.configJson" rows="6"></textarea></label>
          <div class="dock-actions">
            <button class="primary" @click="submitSkill">保存 Skill</button>
            <button @click="closeCatalogModal">取消</button>
          </div>
        </template>
      </div>
    </div>

    <div v-if="mentionPicker.visible" class="modal-backdrop" @click.self="mentionPicker.visible = false">
      <div class="modal-card reference-modal">
        <div class="modal-head">
          <h3>选择上下文引用</h3>
          <button @click="mentionPicker.visible = false">关闭</button>
        </div>
        <label class="dock-field">
          <span>搜索</span>
          <input v-model="mentionPicker.query" placeholder="搜索知识库、Skill、工具定义" />
        </label>
        <div class="reference-picker modal-list">
          <button
            v-for="option in referenceOptions"
            :key="`${option.type}-${option.id}`"
            class="reference-option"
            @click="addReference(option)"
          >
            <strong>{{ option.label }}</strong>
            <span>{{ option.type }} · {{ option.subtitle }}</span>
          </button>
          <div v-if="!referenceOptions.length" class="empty-state compact">没有匹配的知识库、Skill 或工具定义。</div>
        </div>
      </div>
    </div>
  </div>
</template>
