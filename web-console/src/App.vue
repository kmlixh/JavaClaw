<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import {
  approve,
  createSseStream,
  createWebSocketStream,
  getApproval,
  getMemories,
  getRun,
  getSession,
  listAgents,
  listLlms,
  listSessions,
  deleteSession,
  renameSession,
  reject,
  search,
  sendChat
} from "./api";

const agents = ref([]);
const llms = ref([]);
const approvals = ref([]);
const currentStream = ref(null);
const transport = ref("sse");
const activeMenu = ref("chat");
const compactControls = ref(true);
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
const recentSessions = ref([]);
const loading = reactive({
  sending: false,
  session: false,
  run: false,
  search: false,
  memories: false,
  approval: false
});

const form = reactive({
  sessionId: "",
  userId: "anonymous",
  agentId: "",
  llmConfigId: "",
  message: ""
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
  error: ""
});

const menuItems = [
  { id: "chat", label: "会话操作", hint: "发送消息与接收反馈" },
  { id: "search", label: "搜索检索", hint: "查询消息、记忆、artifact" },
  { id: "approval", label: "审批处理", hint: "处理高风险动作" },
  { id: "memory", label: "长期记忆", hint: "浏览 memory note" }
];

const recentEvents = computed(() => state.events.slice().reverse().slice(0, 18));
const transcriptMessages = computed(() => state.session?.messages || []);
const hasAvailableLlms = computed(() => llms.value.length > 0);
const llmWarning = computed(() => hasAvailableLlms.value
  ? ""
  : "后端当前没有加载到任何可用 LLM。请确认 agent-app 使用了正确的 profile，并且 llm_provider_config 表中存在 enabled=true 的配置。");
const groupedRunTimeline = computed(() => {
  const orderedRuns = [];
  const runMap = new Map();

  for (const message of transcriptMessages.value) {
    const runId = message.runId || "unknown";
    if (!runMap.has(runId)) {
      runMap.set(runId, []);
      orderedRuns.push(runId);
    }
    runMap.get(runId).push({
      kind: "message",
      key: `message-${message.id}`,
      message
    });
  }

  for (const event of state.events) {
    const runId = event.runId || state.currentRunId || "current";
    if (!runMap.has(runId)) {
      runMap.set(runId, []);
      orderedRuns.push(runId);
    }
    if (
      [
        "TOOL_REQUESTED",
        "TOOL_STARTED",
        "TOOL_COMPLETED",
        "APPROVAL_REQUIRED",
        "RUN_COMPLETED",
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
const quickPrompts = [
  "总结当前 session 的状态并给出下一步建议",
  "读取 AGENT.md 并说明这个 agent 的职责",
  "列出 workspace 根目录下可用文件",
  "把最近一次 run 的关键结论写成 artifact"
];
const quickActions = [
  {
    label: "读 AGENT.md",
    message: "请使用 file.read 读取 AGENT.md，并用中文总结这个 agent 的职责。"
  },
  {
    label: "列 workspace",
    message: "请使用 file.list 列出 workspace 根目录中的主要文件与目录。"
  },
  {
    label: "写 artifact",
    message: "请总结当前会话关键结论，并使用 artifact.save 保存为 summary.txt。"
  },
  {
    label: "查长期记忆",
    message: "请基于当前长期记忆总结与这个任务最相关的信息。"
  }
];

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

  if (event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED") {
    state.liveAssistant.runId = "";
    state.liveAssistant.content = "";
  }

  if (event.type === "APPROVAL_REQUIRED") {
    const approvalId = String(event.content).split(":").at(-1)?.trim();
    if (approvalId) {
      approvals.value.unshift({
        id: approvalId,
        status: "PENDING",
        reason: event.content
      });
      activeMenu.value = "approval";
    }
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

async function runChat() {
  if (!hasAvailableLlms.value) {
    state.error = llmWarning.value;
    return;
  }
  loading.sending = true;
  state.error = "";
  clearStream();
  try {
    const accepted = await sendChat({
      sessionId: form.sessionId || undefined,
      userId: form.userId || undefined,
      agentId: form.agentId || undefined,
      llmConfigId: form.llmConfigId || undefined,
      message: form.message
    });

    form.sessionId = accepted.sessionId;
    state.currentRunId = accepted.runId;
    state.resolvedAgentId = accepted.agentId;
    state.liveAssistant.runId = accepted.runId;
    state.liveAssistant.content = "";
    searchForm.sessionId = accepted.sessionId;
    searchForm.runId = accepted.runId;
    searchForm.agentId = accepted.agentId;
    state.events = [];
    await refreshRecentSessions();

    const streamPayload = {
      sessionId: accepted.sessionId,
      agentId: accepted.agentId,
      llmConfigId: accepted.llmConfigId || form.llmConfigId || undefined,
      userId: form.userId || "anonymous",
      message: form.message,
      runId: accepted.runId
    };

    if (transport.value === "websocket") {
      currentStream.value = createWebSocketStream({
        ...streamPayload,
        onEvent: pushEvent,
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

    activeMenu.value = "chat";
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

async function runSearch() {
  if (!searchForm.q) return;
  loading.search = true;
  try {
    state.search = await search(searchForm);
    activeMenu.value = "search";
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
    const key = detail.approvalRequestId || id;
    const index = approvals.value.findIndex((item) => (item.id || item.approvalRequestId) === key);
    if (index >= 0) approvals.value[index] = detail;
    else approvals.value.unshift(detail);
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.approval = false;
  }
}

async function decideApproval(id, decision) {
  loading.approval = true;
  try {
    if (decision === "approve") await approve(id);
    else await reject(id);
    await loadApproval(id);
    await refreshRun();
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.approval = false;
  }
}

onMounted(async () => {
  await bootstrap();
  await refreshMemories();
  window.addEventListener("click", closeContextMenu);
});

function useQuickPrompt(value) {
  form.message = value;
  activeMenu.value = "chat";
}

function useQuickAction(action) {
  form.message = action.message;
  activeMenu.value = "chat";
}

function openSession(session) {
  form.sessionId = session.sessionId;
  form.agentId = session.agentId;
  state.resolvedAgentId = session.agentId;
  searchForm.sessionId = session.sessionId;
  searchForm.agentId = session.agentId;
  state.currentRunId = "";
  state.liveAssistant.runId = "";
  state.liveAssistant.content = "";
  refreshSession();
}

function toggleInspector(section) {
  inspector[section] = !inspector[section];
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
          @click="activeMenu = item.id"
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
          <h3>Recent Sessions</h3>
        </div>
        <div class="session-list">
          <button
            v-for="session in recentSessions"
            :key="session.sessionId"
            class="session-link"
            @click="openSession(session)"
            @contextmenu.prevent="openSessionMenu($event, session)"
          >
            <strong>{{ session.title || session.sessionId }}</strong>
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

    <section class="center-stage">
      <header class="stage-header">
        <div>
          <p class="eyebrow">Workspace</p>
          <h2>{{ menuItems.find((item) => item.id === activeMenu)?.label }}</h2>
        </div>
        <div class="transport-switch">
          <button :class="{ active: transport === 'sse' }" @click="transport = 'sse'">SSE</button>
          <button :class="{ active: transport === 'websocket' }" @click="transport = 'websocket'">WebSocket</button>
        </div>
      </header>

      <section v-if="activeMenu === 'chat'" class="workspace-panel">
        <div class="chat-topbar">
          <div class="chat-title-block">
            <strong class="chat-title">{{ currentSessionTitle }}</strong>
            <span class="chat-subtitle">{{ activeAgentLabel }}</span>
          </div>
          <button class="compact-toggle" @click="compactControls = !compactControls">
            {{ compactControls ? "展开会话设置" : "收起会话设置" }}
          </button>
        </div>

        <div v-if="!compactControls" class="context-strip compact">
          <div class="context-card slim">
            <span>Session</span>
            <strong>{{ form.sessionId || "自动创建" }}</strong>
          </div>
          <div class="context-card slim">
            <span>User</span>
            <strong>{{ form.userId }}</strong>
          </div>
          <div class="context-card agent-picker slim">
            <span>Agent</span>
            <select v-model="form.agentId" data-testid="agent-select">
              <option value="">自动路由</option>
              <option v-for="agent in agents" :key="agent.agentId" :value="agent.agentId">
                {{ agent.displayName }} · {{ agent.agentId }}
              </option>
            </select>
          </div>
          <div class="context-card agent-picker slim">
            <span>LLM</span>
            <select v-model="form.llmConfigId" data-testid="llm-select">
              <option value="">自动默认</option>
              <option v-for="llm in llms" :key="llm.configId" :value="llm.configId">
                {{ llm.displayName }} · {{ llm.model }}
              </option>
            </select>
          </div>
        </div>

        <div class="quick-row compact">
          <button v-for="prompt in quickPrompts" :key="prompt" class="quick-chip" @click="useQuickPrompt(prompt)">
            {{ prompt }}
          </button>
          <button v-for="action in quickActions" :key="action.label" class="ops-chip" @click="useQuickAction(action)">
            {{ action.label }}
          </button>
        </div>

        <p v-if="llmWarning" class="error">{{ llmWarning }}</p>
        <p v-if="state.error" class="error">{{ state.error }}</p>

        <div class="conversation-panel expanded">
          <div class="panel-title">
            <h3>会话与反馈</h3>
            <span>{{ transcriptMessages.length }} messages / {{ groupedRunTimeline.length }} runs</span>
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
                  <p>{{ block.message.content }}</p>
                  <div v-if="block.message.toolName || block.message.toolResultJson" class="inline-tool">
                    <span v-if="block.message.toolName">tool: {{ block.message.toolName }}</span>
                    <span v-if="block.message.toolResultJson">result attached</span>
                  </div>
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
                  <p>{{ block.event.content }}</p>
                </article>
              </template>
              <div class="inline-event-row">
                <span
                  v-for="event in state.events.filter((item) => item.runId === group.runId).slice(-6)"
                  :key="event.id"
                  class="event-chip"
                >
                  {{ event.type }}
                </span>
              </div>
            </section>
            <section v-if="!transcriptMessages.length && state.events.length" class="run-group">
              <div class="run-divider">
                <span>Run</span>
                <strong>{{ state.currentRunId || "current" }}</strong>
              </div>
              <article
                v-for="event in state.events"
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
              这里显示真实会话过程，而不是管理表格。
            </div>
          </div>
        </div>

        <div class="composer-dock">
          <label class="dock-field">
            <span>Message</span>
            <textarea
              v-model="form.message"
              rows="3"
              placeholder="在这里描述任务、追问结果、或者要求 agent 调用工具"
              data-testid="message-input"
            ></textarea>
          </label>
          <div class="dock-actions">
            <button class="primary" :disabled="loading.sending || !form.message || !hasAvailableLlms" @click="runChat" data-testid="send-button">发送并开始执行</button>
            <button @click="refreshSession">刷新会话</button>
            <button @click="refreshRun">刷新运行</button>
            <button @click="refreshMemories">刷新记忆</button>
          </div>
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
          <h3>审批工作台</h3>
          <span>{{ approvals.length }} pending/loaded</span>
        </div>
        <div class="approval-list">
          <article v-for="approval in approvals" :key="approval.id || approval.approvalRequestId" class="approval-row">
            <div>
              <strong>{{ approval.toolName || approval.id }}</strong>
              <p>{{ approval.reason || approval.argumentsJson }}</p>
            </div>
            <div class="approval-actions">
              <span class="status-chip">{{ approval.status || "PENDING" }}</span>
              <button @click="loadApproval(approval.id || approval.approvalRequestId)">查看</button>
              <button class="primary" @click="decideApproval(approval.id || approval.approvalRequestId, 'approve')">批准</button>
              <button @click="decideApproval(approval.id || approval.approvalRequestId, 'reject')">拒绝</button>
            </div>
          </article>
          <div v-if="!approvals.length" class="empty-state">当前没有待处理审批。</div>
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
  </div>
</template>
