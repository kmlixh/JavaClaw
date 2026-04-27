<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import MarkdownBlock from "./components/MarkdownBlock.vue";
import {
  approve,
  cancelRun,
  createSseStream,
  createWebSocketStream,
  artifactDownloadUrl,
  deleteAdminLlm,
  getActiveRun,
  listActiveRuns,
  getApproval,
  getMemories,
  getRun,
  getSession,
  listAdminLlms,
  listApprovals,
  listKnowledgeEntries,
  listAgents,
  listAgentDefinitionsAdmin,
  listLlms,
  listMemoryNotesAdmin,
  listRunsBySession,
  listSessions,
  saveAgentDefinitionAdmin,
  saveMemoryNoteAdmin,
  deleteAgentDefinitionAdmin,
  deleteMemoryNoteAdmin,
  listSkillDefinitions,
  listToolDefinitions,
  deleteSession,
  deleteKnowledgeEntry,
  deleteSkillDefinition,
  deleteToolDefinition,
  deleteDatasource,
  listDatasources,
  saveDatasource,
  renameSession,
  reject,
  saveAdminLlm,
  saveKnowledgeEntry,
  saveSkillDefinition,
  saveToolDefinition,
  search,
  sendChat,
  whoami,
  login as apiLogin,
  logout as apiLogout,
  switchTenant as apiSwitchTenant,
  changePassword as apiChangePassword,
  // P4-B 管理后台
  adminListUsers,
  adminCreateUser,
  adminUpdateUser,
  adminDeleteUser,
  adminResetUserPassword,
  adminImportUsers,
  adminAssignRoles,
  adminListUserTenantRoles,
  adminListTenants,
  adminSaveTenant,
  adminDeleteTenant,
  adminListRoles,
  adminSaveRole,
  adminDeleteRole,
  adminListRolePermissions,
  adminUpdateRolePermissions,
  adminListPermissions,
  adminListOauthClients,
  adminCreateOauthClient,
  adminUpdateOauthClient,
  adminRotateOauthClientSecret,
  adminDeleteOauthClient
} from "./api";

const agents = ref([]);
const llms = ref([]);
const approvals = ref([]);

// P2 Auth state. 首次 mount 调 whoami 填进来;anonymous=true 时现有 UX 不变,
// 用户主动点登录才会跳 /login 换成真身份。P3 开启强鉴权时加路由守卫。
const authUser = reactive({
  loaded: false,
  anonymous: true,
  userId: "",
  username: "",
  displayName: "",
  email: "",
  passwordMustChange: false,
  activeTenantId: "",
  tenants: [],
  permissions: [],
  menus: []
});
const loginForm = reactive({ username: "", password: "", submitting: false, error: "" });
const passwordChangeForm = reactive({ oldPassword: "", newPassword: "", submitting: false, error: "" });
const currentStream = ref(null);
const transport = ref("websocket");
const activeMenu = ref("chat");
const activeRuns = ref([]);
let activeRunsTimer = null;

// 会话流的 sticky-follow 滚动:
//   - followBottom 表示"用户现在是否贴在底部"。只在 scroll 事件(用户手滚)里刷新这个值,
//     内容增长本身不改它。这样解决了老版本的 bug —— 以前在内容爆涨时拿"当前距底距离"
//     判断是否跟随,一条 300 字的 MODEL_OUTPUT 涌出后瞬间被判成"离底"就不跟了。
//   - NEAR_BOTTOM_PX 是判定"贴底"的容差。48px 够小,不会误把"手动滚离一小段"当贴底;
//     也够大,能容忍浏览器亚像素滚动误差。
//   - scroll 事件在"我们程序性地 scrollTop = scrollHeight"时也会触发,此时位置就是底部,
//     flag 正确保持 true,不会误判。
const conversationStreamRef = ref(null);
const NEAR_BOTTOM_PX = 48;
const followBottom = ref(true);

function updateFollowFlagFromPosition() {
  const el = conversationStreamRef.value;
  if (!el) return;
  followBottom.value = (el.scrollHeight - el.scrollTop - el.clientHeight) <= NEAR_BOTTOM_PX;
}

function scrollConversationToBottom(opts = {}) {
  const el = conversationStreamRef.value;
  if (!el) return;
  const force = opts.force === true;
  if (!force && !followBottom.value) return;
  // 用 rAF 让浏览器先把最新 DOM 布局完,再读 scrollHeight,不然会滚到旧高度。
  requestAnimationFrame(() => {
    const target = conversationStreamRef.value;
    if (!target) return;
    target.scrollTop = target.scrollHeight;
    // force 场景(初次挂载 / 切 session)把 flag 也重置回 true,之后就正常跟随。
    if (force) followBottom.value = true;
  });
}

// WebSocket 重连控制:
//   - runId:当前需要保活的 run。切 session / clearStream 时会被清或设 cancelled=true。
//   - attempts:指数退避计数,open 成功时归零,连续失败累加。
//   - cancelled:用户主动切换时设 true,避免被调度的 setTimeout 触发意外重连。
//   - timer:待定的重连 setTimeout 句柄,用于取消。
// 设计上一次只保活一个 run。跨 session 的 run 靠 cross-session 过滤保护。
const reconnectCtl = {
  runId: "",
  sessionId: "",
  agentId: "",
  userId: "",
  attempts: 0,
  cancelled: false,
  timer: null
};
const RECONNECT_MAX_ATTEMPTS = 10;
const RECONNECT_MAX_DELAY_MS = 30000;

// 事件签名用于重连 replay 去重。服务端 replayAndSubscribe 会重放全部 history,
// 前端若不识别会把 liveAssistant / runPlan 再灌一遍。签名 = type|runId|timestamp|content(前160),
// 放进 Set 里 O(1) 查,容量封顶 4000 —— 超限时按 FIFO 清到 3000。
const processedEventSigs = new Set();
function eventSignature(event) {
  const content = typeof event?.content === "string" ? event.content.slice(0, 160) : "";
  return `${event?.type || ""}|${event?.runId || ""}|${event?.timestamp || ""}|${content}`;
}
function rememberEventSig(sig) {
  processedEventSigs.add(sig);
  if (processedEventSigs.size > 4000) {
    const iter = processedEventSigs.values();
    while (processedEventSigs.size > 3000) {
      processedEventSigs.delete(iter.next().value);
    }
  }
}
const router = useRouter();
const route = useRoute();
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
const imageInput = ref(null);
const mapCanvas = ref(null);
const composerEditor = ref(null);
const mentionPicker = reactive({
  visible: false,
  query: "",
  triggerIndex: -1
});
const mapModal = reactive({
  visible: false,
  zoom: 5,
  center: { lat: 35, lng: 105 },
  mode: "pan",
  regions: [],
  drawing: false,
  panning: false,
  startLatLng: null,
  startPoint: null,
  draftRegion: null,
  panOrigin: null,
  polygonPoints: [],
  hoverLatLng: null
});
const chartOverrides = reactive({});
let sessionRefreshTimer = null;
let runRefreshTimer = null;
const recentSessions = ref([]);
const MAP_TILE_SIZE = 256;
const MAP_VIEWPORT_WIDTH = 1120;
const MAP_VIEWPORT_HEIGHT = 640;
const mapViewport = reactive({
  width: MAP_VIEWPORT_WIDTH,
  height: MAP_VIEWPORT_HEIGHT
});
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
  llmModel: "",
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
  renderCompletionHintByRun: {},
  pendingMessages: [],
  liveAssistant: {
    runId: "",
    content: "",
    // 当前流式文字属于第几个 tool loop iteration。step 变化时要把上一 step 冻成独立气泡,
    // 否则下一 step 的 overwrite 会把自检 / 过渡说明 / 优化后报告等中间 turn 的内容全部吃掉。
    step: null
  },
  liveStatus: {
    runId: "",
    phase: "",
    text: "",
    updatedAt: ""
  },
  runPlan: {
    runId: "",
    title: "",
    steps: [],
    updatedAt: "",
    collapsed: false
  },
  session: null,
  run: null,
  // 会话内所有 run 的权威状态（由 /api/sessions/{id}/runs 提供，已经过 StaleRunReconciler 修正）。
  // isRunTerminalByEvents 除了看 RUN_COMPLETED/RUN_FAILED 事件，还会看这里 —— 因为历史 run 失败时
  // 前端并不一定有 WebSocket 流可以收到事件，必须用 DB 权威状态兜底。
  runsByRunId: {},
  memories: [],
  search: {
    messages: [],
    memories: [],
    artifacts: []
  },
  catalog: {
    llms: [],
    knowledge: [],
    tools: [],
    skills: [],
    datasources: [],
    agents: [],
    // P4-B 管理后台
    users: [],
    tenants: [],
    roles: [],
    permissions: [],
    oauthClients: []
  },
  error: ""
});

// 菜单按"工作区 → Agent 配置 → 系统管理"三大组排列。
// group 决定渲染时显示在哪个分组下;permission 是必需的可见性条件,缺则人人可见。
const menuItems = [
  // —— 工作区:日常使用的功能入口 ——
  { id: "chat",        group: "工作区", label: "会话",      hint: "发送消息与接收反馈" },
  { id: "search",      group: "工作区", label: "搜索",      hint: "查询消息 / 记忆 / artifact" },
  { id: "approval",    group: "工作区", label: "审批",      hint: "查看历史审批记录" },
  { id: "memory",      group: "工作区", label: "长期记忆",   hint: "浏览 memory note" },

  // —— Agent 配置:跟某个 agent / skill 绑的资源 ——
  { id: "agents",      group: "Agent 配置", label: "Agents",   hint: "管理 agent 定义 (AGENT/SOUL/MEMORY)" },
  { id: "knowledge",   group: "Agent 配置", label: "知识库",    hint: "管理 agent 知识条目" },
  { id: "skills",      group: "Agent 配置", label: "Skills",    hint: "管理 skill 提示词" },
  { id: "tools",       group: "Agent 配置", label: "工具定义",   hint: "管理工具白名单" },
  { id: "datasources", group: "Agent 配置", label: "数据源",    hint: "JDBC 连接与凭证" },
  { id: "llms",        group: "Agent 配置", label: "LLM 配置",  hint: "模型连接与默认配置" },

  // —— 系统管理:跨用户 / 跨租户的平台级操作,按权限显隐 ——
  { id: "users",         group: "系统管理", label: "用户",       hint: "用户与角色分配",         permission: "user.manage" },
  { id: "tenants",       group: "系统管理", label: "租户",       hint: "租户与应用",            permission: "tenant.manage" },
  { id: "roles",         group: "系统管理", label: "角色 / 权限", hint: "角色定义与权限集合",     permission: "permission.manage" },
  { id: "oauth-clients", group: "系统管理", label: "OAuth 应用", hint: "外部应用 client_id/secret", permission: "oauth.client.manage" }
];

// 按权限过滤后再按 group 拆分;空组不渲染。匿名期一律放行,SUPER_ADMIN 自然啥都看得见。
const visibleMenuItems = computed(() => {
  if (authUser.anonymous) return menuItems;
  const perms = new Set(authUser.permissions || []);
  return menuItems.filter((item) => !item.permission || perms.has(item.permission));
});

const groupedMenuItems = computed(() => {
  const groups = new Map();
  for (const item of visibleMenuItems.value) {
    const key = item.group || "其他";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(item);
  }
  return Array.from(groups, ([title, items]) => ({ title, items }));
});
const llmProviderOptions = [
  "jiutian",
  "openai-compatible",
  "siliconflow",
  "dify",
  "openai",
  "azure-openai",
  "anthropic",
  "google",
  "ollama",
  "custom"
];
const llmForm = reactive({
  id: "",
  provider: "openai-compatible",
  displayName: "",
  model: "",
  modelMappingJson: "{\n  \"models\": []\n}",
  baseUrl: "",
  apiKey: "",
  chatPath: "/chat/completions",
  stream: true,
  enabled: true,
  defaultConfig: false
});
const llmModelRows = ref([]);
const lastSentRequest = ref(null);
const knowledgeForm = reactive({ id: "", title: "", content: "", contentType: "markdown", source: "database", tagsJson: "[]", enabled: true });
const toolForm = reactive({ id: "", toolName: "", displayName: "", description: "", schemaJson: "{}", toolType: "builtin", configJson: "{}", enabled: true, approvalRequired: false });
const skillForm = reactive({
  id: "",
  agentIds: [],
  skillName: "",
  description: "",
  promptTemplate: "",
  configJson: "{}",
  triggerKeywords: "[]",
  enabled: true
});
const datasourceForm = reactive({ id: "", displayName: "", jdbcUrl: "", username: "", password: "", dialect: "postgresql", description: "", enabled: true });

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
const selectedRuntimeLlm = computed(() => llms.value.find((item) => item.configId === form.llmConfigId) || null);
const selectedRuntimeLlmModels = computed(() => parseLlmModelMappings(selectedRuntimeLlm.value?.modelMappingJson, selectedRuntimeLlm.value?.model));
const chatRunBusy = computed(() => loading.sending || !!currentStream.value || isActiveRunStatus(state.run?.status));
const llmWarning = computed(() => hasAvailableLlms.value
  ? ""
  : "后端当前没有加载到任何可用 LLM。请确认 agent-app 使用了正确的 profile，并且 llm_provider_config 表中存在 enabled=true 的配置。");
const mapCenterWorld = computed(() => projectLatLng(mapModal.center.lat, mapModal.center.lng, mapModal.zoom));
const mapOriginWorld = computed(() => ({
  x: mapCenterWorld.value.x - mapViewport.width / 2,
  y: mapCenterWorld.value.y - mapViewport.height / 2
}));
const mapTiles = computed(() => {
  const limit = 2 ** mapModal.zoom;
  const minX = Math.floor(mapOriginWorld.value.x / MAP_TILE_SIZE);
  const maxX = Math.floor((mapOriginWorld.value.x + mapViewport.width) / MAP_TILE_SIZE);
  const minY = Math.floor(mapOriginWorld.value.y / MAP_TILE_SIZE);
  const maxY = Math.floor((mapOriginWorld.value.y + mapViewport.height) / MAP_TILE_SIZE);
  const tiles = [];
  for (let tileX = minX; tileX <= maxX; tileX += 1) {
    for (let tileY = minY; tileY <= maxY; tileY += 1) {
      if (tileY < 0 || tileY >= limit) {
        continue;
      }
      const wrappedX = ((tileX % limit) + limit) % limit;
      tiles.push({
        key: `${mapModal.zoom}-${tileX}-${tileY}`,
        url: `https://tile.openstreetmap.org/${mapModal.zoom}/${wrappedX}/${tileY}.png`,
        left: tileX * MAP_TILE_SIZE - mapOriginWorld.value.x,
        top: tileY * MAP_TILE_SIZE - mapOriginWorld.value.y
      });
    }
  }
  return tiles;
});
const mapRegionOverlays = computed(() => {
  const shapes = mapModal.draftRegion
    ? [...mapModal.regions, mapModal.draftRegion]
    : mapModal.regions;
  return shapes.map((region) => toOverlayRegion(region));
});

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
    .find((event) => event.runId === runId && event.type === "RUN_STATUS" && !isModelOutputStatus(event.content));
  return candidate?.content || "";
});

function toolDisplayPayload(message) {
  return parseJson(message?.toolResultJson);
}

function isRenderedDisplay(payload) {
  return payload && ["table", "echarts", "markdown"].includes(payload.displayType);
}

function findRenderableToolForRun(runId) {
  return transcriptMessages.value.find((message) =>
    message.runId === runId
    && message.role === "tool"
    && isRenderedDisplay(toolDisplayPayload(message))
  );
}

function hasRenderableToolMessageForRun(runId) {
  return !!findRenderableToolForRun(runId)
    || transcriptMessages.value.some((message) =>
      message.runId === runId
      && message.role === "tool"
      && artifactPayload(message)
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
  if (payload.displayType === "markdown") return payload.name || "Markdown 文档";
  return message.toolName || "结果";
}

function artifactPayload(message) {
  const payload = toolDisplayPayload(message);
  return payload?.displayType === "artifact" ? payload : null;
}

function markdownPayload(message) {
  const payload = toolDisplayPayload(message);
  return payload?.displayType === "markdown" ? payload : null;
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

// 这些是纯后端工具:LLM 在循环里用来辅助决策(查表结构、看 partition 等),但对用户
// 没有直接价值。它们的原始调用 / 返回不应该出现在会话气泡里,否则聊天流充满噪音。
// 注意:此过滤只影响**展示**,TOOL_REQUESTED/COMPLETED 事件仍会被后端正常记录和推送。
const HIDDEN_BACKEND_TOOLS = new Set(["db.schema.inspect"]);

function isHiddenBackendTool(toolName) {
  return !!toolName && HIDDEN_BACKEND_TOOLS.has(toolName);
}

function shouldHideToolMessage(message, runId) {
  if (message.role !== "tool") return false;
  if (isHiddenBackendTool(message.toolName)) return true;
  return message.toolName === "db.query" && !!findRenderableToolForRun(runId);
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

// 后端失败分支给 assistant 消息加 "[运行失败]" 前缀,前端据此把气泡标成 error 样式。
// 另外兼容一下 run 记录 —— 如果 runsByRunId 里标了 FAILED,而且这条 assistant 消息
// 跟那个 run 关联,也算失败气泡(为旧数据兜底)。
function isFailedAssistantMessage(message) {
  if (!message || message.role !== "assistant") return false;
  const content = (message.content || "").trim();
  if (content.startsWith("[运行失败]")) return true;
  const runStatus = state.runsByRunId?.[message.runId]?.status;
  return runStatus === "FAILED";
}

function isRenderedToolMessage(message) {
  const payload = toolDisplayPayload(message);
  return !!(isRenderedDisplay(payload) || artifactPayload(message));
}

function isFinalResultBlock(block) {
  if (block.kind !== "message") {
    return false;
  }
  const message = block.message;
  if (!message) {
    return false;
  }
  if (message.role === "assistant" && String(message.content || "").trim()) {
    return true;
  }
  if (message.role === "tool" && isRenderedToolMessage(message)) {
    return true;
  }
  return false;
}

function isUserBlock(block) {
  return block.kind === "message" && block.message?.role === "user";
}

function isRenderedToolBlock(block) {
  return block.kind === "message"
    && block.message?.role === "tool"
    && isRenderedToolMessage(block.message);
}

function pickSingleFinalResultBlock(blocks) {
  if (!Array.isArray(blocks) || blocks.length === 0) {
    return null;
  }
  for (let i = blocks.length - 1; i >= 0; i -= 1) {
    if (isRenderedToolBlock(blocks[i])) {
      return blocks[i];
    }
  }
  for (let i = blocks.length - 1; i >= 0; i -= 1) {
    if (isFinalResultBlock(blocks[i])) {
      return blocks[i];
    }
  }
  return null;
}

function isRunTerminalByEvents(runId) {
  // 1) 优先看实时事件流（RUN_COMPLETED/RUN_FAILED）—— 当前正在跑的 run 以此为准
  if (state.events.some((event) => event.runId === runId && (event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED"))) {
    return true;
  }
  // 2) 退回 DB 权威状态 —— 历史/僵尸 run 走这条，避免前端把"server 早就 FAIL 了"的 run 一直显示为"任务执行中"
  const dbStatus = state.runsByRunId?.[runId]?.status;
  if (dbStatus === "COMPLETED" || dbStatus === "FAILED") {
    return true;
  }
  return false;
}

function makeWaitingBlock(runId) {
  return {
    kind: "event",
    key: `waiting-${runId}`,
    event: {
      id: `waiting-${runId}`,
      runId,
      type: "RUN_STATUS",
      timestamp: "",
      content: "任务执行中...",
      dynamic: true
    }
  };
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
    const deferredRenderBlocks = [];
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
      const block = {
        kind: "message",
        key: `message-${message.id}`,
        message: normalized
      };
      if (normalized.role === "tool" && isRenderedDisplay(toolDisplayPayload(normalized))) {
        deferredRenderBlocks.push(block);
      } else {
        blocks.push(block);
      }
    }
    blocks.push(...deferredRenderBlocks);
    runMap.set(runId, blocks);
  }

  for (const event of state.events) {
    const runId = event.runId || state.currentRunId || "current";
    if (!runMap.has(runId)) {
      runMap.set(runId, []);
      orderedRuns.push(runId);
    }
    if (shouldShowTimelineEvent(event)) {
      if (event.type === "TOOL_COMPLETED" && toolEventRenderPayload(event)) {
        const renderMessage = toolEventRenderMessage(event, runId);
        if (renderMessage && !hasRenderableToolMessageForRun(runId)) {
          runMap.get(runId).push({
            kind: "message",
            key: `event-render-${event.id}`,
            message: renderMessage
          });
        }
      }
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

  return orderedRuns.map((runId) => {
    const blocks = runMap.get(runId) || [];
    const userBlocks = blocks.filter((block) => isUserBlock(block));
    const finalResultBlock = pickSingleFinalResultBlock(blocks);
    const runTerminal = isRunTerminalByEvents(runId);
    if (finalResultBlock) {
      return {
        runId,
        blocks: [...userBlocks, finalResultBlock]
      };
    }

    // 不再挂 makeWaitingBlock "任务执行中..." —— 顶部的 live-status 横幅已经显式在说"思考中 / 数据库查询 / ..."
    // 当前阶段，时间线里再冒一个冗余的"任务执行中"就是噪音，也就是用户看到的"两个奇怪的执行步骤"。
    const latestRenderableEvent = [...state.events]
      .reverse()
      .find((event) => event.runId === runId && event.type === "TOOL_COMPLETED" && !!toolEventRenderPayload(event));
    if (latestRenderableEvent) {
      const renderMessage = toolEventRenderMessage(latestRenderableEvent, runId);
      if (renderMessage) {
        return {
          runId,
          blocks: [
            ...userBlocks,
            {
              kind: "message",
              key: `latest-render-${latestRenderableEvent.id}`,
              message: renderMessage
            }
          ]
        };
      }
    }

    if (state.liveAssistant.runId === runId && state.liveAssistant.content) {
      return {
        runId,
        blocks: [
          ...userBlocks,
          {
            kind: "live",
            key: `latest-live-${runId}`,
            content: state.liveAssistant.content
          }
        ]
      };
    }

    const latestProgressEvent = [...state.events]
      .reverse()
      .find((event) => event.runId === runId && shouldShowTimelineEvent(event));
    if (latestProgressEvent) {
      return {
        runId,
        blocks: [
          ...userBlocks,
          {
            kind: "event",
            key: `latest-event-${latestProgressEvent.id}`,
            event: latestProgressEvent
          }
        ]
      };
    }

    return {
      runId,
      blocks: [...userBlocks]
    };
  });
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
const lastUserRequest = computed(() => {
  const messages = [...transcriptMessages.value].reverse();
  return messages.find((item) => item.role === "user" && item.content?.trim()) || null;
});
const resendSourceRequest = computed(() => {
  // 1) 本次会话内已经发过 —— 用内存里的最新快照（带 references/attachments）
  if (lastSentRequest.value?.message?.trim()) {
    return lastSentRequest.value;
  }
  // 2) 刷新页面后 lastSentRequest=null —— fallback 到历史记录里最后一条 user message。
  // 从消息上的 referencesJson / attachmentsJson(V20 之后后端持久化的 JSON 数组字符串)
  // 解析出空间区域 / 图片 / 文件等附件,保证刷新后重发也能带上完整 payload。
  // V20 之前的老消息这两列为空,安全回退到空数组(只重发文本)。
  const fallback = lastUserRequest.value;
  if (fallback?.content?.trim()) {
    return {
      message: fallback.content,
      references: parseJsonArraySafe(fallback.referencesJson),
      attachments: parseJsonArraySafe(fallback.attachmentsJson)
    };
  }
  return null;
});

function parseJsonArraySafe(raw) {
  if (!raw || typeof raw !== "string") return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch (error) {
    console.warn("[transcript.parse_json_array_failed]", error?.message || error);
    return [];
  }
}
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
    case "llms":
      return {
        count: state.catalog.llms.length,
        label: "添加 LLM",
        action: createLlm
      };
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
    case "datasources":
      return {
        count: state.catalog.datasources.length,
        label: "添加数据源",
        action: createDatasource
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

function escapeHtmlAttribute(value) {
  return escapeHtml(value)
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

function safeMarkdownUrl(value) {
  const url = String(value || "").trim();
  if (!url) return "";
  if (/^(https?:\/\/|\/|#)/i.test(url)) {
    return url;
  }
  if (/^data:image\/[a-z0-9.+-]+;base64,/i.test(url)) {
    return url;
  }
  return "";
}

function renderInlineMarkdown(value) {
  let html = escapeHtml(value);
  html = html.replace(/!\[([^\]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)/g, (_match, alt, url) => {
    const safeUrl = safeMarkdownUrl(url);
    if (!safeUrl) {
      return escapeHtml(_match);
    }
    return `<img src="${escapeHtmlAttribute(safeUrl)}" alt="${escapeHtmlAttribute(alt)}" loading="lazy" />`;
  });
  html = html.replace(/\[([^\]]+)]\(([^)\s]+)(?:\s+"[^"]*")?\)/g, (_match, label, url) => {
    const safeUrl = safeMarkdownUrl(url);
    if (!safeUrl) {
      return escapeHtml(_match);
    }
    return `<a href="${escapeHtmlAttribute(safeUrl)}" target="_blank" rel="noopener">${label}</a>`;
  });
  html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
  html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  html = html.replace(/\*([^*]+)\*/g, "<em>$1</em>");
  return html;
}

function isMarkdownTableSeparator(line) {
  return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line || "");
}

function parseMarkdownTable(lines, startIndex) {
  if (!lines[startIndex]?.includes("|") || !isMarkdownTableSeparator(lines[startIndex + 1])) {
    return null;
  }
  const splitRow = (line) => String(line || "")
    .trim()
    .replace(/^\|/, "")
    .replace(/\|$/, "")
    .split("|")
    .map((cell) => cell.trim());
  const headers = splitRow(lines[startIndex]);
  const rows = [];
  let index = startIndex + 2;
  while (index < lines.length && lines[index].includes("|") && lines[index].trim()) {
    rows.push(splitRow(lines[index]));
    index += 1;
  }
  const headHtml = headers.map((cell) => `<th>${renderInlineMarkdown(cell)}</th>`).join("");
  const bodyHtml = rows
    .map((row) => `<tr>${headers.map((_header, cellIndex) => `<td>${renderInlineMarkdown(row[cellIndex] || "")}</td>`).join("")}</tr>`)
    .join("");
  return {
    html: `<table><thead><tr>${headHtml}</tr></thead><tbody>${bodyHtml}</tbody></table>`,
    nextIndex: index
  };
}

function markdownToHtml(markdown) {
  const lines = String(markdown || "").replace(/\r\n/g, "\n").split("\n");
  const blocks = [];
  let index = 0;
  let inCode = false;
  let codeLines = [];
  while (index < lines.length) {
    const line = lines[index];
    const trimmed = line.trim();
    if (/^```/.test(trimmed)) {
      if (inCode) {
        blocks.push(`<pre><code>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
        codeLines = [];
        inCode = false;
      } else {
        inCode = true;
      }
      index += 1;
      continue;
    }
    if (inCode) {
      codeLines.push(line);
      index += 1;
      continue;
    }
    if (!trimmed) {
      index += 1;
      continue;
    }
    const table = parseMarkdownTable(lines, index);
    if (table) {
      blocks.push(table.html);
      index = table.nextIndex;
      continue;
    }
    const heading = trimmed.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      const level = heading[1].length;
      blocks.push(`<h${level}>${renderInlineMarkdown(heading[2])}</h${level}>`);
      index += 1;
      continue;
    }
    if (/^>\s?/.test(trimmed)) {
      const quoteLines = [];
      while (index < lines.length && /^>\s?/.test(lines[index].trim())) {
        quoteLines.push(lines[index].trim().replace(/^>\s?/, ""));
        index += 1;
      }
      blocks.push(`<blockquote>${quoteLines.map(renderInlineMarkdown).join("<br>")}</blockquote>`);
      continue;
    }
    if (/^[-*+]\s+/.test(trimmed)) {
      const items = [];
      while (index < lines.length && /^[-*+]\s+/.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^[-*+]\s+/, ""));
        index += 1;
      }
      blocks.push(`<ul>${items.map((item) => `<li>${renderInlineMarkdown(item)}</li>`).join("")}</ul>`);
      continue;
    }
    if (/^\d+\.\s+/.test(trimmed)) {
      const items = [];
      while (index < lines.length && /^\d+\.\s+/.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^\d+\.\s+/, ""));
        index += 1;
      }
      blocks.push(`<ol>${items.map((item) => `<li>${renderInlineMarkdown(item)}</li>`).join("")}</ol>`);
      continue;
    }
    const paragraph = [trimmed];
    index += 1;
    while (index < lines.length && lines[index].trim() && !/^(```|#{1,6}\s+|[-*+]\s+|\d+\.\s+|>\s?)/.test(lines[index].trim())) {
      if (parseMarkdownTable(lines, index)) {
        break;
      }
      paragraph.push(lines[index].trim());
      index += 1;
    }
    blocks.push(`<p>${renderInlineMarkdown(paragraph.join(" "))}</p>`);
  }
  if (inCode) {
    blocks.push(`<pre><code>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
  }
  return blocks.join("\n");
}

function renderComposerHtml(message) {
  let html = escapeHtml(message).replace(/\n/g, "<br>");
  const tokenLabels = [
    ...form.references.map((reference) => reference.label),
    ...form.attachments.map((attachment) => attachment.displayLabel).filter(Boolean)
  ];
  for (const label of tokenLabels) {
    const token = escapeHtml(`{${label}}`);
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

// Enter 发送 / Shift+Enter 换行 —— 提及面板展开时让上下左右 + Enter 正常走面板选择路径，不劫持。
function handleComposerKeydown(event) {
  if (event.key !== "Enter" || event.shiftKey || event.isComposing) {
    return;
  }
  if (mentionPicker.visible) {
    return;
  }
  event.preventDefault();
  syncMessageFromComposer();
  if (chatRunBusy.value || !form.message.trim() || !hasAvailableLlms.value) {
    return;
  }
  runChat();
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

function openImagePicker() {
  imageInput.value?.click();
}

function openFilePicker() {
  fileInput.value?.click();
}

async function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error(`读取文件失败: ${file.name}`));
    reader.readAsDataURL(file);
  });
}

function isTextLikeFile(file) {
  const textTypes = [
    "text/",
    "application/json",
    "application/xml",
    "application/sql",
    "application/javascript"
  ];
  if (textTypes.some((type) => file.type.startsWith(type) || file.type === type)) {
    return true;
  }
  return /\.(txt|md|sql|json|csv|tsv|log|xml|yaml|yml|js|ts)$/i.test(file.name);
}

async function addAttachmentFromFile(file, mode) {
  const imageMode = mode === "image";
  const sizeLimit = imageMode ? 3 * 1024 * 1024 : 1024 * 1024;
  if (file.size > sizeLimit) {
    state.error = `${file.name} 超过大小限制，${imageMode ? "图片" : "文件"}当前支持 ${Math.floor(sizeLimit / 1024 / 1024) || 1}MB 以内`;
    return;
  }
  const isText = !imageMode && isTextLikeFile(file);
  const content = isText ? await file.text() : await fileToDataUrl(file);
  const attachment = {
    name: file.name,
    displayLabel: nextAttachmentDisplayLabel(imageMode ? "image" : "file"),
    contentType: file.type || (imageMode ? "image/*" : "application/octet-stream"),
    content
  };
  form.attachments.push(attachment);
  appendComposerToken(attachment.displayLabel);
}

async function handleImageChange(event) {
  const files = Array.from(event.target.files || []);
  for (const file of files) {
    await addAttachmentFromFile(file, "image");
  }
  event.target.value = "";
}

async function handleAttachmentChange(event) {
  const files = Array.from(event.target.files || []);
  for (const file of files) {
    await addAttachmentFromFile(file, "file");
  }
  event.target.value = "";
}

function removeAttachment(attachment) {
  form.attachments = form.attachments.filter((item) => item !== attachment);
  if (attachment?.displayLabel) {
    form.message = String(form.message || "")
      .replaceAll(`{${attachment.displayLabel}}`, "")
      .replace(/[ \t]{2,}/g, " ")
      .replace(/\n{3,}/g, "\n\n")
      .trim();
    syncComposerEditor(true);
  }
}

function isMapRegionAttachment(attachment) {
  return attachment?.contentType === "application/vnd.java-claw.map-regions+json";
}

function mapRegionAttachmentPayload(attachment) {
  if (!isMapRegionAttachment(attachment)) {
    return null;
  }
  try {
    const parsed = JSON.parse(String(attachment.content || "{}"));
    if (!parsed || !Array.isArray(parsed.regions)) {
      return parsed;
    }
    return {
      ...parsed,
      regions: parsed.regions.map((region) => {
        if (region?.geometry?.type === "Polygon") {
          return region;
        }
        const polygon = Array.isArray(region?.polygon) ? region.polygon : [];
        if (polygon.length >= 4) {
          return {
            ...region,
            srid: region.srid || 4326,
            geometry: {
              type: "Polygon",
              coordinates: [polygon]
            }
          };
        }
        return region;
      })
    };
  } catch {
    return null;
  }
}

function visibleComposerAttachments() {
  return form.attachments.filter((attachment) => !isMapRegionAttachment(attachment));
}

function visibleMapRegionAttachments() {
  return form.attachments
    .filter((attachment) => isMapRegionAttachment(attachment))
    .map((attachment) => ({
      attachment,
      payload: mapRegionAttachmentPayload(attachment)
    }))
    .filter((item) => item.payload);
}

function appendMessageText(value) {
  const text = String(value || "").trim();
  if (!text) return;
  const current = (form.message || "").trim();
  form.message = current ? `${current}\n${text}` : text;
  syncComposerEditor(true);
}

function nextAttachmentDisplayLabel(kind) {
  const prefixMap = {
    image: "图片",
    file: "文件",
    map: "地图"
  };
  const prefix = prefixMap[kind] || "附件";
  const count = form.attachments.filter((attachment) => {
    if (kind === "map") {
      return isMapRegionAttachment(attachment);
    }
    if (kind === "image") {
      return String(attachment.contentType || "").startsWith("image/");
    }
    return !isMapRegionAttachment(attachment) && !String(attachment.contentType || "").startsWith("image/");
  }).length;
  return `${prefix}${count + 1}`;
}

function appendComposerToken(label) {
  const token = `{${label}}`;
  const current = form.message || "";
  const separator = current && !/\s$/.test(current) ? " " : "";
  form.message = `${current}${separator}${token}`;
  syncComposerEditor(true);
}

function clampLatitude(lat) {
  return Math.max(-85, Math.min(85, lat));
}

function normalizeLongitude(lng) {
  let value = lng;
  while (value < -180) value += 360;
  while (value > 180) value -= 360;
  return value;
}

function projectLatLng(lat, lng, zoom) {
  const scale = MAP_TILE_SIZE * 2 ** zoom;
  const sinLat = Math.sin((clampLatitude(lat) * Math.PI) / 180);
  const x = ((normalizeLongitude(lng) + 180) / 360) * scale;
  const y = (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * scale;
  return { x, y };
}

function unprojectWorld(x, y, zoom) {
  const scale = MAP_TILE_SIZE * 2 ** zoom;
  const lng = (x / scale) * 360 - 180;
  const n = Math.PI - (2 * Math.PI * y) / scale;
  const lat = (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
  return {
    lat: clampLatitude(lat),
    lng: normalizeLongitude(lng)
  };
}

function latLngToScreen(lat, lng) {
  const world = projectLatLng(lat, lng, mapModal.zoom);
  return {
    x: world.x - mapOriginWorld.value.x,
    y: world.y - mapOriginWorld.value.y
  };
}

function updateMapViewport() {
  const rect = mapCanvas.value?.getBoundingClientRect();
  if (!rect) {
    return;
  }
  mapViewport.width = rect.width || MAP_VIEWPORT_WIDTH;
  mapViewport.height = rect.height || MAP_VIEWPORT_HEIGHT;
}

function getRelativePoint(event) {
  updateMapViewport();
  const rect = mapCanvas.value?.getBoundingClientRect() || event.currentTarget.getBoundingClientRect();
  return {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  };
}

function pointToLatLng(point) {
  return unprojectWorld(point.x + mapOriginWorld.value.x, point.y + mapOriginWorld.value.y, mapModal.zoom);
}

function boundsFromPoints(points) {
  return {
    minLat: Math.min(...points.map((point) => point.lat)),
    maxLat: Math.max(...points.map((point) => point.lat)),
    minLng: Math.min(...points.map((point) => point.lng)),
    maxLng: Math.max(...points.map((point) => point.lng))
  };
}

function distanceMeters(left, right) {
  const earthRadius = 6371000;
  const dLat = ((right.lat - left.lat) * Math.PI) / 180;
  const dLng = ((right.lng - left.lng) * Math.PI) / 180;
  const a = Math.sin(dLat / 2) ** 2
    + Math.cos((left.lat * Math.PI) / 180) * Math.cos((right.lat * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return 2 * earthRadius * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function rectanglePolygonPoints(bounds) {
  return [
    { lat: bounds.maxLat, lng: bounds.minLng },
    { lat: bounds.maxLat, lng: bounds.maxLng },
    { lat: bounds.minLat, lng: bounds.maxLng },
    { lat: bounds.minLat, lng: bounds.minLng },
    { lat: bounds.maxLat, lng: bounds.minLng }
  ];
}

function circlePolygonPoints(center, radiusMeters, segments = 48) {
  const latDelta = radiusMeters / 111320;
  const lngScale = Math.max(Math.cos((center.lat * Math.PI) / 180), 0.01);
  const lngDelta = radiusMeters / (111320 * lngScale);
  const points = [];
  for (let index = 0; index <= segments; index += 1) {
    const angle = (Math.PI * 2 * index) / segments;
    points.push({
      lat: clampLatitude(center.lat + latDelta * Math.sin(angle)),
      lng: normalizeLongitude(center.lng + lngDelta * Math.cos(angle))
    });
  }
  return points;
}

function createRectangleRegion(start, end, id = `region-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`) {
  const bounds = boundsFromPoints([start, end]);
  return {
    id,
    geometryType: "rectangle",
    bounds,
    polygonPoints: rectanglePolygonPoints(bounds)
  };
}

function createCircleRegion(center, edge, id = `region-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`) {
  const radiusMeters = distanceMeters(center, edge);
  const polygonPoints = circlePolygonPoints(center, radiusMeters);
  return {
    id,
    geometryType: "circle",
    center,
    radiusMeters,
    bounds: boundsFromPoints(polygonPoints),
    polygonPoints
  };
}

function createPolygonRegion(points, id = `region-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`) {
  const closed = [...points];
  const first = closed[0];
  const last = closed.at(-1);
  if (!last || first.lat !== last.lat || first.lng !== last.lng) {
    closed.push({ ...first });
  }
  return {
    id,
    geometryType: "polygon",
    bounds: boundsFromPoints(points),
    polygonPoints: closed
  };
}

function toOverlayRegion(region) {
  if (region.geometryType === "circle") {
    const center = latLngToScreen(region.center.lat, region.center.lng);
    const edge = latLngToScreen(region.center.lat, normalizeLongitude(region.center.lng + (region.radiusMeters / (111320 * Math.max(Math.cos((region.center.lat * Math.PI) / 180), 0.01)))));
    const radius = Math.max(4, Math.hypot(edge.x - center.x, edge.y - center.y));
    return { ...region, overlayType: "circle", cx: center.x, cy: center.y, radius };
  }
  if (region.geometryType === "polygon") {
    return {
      ...region,
      overlayType: "polygon",
      pointsAttr: region.polygonPoints.map((point) => {
        const screen = latLngToScreen(point.lat, point.lng);
        return `${screen.x},${screen.y}`;
      }).join(" ")
    };
  }
  const northWest = latLngToScreen(region.bounds.maxLat, region.bounds.minLng);
  const southEast = latLngToScreen(region.bounds.minLat, region.bounds.maxLng);
  return {
    ...region,
    overlayType: "rect",
    x: Math.min(northWest.x, southEast.x),
    y: Math.min(northWest.y, southEast.y),
    width: Math.abs(southEast.x - northWest.x),
    height: Math.abs(southEast.y - northWest.y)
  };
}

function openMapModal() {
  mapModal.regions = [];
  mapModal.draftRegion = null;
  mapModal.center = { lat: 35, lng: 105 };
  mapModal.zoom = 5;
  mapModal.mode = "pan";
  mapModal.drawing = false;
  mapModal.panning = false;
  mapModal.polygonPoints = [];
  mapModal.hoverLatLng = null;
  mapModal.visible = true;
  nextTick(() => updateMapViewport());
}

function closeMapModal() {
  mapModal.visible = false;
  mapModal.drawing = false;
  mapModal.panning = false;
  mapModal.startLatLng = null;
  mapModal.startPoint = null;
  mapModal.draftRegion = null;
  mapModal.panOrigin = null;
  mapModal.polygonPoints = [];
  mapModal.hoverLatLng = null;
}

function setMapMode(mode) {
  mapModal.mode = mode;
  mapModal.drawing = false;
  mapModal.panning = false;
  mapModal.draftRegion = null;
  mapModal.startLatLng = null;
  mapModal.startPoint = null;
  mapModal.polygonPoints = [];
  mapModal.hoverLatLng = null;
}

function setMapZoom(nextZoom, focusPoint = { x: mapViewport.width / 2, y: mapViewport.height / 2 }) {
  const zoom = Math.max(2, Math.min(18, nextZoom));
  if (zoom === mapModal.zoom) {
    return;
  }
  const focusLatLng = pointToLatLng(focusPoint);
  const focusWorld = projectLatLng(focusLatLng.lat, focusLatLng.lng, zoom);
  const nextCenterWorld = {
    x: focusWorld.x - focusPoint.x + mapViewport.width / 2,
    y: focusWorld.y - focusPoint.y + mapViewport.height / 2
  };
  mapModal.zoom = zoom;
  mapModal.center = unprojectWorld(nextCenterWorld.x, nextCenterWorld.y, zoom);
}

function zoomMap(delta) {
  setMapZoom(mapModal.zoom + delta);
}

function onMapWheel(event) {
  const point = getRelativePoint(event);
  setMapZoom(mapModal.zoom + (event.deltaY > 0 ? -1 : 1), point);
}

function onMapPointerDown(event) {
  event.currentTarget.setPointerCapture?.(event.pointerId);
  const point = getRelativePoint(event);
  if (mapModal.mode === "pan") {
    mapModal.panning = true;
    mapModal.startPoint = point;
    mapModal.panOrigin = { ...mapModal.center };
    return;
  }
  if (mapModal.mode === "rectangle" || mapModal.mode === "circle") {
    mapModal.drawing = true;
    mapModal.startPoint = point;
    mapModal.startLatLng = pointToLatLng(point);
    mapModal.draftRegion = mapModal.mode === "rectangle"
      ? createRectangleRegion(mapModal.startLatLng, mapModal.startLatLng, `draft-${Date.now()}`)
      : createCircleRegion(mapModal.startLatLng, mapModal.startLatLng, `draft-${Date.now()}`);
  }
}

function onMapPointerMove(event) {
  const point = getRelativePoint(event);
  if (mapModal.mode === "polygon") {
    mapModal.hoverLatLng = pointToLatLng(point);
    if (mapModal.polygonPoints.length >= 1) {
      mapModal.draftRegion = createPolygonRegion(
        [...mapModal.polygonPoints, mapModal.hoverLatLng],
        `draft-${Date.now()}`
      );
    }
    return;
  }
  if (mapModal.drawing && mapModal.startLatLng) {
    const current = pointToLatLng(point);
    mapModal.draftRegion = mapModal.mode === "rectangle"
      ? createRectangleRegion(mapModal.startLatLng, current, mapModal.draftRegion?.id || `draft-${Date.now()}`)
      : createCircleRegion(mapModal.startLatLng, current, mapModal.draftRegion?.id || `draft-${Date.now()}`);
    return;
  }
  if (mapModal.panning && mapModal.startPoint && mapModal.panOrigin) {
    const dx = point.x - mapModal.startPoint.x;
    const dy = point.y - mapModal.startPoint.y;
    const centerWorld = projectLatLng(mapModal.panOrigin.lat, mapModal.panOrigin.lng, mapModal.zoom);
    mapModal.center = unprojectWorld(centerWorld.x - dx, centerWorld.y - dy, mapModal.zoom);
  }
}

function onMapCanvasClick(event) {
  if (mapModal.mode !== "polygon" || mapModal.panning || mapModal.drawing) {
    return;
  }
  const latLng = pointToLatLng(getRelativePoint(event));
  mapModal.polygonPoints = [...mapModal.polygonPoints, latLng];
  if (mapModal.polygonPoints.length >= 2 && mapModal.hoverLatLng) {
    mapModal.draftRegion = createPolygonRegion(
      [...mapModal.polygonPoints, mapModal.hoverLatLng],
      `draft-${Date.now()}`
    );
  }
}

function finishPolygonDrawing() {
  if (mapModal.mode !== "polygon" || mapModal.polygonPoints.length < 3) {
    return;
  }
  mapModal.regions.push(createPolygonRegion(mapModal.polygonPoints, `region-${mapModal.regions.length + 1}`));
  mapModal.polygonPoints = [];
  mapModal.hoverLatLng = null;
  mapModal.draftRegion = null;
}

function finishMapInteraction(event) {
  if (mapModal.drawing && mapModal.draftRegion) {
    const region = mapModal.draftRegion;
    if ((region.geometryType === "rectangle"
      && region.bounds.maxLat !== region.bounds.minLat
      && region.bounds.maxLng !== region.bounds.minLng)
      || (region.geometryType === "circle" && region.radiusMeters > 5)) {
      mapModal.regions.push({ ...region, id: `region-${mapModal.regions.length + 1}` });
    }
  }
  mapModal.drawing = false;
  mapModal.panning = false;
  mapModal.startLatLng = null;
  mapModal.startPoint = null;
  mapModal.draftRegion = mapModal.mode === "polygon" ? mapModal.draftRegion : null;
  mapModal.panOrigin = null;
  if (event?.pointerId !== undefined) {
    mapCanvas.value?.releasePointerCapture?.(event.pointerId);
  }
}

function removeMapRegion(regionId) {
  mapModal.regions = mapModal.regions.filter((item) => item.id !== regionId);
}

function regionPayload(region, index) {
  const polygon = region.polygonPoints.map((point) => [point.lng, point.lat]);
  const geometry = {
    type: "Polygon",
    coordinates: [polygon]
  };
  if (region.geometryType === "circle") {
    return {
      id: region.id,
      name: `区域${index + 1}`,
      geometryType: "circle",
      srid: 4326,
      geometry,
      bounds: region.bounds,
      center: region.center,
      radiusMeters: Math.round(region.radiusMeters),
      polygon
    };
  }
  if (region.geometryType === "polygon") {
    return {
      id: region.id,
      name: `区域${index + 1}`,
      geometryType: "polygon",
      srid: 4326,
      geometry,
      bounds: region.bounds,
      polygon
    };
  }
  return {
    id: region.id,
    name: `区域${index + 1}`,
    geometryType: "rectangle",
    srid: 4326,
    geometry,
    bounds: region.bounds,
    polygon
  };
}

function confirmMapRegions() {
  if (!mapModal.regions.length) {
    closeMapModal();
    return;
  }
  const payload = {
    type: "map-regions",
    generatedAt: new Date().toISOString(),
    center: mapModal.center,
    zoom: mapModal.zoom,
    regions: mapModal.regions.map((region, index) => regionPayload(region, index))
  };
  const attachment = {
    name: `map-regions-${Date.now()}.json`,
    displayLabel: nextAttachmentDisplayLabel("map"),
    contentType: "application/vnd.java-claw.map-regions+json",
    content: JSON.stringify(payload, null, 2)
  };
  form.attachments.push(attachment);
  appendComposerToken(attachment.displayLabel);
  closeMapModal();
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
  syncSelectedRuntimeModel();
  await refreshRecentSessions();
  await refreshApprovals();
}

function normalizeIncomingEvent(event) {
  const fallbackRunId = state.currentRunId || state.liveAssistant.runId || "current";
  const runId = event?.runId || fallbackRunId;
  const type = event?.type || "RUN_STATUS";
  const timestamp = event?.timestamp || new Date().toISOString();
  const content = event?.content == null ? "" : String(event.content);
  const id = event?.id || `${type}-${runId}-${timestamp}-${state.events.length + 1}`;
  return {
    ...event,
    id,
    runId,
    type,
    timestamp,
    content
  };
}

const livePhaseLabel = computed(() => {
  switch (state.liveStatus.phase) {
    case "THINKING": return "思考中";
    case "QUERYING": return "数据库查询";
    case "SCHEMA": return "探测表结构";
    case "RENDERING": return "绘制图表";
    case "MODEL_OUTPUT": return "模型输出";
    case "MODEL_RETRY": return "模型重试";
    case "TOOL": return "工具执行";
    case "CONTEXT": return "组装上下文";
    case "MODEL": return "模型启动";
    case "COMPLETED":
    case "DONE": return "已完成";
    case "FAILED": return "失败";
    default: return state.liveStatus.phase || "";
  }
});

const isLiveStatusVisible = computed(() => {
  if (!state.currentRunId) return false;
  const phase = state.liveStatus.phase;
  if (!phase) return false;
  if (phase === "DONE" || phase === "COMPLETED") return false;
  return true;
});

const isPlanPanelVisible = computed(() => {
  // 只在真的有 plan steps 时才显示面板。
  // 旧逻辑曾经让"正在流式"就显示占位面板,目的是填补"run 开始到 plan.create 之间的空窗"。
  // 但 trigger-gated skill 激活后,大量请求(简单聊天、不命中关键词的指令)根本不会走
  // plan.create —— 如果沿用旧逻辑,这些请求也会空转一个"准备中…"面板直到 run 结束,
  // 变成无意义弹窗。现在改成:拿到 PLAN_UPDATED 事件、有实际 step 后才展示。
  return Array.isArray(state.runPlan.steps) && state.runPlan.steps.length > 0;
});

const planProgressLabel = computed(() => {
  const steps = state.runPlan.steps;
  if (!Array.isArray(steps) || !steps.length) return "";
  const completed = steps.filter((step) => step.status === "COMPLETED" || step.status === "SKIPPED").length;
  return `${completed}/${steps.length}`;
});

function planStepClass(step) {
  switch (step?.status) {
    case "IN_PROGRESS": return "in-progress";
    case "COMPLETED": return "completed";
    case "FAILED": return "failed";
    case "SKIPPED": return "skipped";
    default: return "pending";
  }
}

function planStepBadge(step) {
  switch (step?.status) {
    case "IN_PROGRESS": return "●";
    case "COMPLETED": return "✓";
    case "FAILED": return "✗";
    case "SKIPPED": return "—";
    default: return "○";
  }
}

function pushEvent(event) {
  const normalized = normalizeIncomingEvent(event);
  // Session 隔离兜底:用户切会话时,老 WS 的 close() 是异步的,这段窗口还能收到
  // 前一个会话的 RUN_STATUS/TOKEN_DELTA 等事件。如果不过滤,这些事件会把另一
  // 个会话的内容写进当前 UI(liveAssistant / runPlan / events 列表)。
  // 只要事件带 sessionId 且和当前关注的 sessionId 不一致,直接丢弃 —— 同一会话
  // 内多设备订阅同一 runId 不受影响(后端用 multicast 广播,每端各自拿到)。
  if (normalized.sessionId && form.sessionId && normalized.sessionId !== form.sessionId) {
    console.log("[ws.event.dropped_cross_session]", {
      eventSessionId: normalized.sessionId,
      currentSessionId: form.sessionId,
      type: normalized.type,
      runId: normalized.runId
    });
    return;
  }
  // 重连 replay 去重:断线重连时后端会把这个 run 的全部 history 再发一遍。这里按
  // 签名挡住已处理的那一份,避免 liveAssistant / runPlan / state.events 被重复灌。
  const sig = eventSignature(normalized);
  if (processedEventSigs.has(sig)) {
    console.log("[ws.event.dropped_replay_dedup]", normalized.type, normalized.runId);
    return;
  }
  rememberEventSig(sig);
  console.log("[ws.event.normalized]", normalized);
  state.events.push(normalized);

  if (normalized.type === "RUN_STARTED") {
    state.liveAssistant.runId = normalized.runId || state.currentRunId;
    state.liveAssistant.content = "";
    state.liveAssistant.step = null;
    state.runPlan.runId = normalized.runId || state.currentRunId;
    state.runPlan.title = "";
    state.runPlan.steps = [];
    state.runPlan.updatedAt = "";
  }

  if (normalized.type === "PLAN_UPDATED") {
    try {
      const snapshot = JSON.parse(normalized.content || "{}");
      state.runPlan.runId = normalized.runId || state.currentRunId;
      state.runPlan.title = snapshot.title || "";
      state.runPlan.steps = Array.isArray(snapshot.steps) ? snapshot.steps : [];
      state.runPlan.updatedAt = normalized.timestamp || new Date().toISOString();
    } catch (error) {
      console.warn("[plan.parse_failed]", normalized.content, error);
    }
  }

  if (normalized.type === "TOKEN_DELTA") {
    state.liveAssistant.runId = normalized.runId || state.currentRunId;
    // 后端 SimpleAgentRunner 在 loop 结束时只发一次 TOKEN_DELTA，content 是完整累积文本，
    // 不是 per-chunk 增量 —— 用 overwrite 避免和已通过 RUN_STATUS MODEL_OUTPUT 流式更新
    // 的 liveAssistant.content 产生双计。
    state.liveAssistant.content = normalized.content || "";
  }

  // RUN_STATUS 的 MODEL_OUTPUT phase 事件：每 ~700ms 一条，content 是到目前为止的
  // 完整累积文本。之前为了"避免刷屏"直接丢弃，但这样用户就看不到流式字幕了 —— 短对话
  // 会出现 20s 空白再 poof 一次性全出的糟糕体验。
  // 现在的处理：
  //   1) liveAssistant.content 直接 overwrite 成最新累积文本 → 流式打字效果回来
  //   2) liveStatus 横幅 保留上一条非 MODEL_OUTPUT phase 的文本，不被 MODEL_OUTPUT 覆盖
  //   3) timeline 依然过滤 MODEL_OUTPUT（shouldShowTimelineEvent 里处理）
  // 这样既保留实时流式，又不刷屏到其他 UI 组件。

  if (normalized.type === "RUN_STATUS") {
    const raw = String(normalized.content || "");
    const phaseMatch = raw.match(/\bphase=([A-Z_]+)\b/);
    const phase = phaseMatch ? phaseMatch[1] : "";
    if (phase === "MODEL_OUTPUT") {
      const streamed = stripRunStatusPrefix(raw).trim();
      if (streamed) {
        const evtRunId = normalized.runId || state.currentRunId;
        // 从 content 里抽 step 编号(后端 RUN_STATUS 格式是 "step=N phase=MODEL_OUTPUT ...")。
        // 一个 run 会经历多个 tool loop iteration,每次 iteration 都会发自己的 MODEL_OUTPUT 累积流。
        // 如果 step 变了,说明上一轮 LLM 输出已收尾(后端已经或即将发起 tool 调用 / 进入下一轮),
        // 必须把当前 live 文字冻成一个独立 assistant 气泡再开新一轮,否则下一轮的 overwrite 会
        // 把自检 / 过渡说明 / 优化后报告等中间 turn 的内容全部吃掉。
        const stepMatch = raw.match(/\bstep=(\d+)\b/);
        const nextStep = stepMatch ? parseInt(stepMatch[1], 10) : null;
        const prevStep = state.liveAssistant.step;
        const sameRun = state.liveAssistant.runId === evtRunId;
        if (sameRun && prevStep !== null && nextStep !== null && nextStep !== prevStep && state.liveAssistant.content) {
          commitLiveAssistantAsBubble(evtRunId, prevStep, state.liveAssistant.content);
        }
        state.liveAssistant.runId = evtRunId;
        state.liveAssistant.content = streamed;
        if (nextStep !== null) state.liveAssistant.step = nextStep;
      }
    } else if (phase === "MODEL_RETRY") {
      // 超时重试会重新从头流，如果不清掉前一次的半截文本，UI 看起来是“字退回又往前涨”，
      // 非常像坏掉。清空 liveAssistant.content，只留重试 banner；新一轮 MODEL_OUTPUT 到来
      // 时会自然写满回去。
      if (state.liveAssistant.runId === (normalized.runId || state.currentRunId)) {
        state.liveAssistant.content = "";
      }
      state.liveStatus.runId = normalized.runId || state.currentRunId;
      state.liveStatus.phase = phase;
      state.liveStatus.text = stripRunStatusPrefix(raw);
      state.liveStatus.updatedAt = normalized.timestamp || new Date().toISOString();
    } else if (phase) {
      state.liveStatus.runId = normalized.runId || state.currentRunId;
      state.liveStatus.phase = phase;
      state.liveStatus.text = stripRunStatusPrefix(raw);
      state.liveStatus.updatedAt = normalized.timestamp || new Date().toISOString();
    }
  }

  if (normalized.type === "RUN_COMPLETED" || normalized.type === "RUN_FAILED") {
    state.liveStatus.phase = normalized.type === "RUN_FAILED" ? "FAILED" : "DONE";
    state.liveStatus.text = normalized.content || "";
  }

  if (normalized.type === "RUN_COMPLETED") {
    const completedRunId = normalized.runId || state.currentRunId;
    const liveContent = state.liveAssistant.runId === completedRunId ? state.liveAssistant.content : "";
    if (liveContent) {
      appendPendingMessage({
        id: `pending-assistant-${completedRunId}-${Date.now()}`,
        runId: completedRunId,
        role: "assistant",
        messageType: "text",
        content: liveContent,
        toolName: null,
        toolArgsJson: null,
        toolResultJson: null,
        seqNo: (transcriptMessages.value.at(-1)?.seqNo || 0) + 1,
        createdAt: new Date().toISOString()
      });
    } else {
      const fallbackHint = state.renderCompletionHintByRun[completedRunId];
      const hasAssistantMessage = transcriptMessages.value.some((message) =>
        message.runId === completedRunId
        && message.role === "assistant"
        && String(message.content || "").trim()
      );
      if (fallbackHint && !hasAssistantMessage) {
        appendPendingMessage({
          id: `pending-assistant-fallback-${completedRunId}-${Date.now()}`,
          runId: completedRunId,
          role: "assistant",
          messageType: "text",
          content: fallbackHint,
          toolName: null,
          toolArgsJson: null,
          toolResultJson: null,
          seqNo: (transcriptMessages.value.at(-1)?.seqNo || 0) + 1,
          createdAt: new Date().toISOString()
        });
      }
    }
    delete state.renderCompletionHintByRun[completedRunId];
    state.liveAssistant.runId = "";
    state.liveAssistant.content = "";
    state.liveAssistant.step = null;
  }

  if (normalized.type === "RUN_FAILED") {
    delete state.renderCompletionHintByRun[normalized.runId];
    state.liveAssistant.runId = "";
    state.liveAssistant.content = "";
    state.liveAssistant.step = null;
  }

  if (normalized.type === "APPROVAL_REQUIRED") {
    const approvalId = extractApprovalId(normalized.content);
    if (approvalId) {
      upsertApproval({
        id: approvalId,
        status: "PENDING",
        reason: normalized.content
      });
      loadApproval(approvalId);
    }
  }

  if (normalized.type === "TOOL_COMPLETED") {
    const renderMessage = toolEventRenderMessage(normalized);
    const renderPayload = toolEventRenderPayload(normalized);
    if (renderPayload) {
      // 表格 / 图表类 displayType 前端已不再渲染成独立卡片(统一到 markdown 正文),
      // 所以对应的 "表格已生成" / "图表已生成" hint 会误导用户。只留 markdown / 默认兜底。
      state.renderCompletionHintByRun[normalized.runId] = renderPayload.displayType === "markdown"
        ? "Markdown 文档已生成，请查看下方预览。"
        : "结果已生成。";
    }
    if (renderMessage && !hasPendingRenderedToolForRun(renderMessage.runId, renderMessage.toolResultJson)) {
      appendPendingMessage({
        ...renderMessage,
        id: `${renderMessage.id}-${Date.now()}`,
        seqNo: (transcriptMessages.value.at(-1)?.seqNo || 0) + 1
      });
    }
  }

  if (["TOOL_COMPLETED", "APPROVAL_REQUIRED", "RUN_COMPLETED", "RUN_FAILED"].includes(normalized.type)) {
    scheduleSessionRefresh(250);
  }

  if (["RUN_STATUS", "TOOL_COMPLETED", "RUN_COMPLETED", "RUN_FAILED"].includes(normalized.type)) {
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
    state.liveAssistant.step = null;
  }
}

function syncPendingMessagesWithSession() {
  if (!state.pendingMessages.length || !state.session?.messages?.length) {
    return;
  }
  state.pendingMessages = state.pendingMessages.filter((pending) =>
    !state.session.messages.some((message) => {
      if (message.runId !== pending.runId || message.role !== pending.role) {
        return false;
      }
      if (pending.role === "tool") {
        return message.toolName === pending.toolName
          && message.toolResultJson === pending.toolResultJson;
      }
      return message.content === pending.content;
    })
  );
}

function appendPendingMessage(message) {
  state.pendingMessages.push(message);
}

/**
 * 把 liveAssistant 的当前累积文字冻结成一条 pending assistant 气泡。在 tool loop 的 step
 * 切换点调用 —— 上一 step 的 LLM 自检 / 过渡说明 / 输出,都应该作为独立气泡留在聊天流里,
 * 不能被下一 step 的 overwrite 丢掉。每条 bubble 用 step 编号构造唯一 id 防重复提交。
 */
function commitLiveAssistantAsBubble(runId, step, content) {
  if (!content || !content.trim()) return;
  const id = `live-turn-${runId || "unknown"}-${step}`;
  const alreadyThere = state.pendingMessages.some((m) => m.id === id);
  if (alreadyThere) return;
  appendPendingMessage({
    id,
    runId,
    role: "assistant",
    messageType: "text",
    content,
    toolName: null,
    toolArgsJson: null,
    toolResultJson: null,
    seqNo: (transcriptMessages.value.at(-1)?.seqNo || 0) + 1,
    createdAt: new Date().toISOString()
  });
}

function upsertPendingMessage(message) {
  const index = state.pendingMessages.findIndex((item) => item.id === message.id);
  if (index >= 0) {
    state.pendingMessages[index] = {
      ...state.pendingMessages[index],
      ...message
    };
    return;
  }
  state.pendingMessages.push(message);
}

function isModelOutputStatus(content) {
  return /\bphase=MODEL_OUTPUT\b/i.test(String(content || ""));
}

function stripRunStatusPrefix(content) {
  return String(content || "")
    .replace(/^step=\d+\s*/i, "")
    .replace(/^phase=[A-Z_]+\s*/i, "")
    .trim();
}

function upsertModelOutputMessage(event) {
  const runId = event.runId || state.currentRunId;
  const content = stripRunStatusPrefix(event.content);
  if (!runId || !content) {
    return;
  }
  upsertPendingMessage({
    id: `pending-model-output-${runId}`,
    runId,
    role: "assistant",
    messageType: "text",
    content,
    toolName: null,
    toolArgsJson: null,
    toolResultJson: null,
    seqNo: (transcriptMessages.value.at(-1)?.seqNo || 0) + 1,
    createdAt: event.timestamp || new Date().toISOString()
  });
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
  const matched = text.match(/\n\s*\{/);
  if (!matched || matched.index === undefined) {
    return { summary: text, payload: "" };
  }
  const jsonStart = matched.index + matched[0].indexOf("{");
  const payload = extractBalancedJson(text, jsonStart);
  if (!payload || !parseJson(payload)) {
    return { summary: text, payload: "" };
  }
  return {
    summary: text.slice(0, jsonStart).trim(),
    payload
  };
}

function extractBalancedJson(text, startIndex) {
  if (startIndex < 0 || startIndex >= text.length || text[startIndex] !== "{") {
    return "";
  }
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = startIndex; i < text.length; i += 1) {
    const ch = text[i];
    if (inString) {
      if (escaped) {
        escaped = false;
        continue;
      }
      if (ch === "\\") {
        escaped = true;
        continue;
      }
      if (ch === "\"") {
        inString = false;
      }
      continue;
    }
    if (ch === "\"") {
      inString = true;
      continue;
    }
    if (ch === "{") {
      depth += 1;
      continue;
    }
    if (ch === "}") {
      depth -= 1;
      if (depth === 0) {
        return text.slice(startIndex, i + 1).trim();
      }
    }
  }
  return "";
}

function toolEventRenderPayload(event) {
  const parsedContent = parseToolEventContent(event?.content);
  const parsed = parseJson(parsedContent.payload);
  if (!parsed?.displayType) {
    console.warn("[ws.render.payload.missing]", {
      runId: event?.runId,
      type: event?.type,
      summary: parsedContent.summary,
      content: event?.content
    });
    return null;
  }
  return ["table", "echarts", "artifact", "markdown"].includes(parsed.displayType) ? parsed : null;
}

function toolNameForDisplayType(displayType) {
  if (displayType === "table") return "table.render";
  if (displayType === "echarts") return "chart.echarts";
  if (displayType === "markdown") return "artifact.markdown";
  return "artifact";
}

function toolEventRenderMessage(event, runIdOverride) {
  const payload = toolEventRenderPayload(event);
  if (!payload) {
    return null;
  }
  const normalizedRunId = runIdOverride || event.runId || state.currentRunId || "current";
  return {
    id: `event-render-${event.id}`,
    runId: normalizedRunId,
    role: "tool",
    messageType: "tool-result",
    content: parseToolEventContent(event.content).summary,
    toolName: toolNameForDisplayType(payload.displayType),
    toolArgsJson: null,
    toolResultJson: JSON.stringify(payload),
    seqNo: 0,
    createdAt: event.timestamp
  };
}

function toolEventRenderPayloadRaw(event) {
  return parseJson(parseToolEventContent(event?.content).payload);
}

function toolEventRenderMessageForView(event) {
  const payload = toolEventRenderPayload(event);
  if (!payload) {
    return null;
  }
  const runId = event?.runId || state.currentRunId || "current";
  return {
    id: `tool-event-${event?.id || Date.now()}`,
    runId,
    toolName: toolNameForDisplayType(payload.displayType),
    toolResultJson: JSON.stringify(payload)
  };
}

function parseJson(value) {
  try {
    return value ? JSON.parse(value) : null;
  } catch {
    return null;
  }
}

function cloneTransportItems(items) {
  return JSON.parse(JSON.stringify(Array.isArray(items) ? items : []));
}

function parseJsonArray(value) {
  const parsed = parseJson(value);
  return Array.isArray(parsed) ? parsed : [];
}

function parseLlmModelMappings(raw, fallbackModel = "") {
  try {
    const parsed = raw ? JSON.parse(raw) : { models: [] };
    const sourceModels = Array.isArray(parsed)
      ? parsed
      : Array.isArray(parsed?.models)
        ? parsed.models
        : [];
    const normalized = sourceModels
      .map((item) => ({
        displayName: String(item?.displayName || "").trim(),
        apiModel: String(item?.apiModel || "").trim()
      }))
      .map((item) => ({
        displayName: item.displayName || item.apiModel,
        apiModel: item.apiModel || item.displayName
      }))
      .filter((item) => item.apiModel);
    if (!normalized.length && fallbackModel) {
      normalized.push({ displayName: fallbackModel, apiModel: fallbackModel });
    }
    return normalized;
  } catch {
    return fallbackModel ? [{ displayName: fallbackModel, apiModel: fallbackModel }] : [];
  }
}

function syncSelectedRuntimeModel() {
  const models = selectedRuntimeLlmModels.value;
  if (!models.length) {
    form.llmModel = "";
    return;
  }
  const exists = models.some((item) => item.apiModel === form.llmModel);
  if (!exists) {
    form.llmModel = models[0].apiModel;
  }
}

function hasPendingRenderedToolForRun(runId, payloadJson) {
  return transcriptMessages.value.some((message) =>
    message.runId === runId
    && message.role === "tool"
    && message.toolResultJson === payloadJson
  );
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
  // 用户主动切会话 / 退出时 —— 必须先取消待定的重连 timer,否则老 session 的 run
  // 会在切换后几秒被重新 attach,把老事件串到新 UI 上。
  cancelReconnect();
  if (currentStream.value?.close) {
    currentStream.value.close();
  }
  if (currentStream.value instanceof WebSocket) {
    currentStream.value.close();
  }
  currentStream.value = null;
}

function armReconnect({ runId, sessionId, agentId, userId }) {
  if (reconnectCtl.timer) {
    clearTimeout(reconnectCtl.timer);
  }
  reconnectCtl.runId = runId || "";
  reconnectCtl.sessionId = sessionId || "";
  reconnectCtl.agentId = agentId || "";
  reconnectCtl.userId = userId || "anonymous";
  reconnectCtl.attempts = 0;
  reconnectCtl.cancelled = false;
  reconnectCtl.timer = null;
}

function cancelReconnect() {
  if (reconnectCtl.timer) {
    clearTimeout(reconnectCtl.timer);
    reconnectCtl.timer = null;
  }
  reconnectCtl.cancelled = true;
  reconnectCtl.runId = "";
}

// WS 断开后决定要不要重连。只在 run 仍然非终态时才重连 —— 否则 RUN_COMPLETED 后
// 服务端优雅关闭,我们不应再尝试 attach。用 getRun 查 DB 作为"run 是否还活着"的
// 权威来源,不依赖本地状态(本地 state.run 可能还没刷新到终态)。
async function onStreamClosedMaybeReconnect() {
  const ctl = reconnectCtl;
  if (ctl.cancelled || !ctl.runId) {
    return;
  }
  if (ctl.attempts >= RECONNECT_MAX_ATTEMPTS) {
    console.warn("[ws.reconnect.max_attempts_reached]", ctl.runId);
    state.error = "WebSocket 多次重连失败,请手动刷新";
    return;
  }
  try {
    const run = await getRun(ctl.runId);
    if (run && !isActiveRunStatus(run.status)) {
      console.log("[ws.reconnect.skip_terminal]", { runId: ctl.runId, status: run.status });
      // Run 已终止,走一遍正常的结束处理(刷新数据),然后不再重连。
      refreshRun();
      refreshSession();
      return;
    }
  } catch (error) {
    // getRun 失败可能就是后端挂了或网络抖了 —— 仍然继续尝试重连,失败 10 次才停。
    console.warn("[ws.reconnect.getRun_failed]", error?.message || error);
  }
  const delay = Math.min(1000 * (2 ** ctl.attempts), RECONNECT_MAX_DELAY_MS);
  ctl.attempts += 1;
  console.log("[ws.reconnect.scheduled]", { attempt: ctl.attempts, delayMs: delay, runId: ctl.runId });
  ctl.timer = setTimeout(() => {
    ctl.timer = null;
    if (ctl.cancelled || !ctl.runId) return;
    openAttachStream({
      runId: ctl.runId,
      sessionId: ctl.sessionId,
      agentId: ctl.agentId,
      userId: ctl.userId,
      isReconnect: true
    });
  }, delay);
}

// 真正创建 attach 流的单点 —— armReconnect 之后走这里,断线重连时也走这里。
function openAttachStream({ runId, sessionId, agentId, userId, isReconnect }) {
  const subscribedSessionId = sessionId;
  if (currentStream.value?.close) {
    try { currentStream.value.close(); } catch (_) { /* ignore */ }
  }
  currentStream.value = createWebSocketStream({
    sessionId,
    agentId: agentId || undefined,
    userId: userId || "anonymous",
    message: "",
    runId,
    attach: true,
    onOpen: () => {
      if (reconnectCtl.runId === runId) {
        reconnectCtl.attempts = 0;
      }
      if (isReconnect) {
        console.log("[ws.reconnect.success]", runId);
      }
      // 一旦真的连上,清掉先前可能误报的"连接中断"banner。
      if (state.error && /连接|流中断|重连/.test(state.error)) {
        state.error = "";
      }
    },
    onEvent: (event) => {
      if (event?.sessionId && subscribedSessionId && event.sessionId !== subscribedSessionId) {
        console.log("[ws.event.dropped_attach_closure]", {
          eventSessionId: event.sessionId,
          subscribedSessionId,
          type: event.type
        });
        return;
      }
      pushEvent(event);
      if (event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED" || event.type === "RUN_CANCELLED") {
        cancelReconnect();
        refreshRun();
        refreshSession();
        refreshMemories();
        refreshRecentSessions();
      }
    },
    onClose: () => {
      currentStream.value = null;
      onStreamClosedMaybeReconnect();
    },
    onError: () => {
      // WebSocket 协议本身在每次 close 前都会先 fire onerror —— 不直接当真。
      // 真正"无法连上 / 重连失败"会被 onClose + reconnectCtl.attempts 兜住,在那里报。
      console.warn("[ws.attach.error_swallowed]", { runId, isReconnect });
    }
  });
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

async function executeChatRequest(payload) {
  if (!hasAvailableLlms.value) {
    state.error = llmWarning.value;
    return;
  }
  if (currentStream.value) {
    state.error = "当前会话已有运行中的任务，请等待完成后再发送。";
    return;
  }
  const targetSessionId = payload.sessionId || form.sessionId;
  if (targetSessionId) {
    try {
      const activeRun = await getActiveRun(targetSessionId);
      if (activeRun && isActiveRunStatus(activeRun.status)) {
        attachWebSocketRun(activeRun);
        state.error = "当前会话已有运行中的任务，已恢复连接到正在执行的结果流。";
        return;
      }
    } catch (error) {
      state.error = error.message;
      return;
    }
  }
  loading.sending = true;
  state.error = "";
  clearStream();
  try {
    const outgoingMessage = payload.message;
    const outgoingReferences = cloneTransportItems(payload.references || []);
    const outgoingAttachments = cloneTransportItems(payload.attachments || []);
    const accepted = await sendChat({
      sessionId: payload.sessionId || undefined,
      userId: payload.userId || undefined,
      agentId: payload.agentId || undefined,
      llmConfigId: payload.llmConfigId || undefined,
      llmModel: payload.llmModel || undefined,
      message: outgoingMessage,
      references: outgoingReferences,
      attachments: outgoingAttachments
    });
    lastSentRequest.value = {
      message: outgoingMessage,
      references: outgoingReferences,
      attachments: outgoingAttachments
    };

    form.sessionId = accepted.sessionId;
    if (!payload.preserveComposer) {
      form.message = "";
      form.references = [];
      form.attachments = [];
      mentionPicker.visible = false;
      mentionPicker.query = "";
      mentionPicker.triggerIndex = -1;
      syncComposerEditor();
    }
    state.currentRunId = accepted.runId;
    state.resolvedAgentId = accepted.agentId;
    state.liveAssistant.runId = accepted.runId;
    state.liveAssistant.content = "";
    state.liveAssistant.step = null;
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
      llmModel: payload.llmModel || form.llmModel || accepted.llmModel || undefined,
      userId: form.userId || "anonymous",
      message: outgoingMessage,
      runId: accepted.runId
    };

    // 闭包层:记住这条 WS 订阅时的 sessionId,事件来了先对比 —— 挡住后端可能复用
    // 同一底层连接或网络层串包的极端情况,也防住 pushEvent 那层 form.sessionId
    // 被用户切换改动后窗口期的事件泄漏。
    const subscribedSessionId = streamPayload.sessionId;
    const gatedOnEvent = (event) => {
      if (event?.sessionId && subscribedSessionId && event.sessionId !== subscribedSessionId) {
        console.log("[ws.event.dropped_closure]", {
          eventSessionId: event.sessionId,
          subscribedSessionId,
          type: event.type
        });
        return;
      }
      pushEvent(event);
      if (event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED" || event.type === "RUN_CANCELLED") {
        cancelReconnect();
        refreshRun();
        refreshSession();
        refreshMemories();
        refreshRecentSessions();
      }
    };

    if (transport.value === "websocket") {
      // Arm reconnect state:这条 WS 意外断开后,WS onClose 会查 run 是否还活着;
      // 活着就指数退避重连(后续连接走 attach_existing 分支,拿 replay + live 流)。
      armReconnect({
        runId: accepted.runId,
        sessionId: accepted.sessionId,
        agentId: accepted.agentId,
        userId: streamPayload.userId
      });
      currentStream.value = createWebSocketStream({
        ...streamPayload,
        onOpen: () => {
          if (reconnectCtl.runId === accepted.runId) {
            reconnectCtl.attempts = 0;
          }
          if (state.error && /连接|流中断|重连/.test(state.error)) {
            state.error = "";
          }
        },
        onEvent: gatedOnEvent,
        onClose: () => {
          currentStream.value = null;
          onStreamClosedMaybeReconnect();
        },
        onError: () => {
          // 同上:WS 协议在每次 close 前先 fire error。重连判定全交给 onClose/reconnectCtl。
          console.warn("[ws.send.error_swallowed]", { runId: accepted.runId });
        }
      });
    } else {
      // SSE 路径当前不做自动重连,保持原有语义(浏览器 EventSource 自己会重试)。
      currentStream.value = createSseStream({
        ...streamPayload,
        onEvent: gatedOnEvent,
        onError: () => {
          // 浏览器 EventSource 在初次握手抖动时也会 fire error,但会自己重连。
          // 不做 banner —— 真正终端状态由 RUN_COMPLETED/FAILED/CANCELLED 事件触发刷新。
          console.warn("[sse.error_swallowed]", { runId: accepted.runId });
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

async function runChat() {
  await executeChatRequest({
    sessionId: form.sessionId,
    userId: form.userId,
    agentId: form.agentId,
    llmConfigId: form.llmConfigId,
    llmModel: form.llmModel,
    message: form.message,
    references: form.references,
    attachments: form.attachments,
    preserveComposer: false
  });
}

async function resendLastUserRequest() {
  if (!resendSourceRequest.value?.message?.trim()) {
    return;
  }
  const snapshot = resendSourceRequest.value;
  try {
    await executeChatRequest({
      sessionId: form.sessionId,
      userId: form.userId,
      agentId: form.agentId,
      llmConfigId: form.llmConfigId,
      llmModel: form.llmModel,
      message: snapshot.message,
      references: cloneTransportItems(snapshot.references || []),
      attachments: cloneTransportItems(snapshot.attachments || []),
      preserveComposer: true
    });
  } catch (error) {
    state.error = error.message;
  }
}

async function refreshSession() {
  if (!form.sessionId) return;
  loading.session = true;
  try {
    state.session = await getSession(form.sessionId);
    syncPendingMessagesWithSession();
    reconcileLiveAssistant();
    await Promise.all([
      refreshRecentSessions(),
      refreshRunStatuses()
    ]);
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.session = false;
  }
}

// 拉取当前会话下所有 run 的状态（已经过 StaleRunReconciler 修正）。
// 失败时不阻塞 refreshSession —— 只是 UI 里僵尸 run 可能继续显示"任务执行中"，不会造成功能故障。
async function refreshRunStatuses() {
  if (!form.sessionId) return;
  try {
    const runs = await listRunsBySession(form.sessionId);
    const map = {};
    for (const run of runs || []) {
      if (!run?.runId) continue;
      map[run.runId] = { status: run.status, detail: run.detail };
    }
    state.runsByRunId = map;
  } catch (error) {
    // 静默失败；不把 error 推到 state.error 污染主 UI
    console.warn("refreshRunStatuses failed:", error.message);
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
    // 历史 run 没有 PLAN_UPDATED 事件重放,纯靠 state.run.planJson 回灌。
    // 活 run 也会从 run_record 拉到最新一次 persister.sync 的快照,和 WebSocket 实时事件
    // 二选其一,哪个更晚哪个赢 —— 一般 PLAN_UPDATED 更实时,所以只在本地 steps 还是空时
    // 才用 planJson 兜底,避免刚收到的 in-flight snapshot 被稍晚刷新的 DB 快照覆盖。
    hydrateRunPlanFromDetail(state.run);
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.run = false;
  }
}

function hydrateRunPlanFromDetail(run) {
  if (!run || !run.planJson) return;
  if (state.runPlan.runId === run.runId && Array.isArray(state.runPlan.steps) && state.runPlan.steps.length > 0) {
    // 已经从 PLAN_UPDATED 实时事件拿到过 snapshot,不要被 DB 快照覆盖回旧状态
    return;
  }
  try {
    const snapshot = JSON.parse(run.planJson);
    if (snapshot && Array.isArray(snapshot.steps)) {
      state.runPlan.runId = run.runId;
      state.runPlan.title = snapshot.title || "";
      state.runPlan.steps = snapshot.steps;
      state.runPlan.updatedAt = run.updatedAt || "";
    }
  } catch (error) {
    console.warn("[runPlan.hydrate_failed]", error?.message || error);
  }
}

function isActiveRunStatus(status) {
  return [
    "RECEIVED",
    "CONTEXT_BUILT",
    "MODEL_RUNNING",
    "TOOL_REQUESTED",
    "TOOL_EXECUTING",
    "TOOL_RESULT_APPENDED",
    "WAITING_APPROVAL"
  ].includes(String(status || ""));
}

function attachWebSocketRun(run) {
  if (!run?.runId || currentStream.value) {
    return;
  }
  state.currentRunId = run.runId;
  state.run = run;
  state.resolvedAgentId = run.agentId || state.resolvedAgentId || form.agentId;
  searchForm.runId = run.runId;
  searchForm.sessionId = run.sessionId || form.sessionId;
  searchForm.agentId = run.agentId || searchForm.agentId;
  state.liveAssistant.runId = run.runId;
  const attachSessionId = run.sessionId || form.sessionId;
  const attachAgentId = run.agentId || form.agentId;
  const attachUserId = form.userId || run.userId || "anonymous";
  armReconnect({
    runId: run.runId,
    sessionId: attachSessionId,
    agentId: attachAgentId,
    userId: attachUserId
  });
  openAttachStream({
    runId: run.runId,
    sessionId: attachSessionId,
    agentId: attachAgentId,
    userId: attachUserId,
    isReconnect: false
  });
}

async function recoverActiveRunForSession() {
  if (!form.sessionId || currentStream.value) {
    return;
  }
  try {
    const activeRun = await getActiveRun(form.sessionId);
    if (activeRun && isActiveRunStatus(activeRun.status)) {
      attachWebSocketRun(activeRun);
    }
  } catch (error) {
    // 进入会话时的 active-run 探查失败不该提示给用户(404 / 网络抖动 / 鉴权过期都属于"页面级噪音")。
    // 真正 401 已经被 fetch 拦截派 auth:unauthorized 跳登录;其它情况静默,日志保留。
    console.warn("[recover.active_run.failed]", form.sessionId, error?.message || error);
  }
}

async function refreshMemories() {
  const agentId = state.resolvedAgentId || form.agentId;
  if (!agentId) return;
  loading.memories = true;
  try {
    // 切换到 admin 端点 —— 返回带 scope / updatedAt 的完整结构，支持前端 pin/unpin/edit
    state.memories = await listMemoryNotesAdmin(agentId);
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.memories = false;
  }
}

const memoryEditor = reactive({
  visible: false,
  noteId: null,
  agentId: "",
  sessionId: "",
  scope: "agent",
  source: "manual",
  content: ""
});

function openMemoryEditor(existing) {
  const agentId = state.resolvedAgentId || form.agentId || "dev-agent";
  if (existing) {
    memoryEditor.noteId = existing.id;
    memoryEditor.agentId = existing.agentId || agentId;
    memoryEditor.sessionId = existing.sessionId || "";
    memoryEditor.scope = existing.scope || "agent";
    memoryEditor.source = existing.source || "manual";
    memoryEditor.content = existing.content || "";
  } else {
    memoryEditor.noteId = null;
    memoryEditor.agentId = agentId;
    memoryEditor.sessionId = "";
    memoryEditor.scope = "agent";
    memoryEditor.source = "manual";
    memoryEditor.content = "";
  }
  memoryEditor.visible = true;
}

function closeMemoryEditor() {
  memoryEditor.visible = false;
  memoryEditor.noteId = null;
  memoryEditor.content = "";
}

async function submitMemoryEditor() {
  if (!memoryEditor.content.trim()) {
    state.error = "备注内容不能为空";
    return;
  }
  try {
    await saveMemoryNoteAdmin({
      id: memoryEditor.noteId,
      agentId: memoryEditor.agentId,
      sessionId: memoryEditor.sessionId || null,
      scope: memoryEditor.scope,
      source: memoryEditor.source,
      content: memoryEditor.content
    });
    closeMemoryEditor();
    await refreshMemories();
  } catch (error) {
    state.error = error.message;
  }
}

async function toggleMemoryScope(note) {
  // One-click pin/unpin：scope='session' ↔ 'agent'
  const nextScope = note.scope === "agent" ? "session" : "agent";
  try {
    await saveMemoryNoteAdmin({
      id: note.id,
      agentId: note.agentId,
      sessionId: note.sessionId,
      scope: nextScope,
      source: note.source,
      content: note.content
    });
    await refreshMemories();
  } catch (error) {
    state.error = error.message;
  }
}

async function removeMemoryNote(note) {
  if (!confirm(`删除这条 memory note？\n\n${note.content.slice(0, 120)}`)) {
    return;
  }
  try {
    await deleteMemoryNoteAdmin(note.id);
    await refreshMemories();
  } catch (error) {
    state.error = error.message;
  }
}

const agentEditor = reactive({
  visible: false,
  isNew: false,
  agentId: "",
  displayName: "",
  description: "",
  systemPrompt: "",
  agentMarkdown: "",
  memoryMarkdown: "",
  enabled: true
});

function openAgentEditor(existing) {
  if (existing) {
    agentEditor.isNew = false;
    agentEditor.agentId = existing.agentId;
    agentEditor.displayName = existing.displayName || "";
    agentEditor.description = existing.description || "";
    agentEditor.systemPrompt = existing.systemPrompt || "";
    agentEditor.agentMarkdown = existing.agentMarkdown || "";
    agentEditor.memoryMarkdown = existing.memoryMarkdown || "";
    agentEditor.enabled = existing.enabled;
  } else {
    agentEditor.isNew = true;
    agentEditor.agentId = "";
    agentEditor.displayName = "";
    agentEditor.description = "";
    agentEditor.systemPrompt = "";
    agentEditor.agentMarkdown = "";
    agentEditor.memoryMarkdown = "";
    agentEditor.enabled = true;
  }
  agentEditor.visible = true;
}

function closeAgentEditor() {
  agentEditor.visible = false;
}

async function submitAgentEditor() {
  if (!agentEditor.agentId.trim()) {
    state.error = "agentId 不能为空";
    return;
  }
  try {
    await saveAgentDefinitionAdmin({
      agentId: agentEditor.agentId.trim(),
      displayName: agentEditor.displayName,
      description: agentEditor.description,
      systemPrompt: agentEditor.systemPrompt,
      agentMarkdown: agentEditor.agentMarkdown,
      memoryMarkdown: agentEditor.memoryMarkdown,
      enabled: agentEditor.enabled
    });
    closeAgentEditor();
    await refreshCatalog();
    agents.value = await listAgents();
  } catch (error) {
    state.error = error.message;
  }
}

async function removeAgentDefinition(agent) {
  if (!confirm(`确认删除 agent "${agent.agentId}"？\n\n会话历史/skill/knowledge 记录不会删，但会指向一个不存在的 agent。`)) {
    return;
  }
  try {
    await deleteAgentDefinitionAdmin(agent.agentId);
    await refreshCatalog();
    agents.value = await listAgents();
  } catch (error) {
    state.error = error.message;
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
  loading.catalog = true;
  try {
    state.catalog.llms = await listAdminLlms();
    state.catalog.datasources = await listDatasources();
    state.catalog.agents = await listAgentDefinitionsAdmin();
    const agentId = state.resolvedAgentId || form.agentId;
    if (agentId) {
      const [knowledge, tools, skills] = await Promise.all([
        listKnowledgeEntries(agentId),
        listToolDefinitions(agentId),
        listSkillDefinitions(agentId)
      ]);
      state.catalog.knowledge = knowledge;
      state.catalog.tools = tools;
      state.catalog.skills = skills;
    }
    // 管理后台数据按权限懒加载,缺权限直接放空数组,后端 PermissionGate 也会兜。
    await refreshAdminCatalog();
  } catch (error) {
    state.error = error.message;
  } finally {
    loading.catalog = false;
  }
}

// P4-B 管理后台:按 authUser.permissions 选择性拉取列表;失败一项不阻塞其它。
async function refreshAdminCatalog() {
  const perms = new Set(authUser.permissions || []);
  const tasks = [];
  if (perms.has("user.manage")) {
    tasks.push(adminListUsers().then((rows) => { state.catalog.users = rows; }).catch(() => {}));
  }
  if (perms.has("tenant.manage")) {
    tasks.push(adminListTenants().then((rows) => { state.catalog.tenants = rows; }).catch(() => {}));
  }
  if (perms.has("permission.manage")) {
    tasks.push(adminListRoles(null).then((rows) => { state.catalog.roles = rows; }).catch(() => {}));
    tasks.push(adminListPermissions().then((rows) => { state.catalog.permissions = rows; }).catch(() => {}));
  }
  if (perms.has("oauth.client.manage")) {
    tasks.push(adminListOauthClients().then((rows) => { state.catalog.oauthClients = rows; }).catch(() => {}));
  }
  await Promise.all(tasks);
}

// 最近会话抽屉:点会话窗口右上角的按钮 → 右侧滑出列表;选中会话或新建后自动收起。
const recentSessionsDrawer = reactive({ open: false });
function openRecentSessionsDrawer() { recentSessionsDrawer.open = true; }
function closeRecentSessionsDrawer() { recentSessionsDrawer.open = false; }
function toggleRecentSessionsDrawer() { recentSessionsDrawer.open = !recentSessionsDrawer.open; }
function openSessionFromDrawer(session) {
  closeRecentSessionsDrawer();
  openSession(session);
}
function createNewSessionFromDrawer() {
  closeRecentSessionsDrawer();
  createNewSession();
}

// 管理后台对话框状态。type 决定渲染哪种表单;每页"新建/编辑"都先打开 modal。
// open(...) 顺手把目标 form 重置/回填,close() 直接清 type 即可。
const adminModal = reactive({ type: null });
function openAdminModal(type) { adminModal.type = type; }
function closeAdminModal() { adminModal.type = null; }

// "敏感值一次性显示"对话框 —— 临时密码 / OAuth secret 等只显示一次的字符串走这里,
// 用户可以一键复制。比 alert() 强的地方:文本可选、可复制、可读多行说明。
const secretModal = reactive({ open: false, title: "", message: "", secret: "", copied: false });
function showSecretModal({ title, message, secret }) {
  secretModal.open = true;
  secretModal.title = title || "请保存以下内容";
  secretModal.message = message || "";
  secretModal.secret = secret || "";
  secretModal.copied = false;
}
function closeSecretModal() {
  secretModal.open = false;
  secretModal.secret = "";
  secretModal.message = "";
  secretModal.copied = false;
}
async function copySecret() {
  try {
    await navigator.clipboard.writeText(secretModal.secret);
    secretModal.copied = true;
    setTimeout(() => { secretModal.copied = false; }, 1500);
  } catch (error) {
    // 退化方案:不能用 clipboard API 时手动选中(罕见,无 https 时会触发)
    state.error = "复制失败,请手动选中复制:" + (error?.message || error);
  }
}

// 管理后台简易表单状态。每个页面一个 form,提交即创建/更新,另起 prompt 行作 inline 编辑入口。
const userForm = reactive({ username: "", email: "", displayName: "", preferredTenantId: "" });
const tenantForm = reactive({ id: "", code: "", name: "", kind: "TENANT", status: "ACTIVE", description: "" });
const roleForm = reactive({ id: "", tenantId: "", code: "", name: "", description: "" });
const oauthClientForm = reactive({
  clientId: "", displayName: "", redirectUris: "[]", scopes: "[]",
  status: "ACTIVE", ownerUserId: "", description: ""
});

function openCreateUserModal() {
  Object.assign(userForm, { username: "", email: "", displayName: "", preferredTenantId: "" });
  openAdminModal("user");
}

async function submitUserCreate() {
  if (!userForm.username.trim()) { state.error = "username 必填"; return; }
  try {
    const res = await adminCreateUser({
      username: userForm.username.trim(),
      email: userForm.email.trim() || null,
      displayName: userForm.displayName.trim() || userForm.username.trim(),
      preferredTenantId: userForm.preferredTenantId.trim() || null
    });
    closeAdminModal();
    showSecretModal({
      title: "用户已创建",
      message: `用户名:${res.user.username}。请通过安全渠道把下面这串临时密码发给他/她,首次登录后必须改密。这个值只显示一次。`,
      secret: res.temporaryPassword
    });
    Object.assign(userForm, { username: "", email: "", displayName: "", preferredTenantId: "" });
    await refreshAdminCatalog();
  } catch (error) { state.error = error.message; }
}

function openImportUsersModal() {
  importForm.results = [];
  importForm.raw = "";
  openAdminModal("import");
}

// 批量导入。textarea 内容支持两种格式:
//   - JSON 数组:[{"username":"a","email":"a@x"}, ...]
//   - CSV(无表头):username,email,displayName,preferredTenantId  每行一条
const importForm = reactive({ raw: "", busy: false, results: [] });

function parseImportRows(raw) {
  const text = (raw || "").trim();
  if (!text) return [];
  if (text.startsWith("[")) {
    return JSON.parse(text);
  }
  return text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => {
    const [username, email, displayName, preferredTenantId] = line.split(",").map((s) => (s || "").trim());
    return { username, email: email || null, displayName: displayName || null, preferredTenantId: preferredTenantId || null };
  });
}

async function submitBatchImport() {
  let rows;
  try { rows = parseImportRows(importForm.raw); }
  catch (error) { state.error = "解析失败:" + error.message; return; }
  if (!rows.length) { state.error = "请输入至少一条记录"; return; }
  importForm.busy = true;
  importForm.results = [];
  try {
    importForm.results = await adminImportUsers(rows);
    await refreshAdminCatalog();
  } catch (error) {
    state.error = error.message;
  } finally {
    importForm.busy = false;
  }
}

async function resetUserPasswordHandler(userId) {
  if (!confirm(`确定要为用户 ${userId} 重置密码吗？`)) return;
  try {
    const res = await adminResetUserPassword(userId);
    showSecretModal({
      title: `已重置 ${userId} 的密码`,
      message: "新的临时密码只显示一次,请立即复制后通过安全渠道转交给用户。下次登录会被强制改密。",
      secret: res.temporaryPassword
    });
  } catch (error) { state.error = error.message; }
}

async function deleteUserHandler(userId) {
  if (!confirm(`确定删除用户 ${userId}？此操作不可恢复。`)) return;
  try {
    await adminDeleteUser(userId);
    await refreshAdminCatalog();
  } catch (error) { state.error = error.message; }
}

// 分配角色弹窗状态:用户 / 选中的租户 / 该租户下所有可选角色 / 已勾的 role id
const assignRolesModal = reactive({
  open: false,
  user: null,
  tenantId: "",
  availableRoles: [],
  selectedRoleIds: [],
  loading: false
});

async function openAssignRolesModal(user) {
  assignRolesModal.user = user;
  // 默认租户:超管挑用户的 preferred,租户管理员只能在自己租户操作
  const isSuper = (authUser.permissions || []).includes("session.read.all");
  assignRolesModal.tenantId = isSuper
    ? (user.preferredTenantId || authUser.activeTenantId || "system")
    : authUser.activeTenantId;
  assignRolesModal.open = true;
  await loadAssignRolesData();
}

async function loadAssignRolesData() {
  if (!assignRolesModal.user || !assignRolesModal.tenantId) return;
  assignRolesModal.loading = true;
  try {
    const [roles, bindings] = await Promise.all([
      adminListRoles(assignRolesModal.tenantId),
      adminListUserTenantRoles(assignRolesModal.user.id)
    ]);
    assignRolesModal.availableRoles = roles;
    assignRolesModal.selectedRoleIds = bindings
      .filter((b) => b.tenantId === assignRolesModal.tenantId)
      .map((b) => b.roleId);
  } catch (error) {
    state.error = error.message;
  } finally {
    assignRolesModal.loading = false;
  }
}

function closeAssignRolesModal() {
  assignRolesModal.open = false;
  assignRolesModal.user = null;
  assignRolesModal.availableRoles = [];
  assignRolesModal.selectedRoleIds = [];
}

function toggleAssignRole(roleId) {
  const idx = assignRolesModal.selectedRoleIds.indexOf(roleId);
  if (idx >= 0) assignRolesModal.selectedRoleIds.splice(idx, 1);
  else assignRolesModal.selectedRoleIds.push(roleId);
}

async function submitAssignRoles() {
  if (!assignRolesModal.user || !assignRolesModal.tenantId) return;
  try {
    await adminAssignRoles(
      assignRolesModal.user.id,
      assignRolesModal.tenantId,
      assignRolesModal.selectedRoleIds
    );
    closeAssignRolesModal();
    await refreshAdminCatalog();
  } catch (error) { state.error = error.message; }
}

// "编辑角色权限"也走多选 checkbox。一次加载,本地切换,提交时整体覆盖。
const editRolePermsModal = reactive({
  open: false,
  role: null,
  selectedCodes: [],
  loading: false,
  filter: ""
});

// 按 category 聚合 + 应用 filter,给模板渲染分段视图。
const groupedPermissions = computed(() => {
  const q = (editRolePermsModal.filter || "").trim().toLowerCase();
  const matches = state.catalog.permissions.filter((p) => {
    if (!q) return true;
    return (p.code || "").toLowerCase().includes(q)
        || (p.description || "").toLowerCase().includes(q);
  });
  const order = ["menu", "data", "admin", "system"];
  const groups = new Map();
  for (const p of matches) {
    const key = p.category || "其他";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(p);
  }
  // 按预设顺序输出,排序外的留尾
  const ordered = [];
  for (const k of order) if (groups.has(k)) ordered.push({ title: k, items: groups.get(k) });
  for (const [k, items] of groups) if (!order.includes(k)) ordered.push({ title: k, items });
  return ordered;
});

function selectAllPermissions() {
  editRolePermsModal.selectedCodes = state.catalog.permissions.map((p) => p.code);
}
function clearAllPermissions() {
  editRolePermsModal.selectedCodes = [];
}
function selectVisiblePermissions() {
  const visible = groupedPermissions.value.flatMap((g) => g.items.map((p) => p.code));
  const set = new Set([...editRolePermsModal.selectedCodes, ...visible]);
  editRolePermsModal.selectedCodes = Array.from(set);
}

async function openEditRolePermsModal(role) {
  editRolePermsModal.role = role;
  editRolePermsModal.open = true;
  editRolePermsModal.loading = true;
  try {
    const current = await adminListRolePermissions(role.id);
    editRolePermsModal.selectedCodes = current.map((c) => c.permissionCode);
  } catch (error) {
    state.error = error.message;
    editRolePermsModal.selectedCodes = [];
  } finally {
    editRolePermsModal.loading = false;
  }
}

function closeEditRolePermsModal() {
  editRolePermsModal.open = false;
  editRolePermsModal.role = null;
  editRolePermsModal.selectedCodes = [];
  editRolePermsModal.filter = "";
}

function togglePermissionCode(code) {
  const idx = editRolePermsModal.selectedCodes.indexOf(code);
  if (idx >= 0) editRolePermsModal.selectedCodes.splice(idx, 1);
  else editRolePermsModal.selectedCodes.push(code);
}

async function submitEditRolePerms() {
  if (!editRolePermsModal.role) return;
  try {
    await adminUpdateRolePermissions(editRolePermsModal.role.id, editRolePermsModal.selectedCodes);
    closeEditRolePermsModal();
    await refreshAdminCatalog();
  } catch (error) { state.error = error.message; }
}

// 用现成的多选弹窗代替 prompt;入口仍叫这名字以免改模板。
function assignUserRolesHandler(userOrId) {
  const user = typeof userOrId === "string"
    ? state.catalog.users.find((u) => u.id === userOrId)
    : userOrId;
  if (!user) { state.error = "找不到用户"; return; }
  openAssignRolesModal(user);
}

function openCreateTenantModal() {
  Object.assign(tenantForm, { id: "", code: "", name: "", kind: "TENANT", status: "ACTIVE", description: "" });
  openAdminModal("tenant");
}

async function submitTenantSave() {
  if (!tenantForm.code.trim() || !tenantForm.name.trim()) { state.error = "code/name 必填"; return; }
  try {
    await adminSaveTenant({ ...tenantForm });
    closeAdminModal();
    Object.assign(tenantForm, { id: "", code: "", name: "", kind: "TENANT", status: "ACTIVE", description: "" });
    await refreshAdminCatalog();
  } catch (error) { state.error = error.message; }
}

async function deleteTenantHandler(id) {
  if (!confirm(`确定删除租户 ${id}？关联用户绑定也会受影响。`)) return;
  try {
    await adminDeleteTenant(id);
    await refreshAdminCatalog();
  } catch (error) { state.error = error.message; }
}

function editTenantHandler(item) {
  Object.assign(tenantForm, {
    id: item.id, code: item.code, name: item.name, kind: item.kind,
    status: item.status, description: item.description || ""
  });
  openAdminModal("tenant");
}

function openCreateRoleModal() {
  Object.assign(roleForm, { id: "", tenantId: "", code: "", name: "", description: "" });
  openAdminModal("role");
}

async function submitRoleSave() {
  if (!roleForm.tenantId.trim() || !roleForm.code.trim() || !roleForm.name.trim()) {
    state.error = "tenantId/code/name 必填"; return;
  }
  try {
    await adminSaveRole({ ...roleForm });
    closeAdminModal();
    Object.assign(roleForm, { id: "", tenantId: "", code: "", name: "", description: "" });
    await refreshAdminCatalog();
  } catch (error) { state.error = error.message; }
}

async function deleteRoleHandler(id) {
  if (!confirm(`确定删除角色 ${id}？已绑定到此角色的用户在该租户下会失去这些权限。`)) return;
  try {
    await adminDeleteRole(id);
    await refreshAdminCatalog();
  } catch (error) { state.error = error.message; }
}

function editRolePermissionsHandler(roleOrId) {
  const role = typeof roleOrId === "string"
    ? state.catalog.roles.find((r) => r.id === roleOrId)
    : roleOrId;
  if (!role) { state.error = "找不到角色"; return; }
  openEditRolePermsModal(role);
}

function editRoleHandler(item) {
  Object.assign(roleForm, {
    id: item.id, tenantId: item.tenantId, code: item.code, name: item.name,
    description: item.description || ""
  });
  openAdminModal("role");
}

function openCreateOauthClientModal() {
  Object.assign(oauthClientForm, {
    clientId: "", displayName: "", redirectUris: "[]", scopes: "[]",
    status: "ACTIVE", ownerUserId: "", description: ""
  });
  openAdminModal("oauth-client");
}

async function submitOauthClientSave() {
  if (!oauthClientForm.displayName.trim()) { state.error = "displayName 必填"; return; }
  try {
    if (oauthClientForm.clientId && state.catalog.oauthClients.some((c) => c.clientId === oauthClientForm.clientId)) {
      // 更新现有
      await adminUpdateOauthClient(oauthClientForm.clientId, { ...oauthClientForm });
      closeAdminModal();
      Object.assign(oauthClientForm, {
        clientId: "", displayName: "", redirectUris: "[]", scopes: "[]",
        status: "ACTIVE", ownerUserId: "", description: ""
      });
      await refreshAdminCatalog();
    } else {
      // 新建
      const res = await adminCreateOauthClient({ ...oauthClientForm });
      closeAdminModal();
      showSecretModal({
        title: "OAuth 客户端已创建",
        message: `client_id: ${res.client.clientId}\n下面是 client_secret,只显示一次,请立即复制保存。丢失后只能重新生成一份。`,
        secret: res.clientSecret
      });
      Object.assign(oauthClientForm, {
        clientId: "", displayName: "", redirectUris: "[]", scopes: "[]",
        status: "ACTIVE", ownerUserId: "", description: ""
      });
      await refreshAdminCatalog();
    }
  } catch (error) { state.error = error.message; }
}

async function rotateOauthSecretHandler(clientId) {
  if (!confirm(`重新生成 ${clientId} 的 client_secret 会立刻使旧 secret 失效。继续?`)) return;
  try {
    const res = await adminRotateOauthClientSecret(clientId);
    showSecretModal({
      title: `已重新生成 ${clientId} 的 secret`,
      message: "新 client_secret 只显示一次,请立即复制并替换到接入方配置。旧 secret 已立即失效。",
      secret: res.clientSecret
    });
  } catch (error) { state.error = error.message; }
}

async function deleteOauthClientHandler(clientId) {
  if (!confirm(`删除 OAuth 客户端 ${clientId}?`)) return;
  try {
    await adminDeleteOauthClient(clientId);
    await refreshAdminCatalog();
  } catch (error) { state.error = error.message; }
}

function editOauthClientHandler(item) {
  Object.assign(oauthClientForm, {
    clientId: item.clientId, displayName: item.displayName,
    redirectUris: item.redirectUris, scopes: item.scopes,
    status: item.status, ownerUserId: item.ownerUserId || "",
    description: item.description || ""
  });
  openAdminModal("oauth-client");
}

async function saveDatasourceEntry(entry) {
  try {
    await saveDatasource(entry);
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

async function deleteDatasourceEntry(id) {
  if (!confirm("确定删除这个数据源吗？依赖它的 skill 调用会立即失败。")) return;
  try {
    await deleteDatasource(id);
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
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

// --------------------- Auth helpers (P2) -------------------------------
async function refreshWhoami() {
  try {
    const info = await whoami();
    Object.assign(authUser, {
      loaded: true,
      anonymous: !!info.anonymous,
      userId: info.userId || "",
      username: info.username || "",
      displayName: info.displayName || "",
      email: info.email || "",
      passwordMustChange: !!info.passwordMustChange,
      activeTenantId: info.activeTenantId || "",
      tenants: Array.isArray(info.tenants) ? info.tenants : [],
      permissions: Array.isArray(info.permissions) ? info.permissions : Array.from(info.permissions || []),
      menus: Array.isArray(info.menus) ? info.menus : []
    });
  } catch (error) {
    // whoami 失败(401/网络)时回退为匿名;登录页本身不会受影响
    authUser.loaded = true;
    authUser.anonymous = true;
    console.warn("whoami failed:", error?.message || error);
  }
  // P4: 后端关掉匿名以后,匿名用户进任何非 /login 路由都自动跳登录页。
  // 已经在 /login 上就别再 push 了,免得 router 报 navigation duplicated。
  if (authUser.anonymous && route.name !== "login") {
    router.replace({ name: "login" });
  }
}

async function submitLogin() {
  if (!loginForm.username.trim() || !loginForm.password) {
    loginForm.error = "用户名和密码必填";
    return;
  }
  loginForm.submitting = true;
  loginForm.error = "";
  try {
    await apiLogin(loginForm.username.trim(), loginForm.password);
    await refreshWhoami();
    loginForm.password = "";
    // 模板首先按 route.name === 'login' 判断;不跳走的话改密页永远不会渲染。
    // 一律 replace 到 /chat —— 改密 shell 由 passwordMustChange 自己接管。
    router.replace({ name: "chat" });
  } catch (error) {
    loginForm.error = error?.message || String(error);
  } finally {
    loginForm.submitting = false;
  }
}

async function submitLogout() {
  await apiLogout();
  await refreshWhoami();
  router.replace({ name: "login" });
}

async function doSwitchTenant(tenantId) {
  if (!tenantId || tenantId === authUser.activeTenantId) return;
  try {
    await apiSwitchTenant(tenantId);
    await refreshWhoami();
    // 切租户会改变可见的 agents/skills,刷一下目录
    await refreshCatalog();
    await refreshMemories();
  } catch (error) {
    state.error = error?.message || String(error);
  }
}

async function submitChangePassword() {
  if (!passwordChangeForm.oldPassword || !passwordChangeForm.newPassword) {
    passwordChangeForm.error = "请填写旧密码和新密码";
    return;
  }
  if (passwordChangeForm.newPassword.length < 8) {
    passwordChangeForm.error = "新密码至少 8 位";
    return;
  }
  passwordChangeForm.submitting = true;
  passwordChangeForm.error = "";
  try {
    await apiChangePassword(passwordChangeForm.oldPassword, passwordChangeForm.newPassword);
    await refreshWhoami();
    passwordChangeForm.oldPassword = "";
    passwordChangeForm.newPassword = "";
    router.replace({ name: "chat" });
  } catch (error) {
    passwordChangeForm.error = error?.message || String(error);
  } finally {
    passwordChangeForm.submitting = false;
  }
}

// 任何 fetch 收到 401 都走这里 —— 立刻清掉认证态,跳到 /login。
// 这条路径覆盖"cookie 过期"/"后端被重启 JWT secret 没变但 token 过期"等场景。
function handleUnauthorized() {
  if (authUser.anonymous && route.name === "login") return;
  authUser.loaded = true;
  authUser.anonymous = true;
  authUser.userId = "";
  authUser.username = "";
  authUser.displayName = "";
  authUser.email = "";
  authUser.passwordMustChange = false;
  authUser.tenants = [];
  authUser.permissions = [];
  authUser.menus = [];
  if (route.name !== "login") {
    router.replace({ name: "login" });
  }
}

onMounted(async () => {
  window.addEventListener("auth:unauthorized", handleUnauthorized);
  syncStateFromRoute();
  await refreshWhoami();
  // 匿名 + 强制登录场景下,refreshWhoami 已经把路由 replace 到 /login 了;
  // 这里直接 return,不要再启动 bootstrap / 轮询,免得在登录页就把后端打 401 一遍。
  if (authUser.anonymous) {
    return;
  }
  await bootstrap();
  if (form.sessionId) {
    await refreshSession();
    await refreshApprovals();
    await recoverActiveRunForSession();
  }
  await refreshMemories();
  await refreshCatalog();
  await refreshActiveRuns();
  // Poll every 4s — cheap (just the list endpoint) and keeps the dock honest.
  activeRunsTimer = setInterval(refreshActiveRuns, 4000);
  window.addEventListener("click", closeContextMenu);
  syncComposerEditor();
  // 首次挂载完,把会话流滚到底部 —— 历史消息列表默认停在最新一条,用户进来就看到当前对话。
  await nextTick();
  scrollConversationToBottom({ force: true });
});

// 新消息 / 流式字幕增量 / 新事件 任一触发,都按"用户在底部附近就跟"的策略自动滚动。
// 切会话时 transcriptMessages 会整批替换,同样走这条路径,nextTick 后 DOM 已更新再滚;
// sessionChanged 情况下强制滚,不管用户此刻滚动条在哪。
watch(
  () => [
    transcriptMessages.value.length,
    state.liveAssistant.content,
    state.events.length,
    form.sessionId
  ],
  async (next, prev) => {
    const sessionChanged = !!prev && next[3] !== prev[3];
    await nextTick();
    scrollConversationToBottom({ force: sessionChanged });
  }
);

async function refreshActiveRuns() {
  try {
    activeRuns.value = await listActiveRuns();
  } catch (error) {
    // Don't spam console for transient network blips — just keep last snapshot.
  }
}

async function terminateRun(runId) {
  if (!runId) return;
  const ok = window.confirm(`确定要终止 run ${runId.slice(0, 8)}? 后端会在下一轮循环检查时优雅退出。`);
  if (!ok) return;
  try {
    const result = await cancelRun(runId);
    await refreshActiveRuns();
    const detail = result?.message || (result?.signalled ? "cancel 信号已投递" : "已强制 DB 状态");
    console.info("run.cancel", runId, detail);
  } catch (error) {
    window.alert(`终止失败: ${error.message || error}`);
  }
}

function shortRunLabel(run) {
  const suffix = (run?.runId || "").slice(0, 8);
  const agent = run?.agentId || "?";
  return `${agent} · ${suffix}`;
}

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
      clearStream();
      state.currentRunId = "";
      state.pendingMessages = [];
      state.liveAssistant.runId = "";
      state.liveAssistant.content = "";
      await refreshSession();
      await refreshApprovals();
      await recoverActiveRunForSession();
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

watch(
  () => [form.llmConfigId, llms.value],
  () => {
    syncSelectedRuntimeModel();
  },
  { deep: true }
);

// 切会话时必须清干净的 session 维度 state —— 以前漏了 events / runPlan / liveStatus /
// runsByRunId / renderCompletionHintByRun,打开新会话时左下方还能看到上个会话的计划面板、
// 状态横幅、timeline 事件,非常混乱。统一放这里,openSession / createNewSession 都调用,
// 避免两条路径不一致。
// 这里只清"和 session / run 绑定"的 state;form 本身的 sessionId / agentId 由调用方按场景设。
function resetSessionScopedState() {
  state.currentRunId = "";
  state.resolvedAgentId = "";
  state.events = [];
  state.pendingMessages = [];
  state.renderCompletionHintByRun = {};
  state.run = null;
  state.session = null;
  state.runsByRunId = {};
  state.liveAssistant.runId = "";
  state.liveAssistant.content = "";
  state.liveAssistant.step = null;
  state.liveStatus.runId = "";
  state.liveStatus.phase = "";
  state.liveStatus.text = "";
  state.liveStatus.updatedAt = "";
  state.runPlan.runId = "";
  state.runPlan.title = "";
  state.runPlan.steps = [];
  state.runPlan.updatedAt = "";
  state.runPlan.collapsed = false;
  searchForm.runId = "";
  // followBottom 重置为 true,新会话进来先滚到底
  followBottom.value = true;
}

function openSession(session) {
  clearStream();
  resetSessionScopedState();
  form.sessionId = session.sessionId;
  form.agentId = session.agentId;
  state.resolvedAgentId = session.agentId;
  searchForm.sessionId = session.sessionId;
  searchForm.agentId = session.agentId;
  navigateToMenu("chat", { sessionId: session.sessionId, agentId: session.agentId });
  refreshSession().then(recoverActiveRunForSession);
  refreshApprovals();
}

function createNewSession() {
  clearStream();
  resetSessionScopedState();
  form.sessionId = "";
  form.message = "";
  form.references = [];
  form.attachments = [];
  searchForm.sessionId = "";
  syncComposerEditor();
  navigateToMenu("chat", { sessionId: undefined });
}

function resetLlmForm() {
  Object.assign(llmForm, {
    id: "",
    provider: "openai-compatible",
    displayName: "",
    model: "",
    modelMappingJson: "{\n  \"models\": []\n}",
    baseUrl: "",
    apiKey: "",
    chatPath: "/chat/completions",
    stream: true,
    enabled: true,
    defaultConfig: false
  });
  llmModelRows.value = [];
}

function resetKnowledgeForm() {
  Object.assign(knowledgeForm, { id: "", title: "", content: "", contentType: "markdown", source: "database", tagsJson: "[]", enabled: true });
}

function resetToolForm() {
  Object.assign(toolForm, { id: "", toolName: "", displayName: "", description: "", schemaJson: "{}", toolType: "builtin", configJson: "{}", enabled: true, approvalRequired: false });
}

function resetSkillForm() {
  Object.assign(skillForm, {
    id: "",
    agentIds: [],
    skillName: "",
    description: "",
    promptTemplate: "",
    configJson: "{}",
    triggerKeywords: "[]",
    enabled: true
  });
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

function createLlm() {
  resetLlmForm();
  addLlmModelRow();
  syncPrimaryModelFromRows();
  openCatalogModal("llms", "create");
}

function editLlm(item) {
  const modelMappingJson = normalizeModelMappingJsonInput(item.modelMappingJson, item.model, true);
  Object.assign(llmForm, item, { modelMappingJson });
  llmModelRows.value = jsonToModelRows(modelMappingJson);
  if (!llmModelRows.value.length) {
    addLlmModelRow();
  }
  syncPrimaryModelFromRows();
  openCatalogModal("llms", "edit");
}

async function submitLlm() {
  try {
    const normalizedMappingJson = modelRowsToJson(llmModelRows.value, llmForm.model);
    const primaryModel = extractPrimaryApiModel(normalizedMappingJson, llmForm.model);
    if (!primaryModel) {
      throw new Error("请至少填写一个模型名称。");
    }
    await saveAdminLlm({
      ...llmForm,
      model: primaryModel,
      modelMappingJson: normalizedMappingJson
    });
    resetLlmForm();
    closeCatalogModal();
    llms.value = await listLlms();
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

function normalizeModelMappingJsonInput(raw, fallbackModel = "", silent = false) {
  let parsed = null;
  try {
    parsed = raw ? JSON.parse(raw) : { models: [] };
  } catch {
    if (silent) {
      parsed = { models: [] };
    } else {
      throw new Error("模型配置解析失败，请检查模型列表。");
    }
  }
  const sourceModels = Array.isArray(parsed)
    ? parsed
    : Array.isArray(parsed?.models)
      ? parsed.models
      : [];
  const normalized = sourceModels
    .map((item) => ({
      displayName: String(item?.displayName || "").trim(),
      apiModel: String(item?.apiModel || "").trim()
    }))
    .map((item) => ({
      displayName: item.displayName || item.apiModel,
      apiModel: item.apiModel || item.displayName
    }))
    .filter((item) => item.apiModel);
  if (!normalized.length && fallbackModel) {
    normalized.push({
      displayName: fallbackModel,
      apiModel: fallbackModel
    });
  }
  return JSON.stringify({ models: normalized }, null, 2);
}

function jsonToModelRows(raw) {
  try {
    const parsed = JSON.parse(raw || "{\"models\":[]}");
    const sourceModels = Array.isArray(parsed)
      ? parsed
      : Array.isArray(parsed?.models)
        ? parsed.models
        : [];
    return sourceModels
      .map((item) => ({
        id: buildLlmModelRowId(),
        displayName: String(item?.displayName || "").trim(),
        apiModel: String(item?.apiModel || "").trim(),
        editing: false
      }))
      .map((item) => ({
        ...item,
        displayName: item.displayName || item.apiModel,
        apiModel: item.apiModel || item.displayName
      }))
      .filter((item) => item.apiModel);
  } catch {
    return [];
  }
}

function modelRowsToJson(rows, fallbackModel = "") {
  const normalized = (rows || [])
    .map((item) => ({
      displayName: String(item?.displayName || "").trim(),
      apiModel: String(item?.apiModel || "").trim()
    }))
    .map((item) => ({
      displayName: item.displayName || item.apiModel,
      apiModel: item.apiModel || item.displayName
    }))
    .filter((item) => item.apiModel);
  if (!normalized.length && fallbackModel) {
    normalized.push({
      displayName: fallbackModel,
      apiModel: fallbackModel
    });
  }
  return JSON.stringify({ models: normalized }, null, 2);
}

function buildLlmModelRowId() {
  return `model-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function addLlmModelRow() {
  llmModelRows.value.push({
    id: buildLlmModelRowId(),
    displayName: "",
    apiModel: "",
    editing: true
  });
  syncPrimaryModelFromRows();
}

function removeLlmModelRow(rowId) {
  llmModelRows.value = llmModelRows.value.filter((item) => item.id !== rowId);
  if (!llmModelRows.value.length) {
    addLlmModelRow();
    return;
  }
  syncPrimaryModelFromRows();
}

function enableLlmModelRowEdit(rowId) {
  llmModelRows.value = llmModelRows.value.map((item) => ({
    ...item,
    editing: item.id === rowId
  }));
}

function finishLlmModelRowEdit(rowId) {
  llmModelRows.value = llmModelRows.value.map((item) => {
    if (item.id !== rowId) return item;
    const displayName = String(item.displayName || "").trim();
    const apiModel = String(item.apiModel || "").trim();
    return {
      ...item,
      displayName: displayName || apiModel,
      apiModel: apiModel || displayName,
      editing: false
    };
  });
  syncPrimaryModelFromRows();
}

function syncPrimaryModelFromRows() {
  const first = (llmModelRows.value || []).find((item) => String(item.apiModel || "").trim());
  llmForm.model = first ? String(first.apiModel).trim() : "";
}

function extractPrimaryApiModel(raw, fallbackModel = "") {
  try {
    const parsed = JSON.parse(raw);
    const sourceModels = Array.isArray(parsed)
      ? parsed
      : Array.isArray(parsed?.models)
        ? parsed.models
        : [];
    for (const item of sourceModels) {
      const model = String(item?.apiModel || "").trim();
      if (model) {
        return model;
      }
    }
  } catch {
    return fallbackModel || "";
  }
  return fallbackModel || "";
}

async function removeLlm(id) {
  try {
    await deleteAdminLlm(id);
    llms.value = await listLlms();
    if (form.llmConfigId === id) {
      form.llmConfigId = "";
      form.llmModel = "";
    }
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
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
  // 老响应里 agentIds 可能缺失(V19 之前的 client);退回到 legacy 单字段填充。
  const ids = Array.isArray(item.agentIds) && item.agentIds.length
    ? [...item.agentIds]
    : (item.agentId ? [item.agentId] : []);
  Object.assign(skillForm, {
    id: item.id || "",
    agentIds: ids,
    skillName: item.skillName || "",
    description: item.description || "",
    promptTemplate: item.promptTemplate || "",
    configJson: item.configJson || "{}",
    triggerKeywords: item.triggerKeywords || "[]",
    enabled: item.enabled !== false
  });
  openCatalogModal("skills", "edit");
}

function toggleSkillAgent(agentId) {
  const idx = skillForm.agentIds.indexOf(agentId);
  if (idx >= 0) {
    skillForm.agentIds.splice(idx, 1);
  } else {
    skillForm.agentIds.push(agentId);
  }
}

async function submitSkill() {
  try {
    // 如果没勾任何 agent,退回到 legacy 单字段;这保留旧行为,让管理员可以不关心 M:N。
    const agentIds = skillForm.agentIds.length
      ? [...skillForm.agentIds]
      : [state.resolvedAgentId || form.agentId].filter(Boolean);
    await saveSkillDefinition({
      ...skillForm,
      agentIds,
      agentId: agentIds[0] || null
    });
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

function resetDatasourceForm() {
  Object.assign(datasourceForm, {
    id: "",
    displayName: "",
    jdbcUrl: "",
    username: "",
    password: "",
    dialect: "postgresql",
    description: "",
    enabled: true
  });
}

function createDatasource() {
  resetDatasourceForm();
  openCatalogModal("datasources", "create");
}

function editDatasource(item) {
  Object.assign(datasourceForm, {
    id: item.id,
    displayName: item.displayName || "",
    jdbcUrl: item.jdbcUrl || "",
    username: item.username || "",
    password: "",
    dialect: item.dialect || "",
    description: item.description || "",
    enabled: item.enabled
  });
  openCatalogModal("datasources", "edit");
}

async function submitDatasource() {
  try {
    await saveDatasource({ ...datasourceForm });
    resetDatasourceForm();
    closeCatalogModal();
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

async function removeDatasource(id) {
  if (!confirm("确定删除这个数据源吗？依赖它的 skill 会立即失效。")) return;
  try {
    await deleteDatasource(id);
    await refreshCatalog();
  } catch (error) {
    state.error = error.message;
  }
}

function eventTitle(type) {
  switch (type) {
    case "RUN_STATUS":
      return "执行步骤";
    case "TOOL_REQUESTED":
      return "工具已选定";
    case "TOOL_STARTED":
      return "工具执行中";
    case "TOOL_COMPLETED":
      return "工具已完成";
    case "APPROVAL_REQUIRED":
      return "等待审批";
    case "RUN_COMPLETED":
      return "执行完成";
    case "RUN_FAILED":
      return "执行失败";
    default:
      return type;
  }
}

function eventTone(type) {
  if (type === "APPROVAL_REQUIRED" || type === "RUN_FAILED") return "warning";
  if (type === "TOOL_COMPLETED" || type === "RUN_COMPLETED") return "success";
  return "neutral";
}

function shouldShowTimelineEvent(eventOrType) {
  const type = typeof eventOrType === "string" ? eventOrType : eventOrType?.type;
  const content = typeof eventOrType === "string" ? "" : eventOrType?.content;
  if (type === "RUN_STATUS" && isModelOutputStatus(content)) {
    return false;
  }
  // 隐藏后端工具的 TOOL_REQUESTED / TOOL_STARTED / TOOL_COMPLETED 事件 ——
  // 聊天流式 timeline 不该展示 "tool=db.schema.inspect ..." 这类条目。
  if (
    (type === "TOOL_REQUESTED" || type === "TOOL_STARTED" || type === "TOOL_COMPLETED")
    && typeof content === "string"
    && /\btool=(\S+)/.test(content)
  ) {
    const match = content.match(/\btool=(\S+)/);
    if (match && isHiddenBackendTool(match[1])) {
      return false;
    }
  }
  return [
    "RUN_STATUS",
    "TOOL_REQUESTED",
    "TOOL_STARTED",
    "TOOL_COMPLETED",
    "APPROVAL_REQUIRED",
    "RUN_FAILED"
  ].includes(type);
}

function formatTimelineEventContent(event) {
  if (!event?.content) {
    return "";
  }
  if (event.type === "RUN_STATUS") {
    return String(event.content).replace(/^step=\d+\s*/i, "");
  }
  if (event.type === "TOOL_REQUESTED" || event.type === "TOOL_STARTED") {
    return String(event.content)
      .replace(/^step=\d+\s*/i, "")
      .replace(/^tool=/i, "tool: ")
      .replace(/\s+args=/i, "\nargs: ");
  }
  return event.content;
}
</script>

<template>
  <!-- 登录页:独立于主 shell 渲染,不显示侧栏 / 菜单,保持简洁 -->
  <div v-if="route.name === 'login'" class="login-shell">
    <div class="login-card">
      <h1>登录 Java Claw</h1>
      <p class="login-hint">使用用户名和密码登录。首次启动的默认管理员密码记录在服务端 <code>FIRST_ADMIN_PASSWORD.txt</code>。</p>
      <label>
        <span>用户名</span>
        <input v-model="loginForm.username" autocomplete="username" autofocus @keyup.enter="submitLogin" />
      </label>
      <label>
        <span>密码</span>
        <input v-model="loginForm.password" type="password" autocomplete="current-password" @keyup.enter="submitLogin" />
      </label>
      <button class="primary" :disabled="loginForm.submitting" @click="submitLogin">
        {{ loginForm.submitting ? "登录中..." : "登录" }}
      </button>
      <p v-if="loginForm.error" class="login-error">{{ loginForm.error }}</p>
    </div>
  </div>

  <!-- 首次登录强制改密弹窗。覆盖主 shell,完成前什么都不让点。 -->
  <div v-else-if="authUser.passwordMustChange" class="login-shell">
    <div class="login-card">
      <h1>请修改初始密码</h1>
      <p class="login-hint">首次登录必须修改初始密码后才能继续使用。新密码至少 8 位。</p>
      <label>
        <span>旧密码</span>
        <input v-model="passwordChangeForm.oldPassword" type="password" autocomplete="current-password" />
      </label>
      <label>
        <span>新密码</span>
        <input v-model="passwordChangeForm.newPassword" type="password" autocomplete="new-password" @keyup.enter="submitChangePassword" />
      </label>
      <button class="primary" :disabled="passwordChangeForm.submitting" @click="submitChangePassword">
        {{ passwordChangeForm.submitting ? "提交中..." : "修改密码" }}
      </button>
      <p v-if="passwordChangeForm.error" class="login-error">{{ passwordChangeForm.error }}</p>
    </div>
  </div>

  <div v-else class="workspace-shell">
    <aside class="left-rail">
      <div class="brand-block brand-block--compact">
        <h1 class="brand-title">JavaClaw</h1>
        <!-- Auth indicator / tenant switcher / logout —— 匿名状态显示"登录",登录后显示用户信息 -->
        <div class="auth-block">
          <template v-if="authUser.anonymous">
            <button class="auth-login-btn" @click="router.push({ name: 'login' })">登录</button>
          </template>
          <template v-else>
            <div class="auth-user">
              <strong>{{ authUser.displayName || authUser.username }}</strong>
              <span class="auth-tenant-label">租户:</span>
              <select
                v-if="authUser.tenants.length > 1"
                :value="authUser.activeTenantId"
                @change="(e) => doSwitchTenant(e.target.value)"
              >
                <option v-for="t in authUser.tenants" :key="t.id" :value="t.id">{{ t.name }}</option>
              </select>
              <span v-else class="auth-tenant-single">{{
                authUser.tenants.find((t) => t.id === authUser.activeTenantId)?.name || authUser.activeTenantId
              }}</span>
            </div>
            <button class="auth-logout-btn" @click="submitLogout">登出</button>
          </template>
        </div>
      </div>

      <nav class="menu-stack">
        <div v-for="group in groupedMenuItems" :key="group.title" class="menu-group">
          <div class="menu-group-title">{{ group.title }}</div>
          <button
            v-for="item in group.items"
            :key="item.id"
            class="menu-item"
            :class="{ active: activeMenu === item.id }"
            :title="item.hint"
            @click="navigateToMenu(item.id)"
          >
            {{ item.label }}
          </button>
        </div>
      </nav>

      <div v-if="activeRuns.length" class="rail-section active-runs-rail">
        <div class="rail-head">
          <h3>运行中 ({{ activeRuns.length }})</h3>
        </div>
        <ul class="active-runs-list">
          <li v-for="run in activeRuns" :key="run.runId" class="active-run-row">
            <div class="active-run-info">
              <strong :title="run.runId">{{ shortRunLabel(run) }}</strong>
              <span class="active-run-status">{{ run.status || "running" }}</span>
            </div>
            <button
              type="button"
              class="active-run-cancel"
              @click="terminateRun(run.runId)"
              title="终止这个 run"
            >
              终止
            </button>
          </li>
        </ul>
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
        <aside v-if="isPlanPanelVisible" class="run-plan-panel" :class="{ collapsed: state.runPlan.collapsed }">
          <header class="run-plan-header">
            <span class="run-plan-dot" aria-hidden="true"></span>
            <strong class="run-plan-title">{{ state.runPlan.title || "任务计划" }}</strong>
            <span class="run-plan-meta">{{ planProgressLabel || "准备中…" }}</span>
            <button type="button" class="run-plan-toggle" @click="state.runPlan.collapsed = !state.runPlan.collapsed">
              {{ state.runPlan.collapsed ? "展开" : "收起" }}
            </button>
          </header>
          <ol v-if="!state.runPlan.collapsed && state.runPlan.steps.length" class="run-plan-steps">
            <li v-for="step in state.runPlan.steps" :key="step.id" :class="['run-plan-step', planStepClass(step)]">
              <span class="run-plan-step-badge">{{ planStepBadge(step) }}</span>
              <div class="run-plan-step-body">
                <div class="run-plan-step-head">
                  <span class="run-plan-step-id">{{ step.id }}</span>
                  <span class="run-plan-step-title">{{ step.title }}</span>
                </div>
                <div v-if="step.toolHint" class="run-plan-step-tool">{{ step.toolHint }}</div>
                <div v-if="step.resultNote" class="run-plan-step-note">{{ step.resultNote }}</div>
                <div v-if="step.outputCount" class="run-plan-step-outputs">产出 {{ step.outputCount }} 次工具执行</div>
              </div>
            </li>
          </ol>
          <div v-else-if="!state.runPlan.collapsed" class="run-plan-placeholder">
            任务已派发，等待模型生成计划…
          </div>
        </aside>

        <div v-if="isLiveStatusVisible" class="live-status-banner" :class="`phase-${state.liveStatus.phase.toLowerCase()}`">
          <span class="live-status-dot"></span>
          <span class="live-status-phase">{{ livePhaseLabel }}</span>
          <span v-if="state.liveStatus.text" class="live-status-text">{{ state.liveStatus.text }}</span>
        </div>
        <div class="chat-session-tag">
          <strong class="chat-session-title">{{ currentSessionTitle }}</strong>
          <span class="chat-session-id">{{ form.sessionId || "auto" }}</span>
        </div>
        <div class="chat-control-deck">
          <div class="chat-topbar">
            <div class="chat-title-block">
              <strong class="chat-title">{{ currentSessionTitle }}</strong>
              <span class="chat-subtitle">围绕当前会话直接补充材料、框定区域并连续追问</span>
            </div>
            <div class="chat-config-bar floating">
              <label class="bar-field bar-field-plain">
                <select v-model="form.agentId" data-testid="agent-select">
                  <option value="">自动路由</option>
                  <option v-for="agent in agents" :key="agent.agentId" :value="agent.agentId">
                    {{ agent.displayName || agent.agentId }}
                  </option>
                </select>
              </label>
              <label class="bar-field bar-field-plain">
                <select v-model="form.llmConfigId" data-testid="llm-select">
                  <option value="">自动默认</option>
                  <option v-for="llm in llms" :key="llm.configId" :value="llm.configId">
                    {{ llm.displayName }}
                  </option>
                </select>
              </label>
              <label class="bar-field bar-field-plain">
                <select v-model="form.llmModel" data-testid="llm-model-select" :disabled="!selectedRuntimeLlmModels.length">
                  <option v-if="!selectedRuntimeLlmModels.length" value="">无可用模型</option>
                  <option v-for="item in selectedRuntimeLlmModels" :key="item.apiModel" :value="item.apiModel">
                    {{ item.displayName || item.apiModel }}
                  </option>
                </select>
              </label>
              <label class="bar-field bar-field-compact bar-field-plain">
                <select v-model="transport">
                  <option value="sse">SSE</option>
                  <option value="websocket">WebSocket</option>
                </select>
              </label>
              <button
                type="button"
                class="recent-drawer-trigger"
                @click="toggleRecentSessionsDrawer"
                :title="recentSessionsDrawer.open ? '关闭最近会话' : '查看最近会话'"
              >
                ☰ 最近会话
              </button>
            </div>
          </div>

        </div>

        <p v-if="llmWarning" class="error">{{ llmWarning }}</p>
        <p v-if="state.error" class="error">{{ state.error }}</p>

        <div class="conversation-panel chat-panel">
          <div v-if="transientStatusHint" class="status-hint">
            {{ transientStatusHint }}
          </div>
          <div ref="conversationStreamRef" class="conversation-stream" @scroll.passive="updateFollowFlagFromPosition">
            <section v-for="group in groupedRunTimeline" :key="group.runId" class="run-group">
              <div class="run-divider">
                <span>Run</span>
                <strong>{{ group.runId }}</strong>
              </div>
              <template v-for="block in group.blocks" :key="block.key">
                <article v-if="block.kind === 'message'" class="bubble" :class="[block.message.role, isFailedAssistantMessage(block.message) ? 'error' : '']">
                  <div class="bubble-meta">
                    <strong>{{ block.message.role }}</strong>
                    <span>{{ block.message.messageType }} · #{{ block.message.seqNo }}</span>
                  </div>
                  <p v-if="shouldShowMessageText(block.message, group.runId)">{{ displayMessageContent(block.message, group.runId) }}</p>
                  <div v-if="block.message.toolName || block.message.toolResultJson" class="inline-tool">
                    <span v-if="block.message.toolName">tool: {{ block.message.toolName }}</span>
                  </div>
                  <pre v-if="shouldShowToolArgs(block.message, group.runId) && block.message.toolArgsJson" class="tool-payload">{{ block.message.toolArgsJson }}</pre>
                  <template v-if="artifactPayload(block.message)">
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
                  <template v-else-if="markdownPayload(block.message)">
                    <!-- Markdown 不再嵌套显示文件名 / contentType 那一层,直接把 markdown
                         正文渲染出来,右上角放一个轻量链接,底部再放一个显式"下载报告"按钮
                         —— 用户读完长文后不用再滚回顶端找下载入口。 -->
                    <div class="render-block markdown-block">
                      <a
                        v-if="markdownPayload(block.message)?.artifactId"
                        class="markdown-download"
                        :href="artifactDownloadUrl(markdownPayload(block.message)?.artifactId)"
                        target="_blank"
                        rel="noopener"
                        title="下载 Markdown 文件"
                      >下载</a>
                      <MarkdownBlock
                        class="markdown-preview"
                        :markdown="markdownPayload(block.message)?.markdown || ''"
                        :to-html="markdownToHtml"
                      />
                      <div v-if="markdownPayload(block.message)?.artifactId" class="markdown-download-footer">
                        <a
                          class="markdown-download-btn"
                          :href="artifactDownloadUrl(markdownPayload(block.message)?.artifactId)"
                          target="_blank"
                          rel="noopener"
                        >下载</a>
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
                    <p>{{ parseToolEventContent(formatTimelineEventContent(block.event)).summary }}</p>
                    <template v-if="toolEventRenderMessageForView(block.event) && toolEventRenderPayloadRaw(block.event)?.displayType === 'artifact'">
                      <div class="render-block artifact-block">
                        <div class="render-block-head">
                          <div class="render-block-heading">
                            <strong>{{ artifactPayload(toolEventRenderMessageForView(block.event))?.name || renderBlockTitle(toolEventRenderMessageForView(block.event)) }}</strong>
                            <span>{{ artifactPayload(toolEventRenderMessageForView(block.event))?.contentType || toolEventRenderMessageForView(block.event).toolName }}</span>
                          </div>
                          <a class="download-link" :href="artifactDownloadUrl(artifactPayload(toolEventRenderMessageForView(block.event))?.artifactId)" target="_blank" rel="noopener">下载</a>
                        </div>
                      </div>
                    </template>
                    <template v-else-if="toolEventRenderMessageForView(block.event) && toolEventRenderPayloadRaw(block.event)?.displayType === 'markdown'">
                      <div class="render-block markdown-block">
                        <a
                          v-if="markdownPayload(toolEventRenderMessageForView(block.event))?.artifactId"
                          class="markdown-download"
                          :href="artifactDownloadUrl(markdownPayload(toolEventRenderMessageForView(block.event))?.artifactId)"
                          target="_blank"
                          rel="noopener"
                          title="下载 Markdown 文件"
                        >下载</a>
                        <MarkdownBlock
                          class="markdown-preview"
                          :markdown="markdownPayload(toolEventRenderMessageForView(block.event))?.markdown || ''"
                          :to-html="markdownToHtml"
                        />
                        <div v-if="markdownPayload(toolEventRenderMessageForView(block.event))?.artifactId" class="markdown-download-footer">
                          <a
                            class="markdown-download-btn"
                            :href="artifactDownloadUrl(markdownPayload(toolEventRenderMessageForView(block.event))?.artifactId)"
                            target="_blank"
                            rel="noopener"
                          >下载</a>
                        </div>
                      </div>
                    </template>
                    <pre v-else-if="parseToolEventContent(block.event.content).payload" class="tool-payload">{{ parseToolEventContent(block.event.content).payload }}</pre>
                  </template>
                  <template v-else-if="block.event.dynamic">
                    <p class="loading-line">
                      <span>任务执行中</span><span class="loading-dots"><i>.</i><i>.</i><i>.</i></span>
                    </p>
                  </template>
                  <p v-else>{{ formatTimelineEventContent(block.event) }}</p>
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
            <div v-if="!transcriptMessages.length && !state.events.length" class="empty-state chat-empty-state">
              <strong>开始一个新对话</strong>
              <p>先输入问题，或者在上方附加图片、文件、地图区域，再让 agent 结合上下文继续分析和查询。</p>
            </div>
          </div>
          <div v-if="resendSourceRequest" class="conversation-tail-actions">
            <button
              class="history-tool-button"
              :disabled="chatRunBusy || !hasAvailableLlms"
              @click="resendLastUserRequest"
            >
              {{ chatRunBusy ? "运行中..." : "重新发送上一条请求" }}
            </button>
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
              v-for="attachment in visibleComposerAttachments()"
              :key="attachment.name"
              class="context-chip attachment"
              @click="removeAttachment(attachment)"
            >
              {{ attachment.displayLabel || "附件" }} · {{ attachment.name }}
            </button>
            <div
              v-for="item in visibleMapRegionAttachments()"
              :key="item.attachment.name"
              class="map-attachment-card"
            >
              <div class="map-attachment-head">
                <strong>{{ item.attachment.displayLabel || "地图" }} · {{ item.payload.regions.length }} 个</strong>
                <button @click="removeAttachment(item.attachment)">移除</button>
              </div>
              <div class="map-attachment-list">
                <span
                  v-for="region in item.payload.regions"
                  :key="region.id"
                  class="map-attachment-item"
                >
                  {{ region.name }} · {{ region.geometry?.type || region.geometryType }} · 点数 {{ region.geometry?.coordinates?.[0]?.length || region.polygon?.length || 0 }}
                </span>
              </div>
            </div>
          </div>
          <div class="chat-toolbar composer-toolbar">
            <button class="icon-tool-button" @click="openImagePicker" title="添加图片" aria-label="添加图片">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 5.5A1.5 1.5 0 0 1 5.5 4h9A1.5 1.5 0 0 1 16 5.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 4 14.5z" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="7.2" cy="7.2" r="1.1" fill="currentColor"/><path d="M5.7 13.4 8.7 10.4l2.1 2.1 1.8-1.8 1.7 2.7" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button class="icon-tool-button" @click="openFilePicker" title="添加文件" aria-label="添加文件">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M7 4.5h4.6L15 7.9v7.6A1.5 1.5 0 0 1 13.5 17h-6A1.5 1.5 0 0 1 6 15.5v-9A2 2 0 0 1 8 4.5z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M11.5 4.7v3h3" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>
            </button>
            <button class="icon-tool-button" @click="openMapModal" title="添加地图区域" aria-label="添加地图区域">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m3.5 5.3 4-1.3 5 1.7 4-1.4v10.4l-4 1.4-5-1.7-4 1.3z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M7.5 4v10.7M12.5 5.7v10.7" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="12.6" cy="8.2" r="1.1" fill="currentColor"/></svg>
            </button>
          </div>
          <!--
            注意:这里不能用 <label>!composer-editor 是 contenteditable div,不是表单控件;
            而 label 的默认语义会把它内部"空白区域"的点击事件**转发给 label 内的第一个表单
            控件 —— 也就是下面的"发送"按钮**。结果就是用户点击输入框旁边的 padding / 空隙
            都会误触发送,这是一个实打实的误发消息 bug。换成普通 <div> 就没有这个行为。
          -->
          <div class="dock-field composer-main-field">
            <div
              ref="composerEditor"
              class="composer-editor"
              contenteditable="true"
              data-placeholder="在这里描述任务、追问结果，回车发送，Shift+回车换行"
              data-testid="message-input"
              @input="updateReferencePicker"
              @keyup="handleComposerKeyup"
              @keydown="handleComposerKeydown"
            ></div>
            <div class="dock-actions composer-inline-actions">
              <!--
                运行中 → 把"发送"换成"终止",点击调 terminateRun(currentRunId),跟 Agents
                菜单那边的终止按钮走同一条后端 cancel 路径。用 v-if 切两个按钮而不是复用
                一个,是因为语义(primary vs danger)、onClick、disabled、dataTestId 全都不同,
                混在一起更难读。
              -->
              <button
                v-if="chatRunBusy && state.currentRunId"
                class="composer-stop-action"
                @click="terminateRun(state.currentRunId)"
                data-testid="stop-button"
                title="终止当前运行"
              >
                <span class="composer-stop-dot" aria-hidden="true"></span>
                终止
              </button>
              <button
                v-else
                class="primary"
                :disabled="chatRunBusy || !form.message || !hasAvailableLlms"
                @click="runChat"
                data-testid="send-button"
              >发送</button>
            </div>
          </div>
          <input ref="imageInput" type="file" accept="image/*" multiple hidden @change="handleImageChange" />
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
          <button class="primary" @click="openMemoryEditor(null)">新增</button>
        </div>
        <p class="empty-state" style="margin:0 0 10px; padding:8px 12px;">
          <strong>scope=agent</strong> 的备注跨所有会话可见（类似"长期记忆"）；<strong>scope=session</strong> 仅本会话可见（避免跨会话数据泄露）。
          一键点击"pin/unpin"可在两者间切换。
        </p>
        <div class="memory-grid">
          <article v-for="note in state.memories" :key="note.id" class="memory-note">
            <div class="bubble-meta">
              <strong>
                <span :class="['event-chip', note.scope === 'agent' ? 'scope-agent' : note.scope === 'global' ? 'scope-global' : 'scope-session']">
                  {{ note.scope || 'session' }}
                </span>
                {{ note.source }}
              </strong>
              <span>{{ note.updatedAt || note.createdAt }}</span>
            </div>
            <p>{{ note.content }}</p>
            <div class="catalog-row-actions">
              <button @click="toggleMemoryScope(note)">
                {{ note.scope === 'agent' ? 'unpin（降回 session）' : 'pin（升到 agent）' }}
              </button>
              <button @click="openMemoryEditor(note)">编辑</button>
              <button @click="removeMemoryNote(note)">删除</button>
            </div>
          </article>
          <div v-if="!state.memories.length" class="empty-state">当前 agent 还没有持久化 memory note。</div>
        </div>
      </section>

      <div v-if="memoryEditor.visible" class="modal-backdrop" @click.self="closeMemoryEditor">
        <div class="modal-card catalog-modal">
          <div class="panel-title">
            <h3>{{ memoryEditor.noteId ? '编辑 memory note' : '新增 memory note' }}</h3>
            <button @click="closeMemoryEditor">关闭</button>
          </div>
          <label>
            <span>Agent ID</span>
            <input v-model="memoryEditor.agentId" placeholder="dev-agent" />
          </label>
          <label>
            <span>Session ID（可留空 —— agent scope 跨 session 共享）</span>
            <input v-model="memoryEditor.sessionId" placeholder="可选" />
          </label>
          <label>
            <span>作用域 scope</span>
            <select v-model="memoryEditor.scope">
              <option value="session">session（仅本会话可见）</option>
              <option value="agent">agent（本 agent 所有会话可见）</option>
              <option value="global">global（所有 agent 共享，慎用）</option>
            </select>
          </label>
          <label>
            <span>来源 source</span>
            <input v-model="memoryEditor.source" placeholder="manual / pinned / run_summary" />
          </label>
          <label>
            <span>内容</span>
            <textarea v-model="memoryEditor.content" rows="8" placeholder="输入 memory 正文" />
          </label>
          <div class="dock-actions">
            <button class="primary" @click="submitMemoryEditor">保存</button>
            <button @click="closeMemoryEditor">取消</button>
          </div>
        </div>
      </div>

      <section v-if="activeMenu === 'agents'" class="workspace-panel catalog-page">
        <div class="panel-title" style="margin-bottom: 12px;">
          <h3>Agents（共 {{ state.catalog.agents.length }}）</h3>
          <button class="primary" @click="openAgentEditor(null)">新增 Agent</button>
        </div>
        <p class="empty-state" style="margin:0 0 12px; padding:8px 12px;">
          Agent 定义是所有 skill / 知识 / 记忆的主键维度。<strong>systemPrompt</strong> 是喂给 LLM 的 role=system 文本；
          <strong>agentMarkdown</strong> 写 agent 职责说明（会注入 prompt 的 AGENT.md 段）；
          <strong>memoryMarkdown</strong> 是补充型永久注释（MEMORY.md 段），跨 session 共享。
          这三个字段原来在 <code>workspaces/&lt;agent&gt;/</code> 目录文件里，现在从数据库读，前端直接编辑。
        </p>
        <div class="catalog-list">
          <article v-for="item in state.catalog.agents" :key="item.agentId" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.displayName }}</strong>
                <span>{{ item.agentId }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="openAgentEditor(item)">编辑</button>
                <button @click="removeAgentDefinition(item)">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">{{ item.description || "(未填写描述)" }}</p>
            <div class="chart-meta">
              <span>{{ item.enabled ? "enabled" : "disabled" }}</span>
              <span>systemPrompt {{ (item.systemPrompt || "").length }} 字符</span>
              <span>AGENT.md {{ (item.agentMarkdown || "").length }} 字符</span>
              <span>MEMORY.md {{ (item.memoryMarkdown || "").length }} 字符</span>
            </div>
          </article>
          <div v-if="!state.catalog.agents.length" class="empty-state">还没有 agent 定义。</div>
        </div>
      </section>

      <div v-if="agentEditor.visible" class="modal-backdrop" @click.self="closeAgentEditor">
        <div class="modal-card catalog-modal">
          <div class="panel-title">
            <h3>{{ agentEditor.isNew ? "新建 Agent" : "编辑 Agent" }}</h3>
            <button @click="closeAgentEditor">关闭</button>
          </div>
          <label>
            <span>Agent ID（主键，不可变，只能小写英文/数字/-）</span>
            <input v-model="agentEditor.agentId" :disabled="!agentEditor.isNew" placeholder="dev-agent" />
          </label>
          <label>
            <span>显示名</span>
            <input v-model="agentEditor.displayName" placeholder="Dev Agent" />
          </label>
          <label>
            <span>描述</span>
            <input v-model="agentEditor.description" placeholder="一句话描述 agent 职责" />
          </label>
          <label>
            <span>System Prompt（role=system，直接喂给 LLM）</span>
            <textarea v-model="agentEditor.systemPrompt" rows="6" placeholder="You are..." />
          </label>
          <label>
            <span>AGENT.md 内容（agent 职责说明，注入 prompt 的 AGENT.md: 段）</span>
            <textarea v-model="agentEditor.agentMarkdown" rows="8" placeholder="# My Agent&#10;&#10;This agent helps with..." />
          </label>
          <label>
            <span>MEMORY.md 内容（跨 session 永久注释，注入 prompt 的 MEMORY.md: 段）</span>
            <textarea v-model="agentEditor.memoryMarkdown" rows="6" placeholder="Stable facts, constants, reminders..." />
          </label>
          <label style="flex-direction: row; gap: 8px; align-items: center;">
            <input type="checkbox" v-model="agentEditor.enabled" />
            <span>启用（disabled 时不会出现在 agent 下拉选择里）</span>
          </label>
          <div class="dock-actions">
            <button class="primary" @click="submitAgentEditor">保存</button>
            <button @click="closeAgentEditor">取消</button>
          </div>
        </div>
      </div>

      <section v-if="activeMenu === 'llms'" class="workspace-panel catalog-page">
        <div class="catalog-list">
          <article v-for="item in state.catalog.llms" :key="item.id" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.displayName }} · {{ item.model }}</strong>
                <span>{{ item.updatedAt }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="editLlm(item)">编辑</button>
                <button @click="removeLlm(item.id)">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">{{ item.provider }} · {{ item.baseUrl }}</p>
            <div class="chart-meta">
              <span>{{ item.id }}</span>
              <span>{{ item.stream ? "stream" : "non-stream" }}</span>
              <span>{{ item.enabled ? "enabled" : "disabled" }}</span>
              <span>{{ item.defaultConfig ? "default" : "optional" }}</span>
            </div>
          </article>
          <div v-if="!state.catalog.llms.length" class="empty-state">当前没有 LLM 配置。</div>
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
            <div class="catalog-card-meta">
              <span class="catalog-card-tag" v-for="agentId in (item.agentIds && item.agentIds.length ? item.agentIds : [item.agentId]).filter(Boolean)" :key="agentId">
                {{ agentId }}
              </span>
              <span v-if="!item.agentIds?.length && !item.agentId" class="muted">未绑定</span>
              <span v-if="item.triggerKeywords && item.triggerKeywords !== '[]'" class="catalog-card-trigger">
                triggers: {{ item.triggerKeywords }}
              </span>
              <span v-if="!item.enabled" class="catalog-card-disabled">disabled</span>
            </div>
          </article>
          <div v-if="!state.catalog.skills.length" class="empty-state">当前没有 Skill 定义。</div>
        </div>
      </section>

      <section v-if="activeMenu === 'datasources'" class="workspace-panel catalog-page">
        <div class="catalog-list">
          <article v-for="item in state.catalog.datasources" :key="item.id" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.displayName || item.id }}</strong>
                <span>{{ item.updatedAt }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="editDatasource(item)">编辑</button>
                <button @click="removeDatasource(item.id)">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">
              <code>{{ item.jdbcUrl }}</code>
              · user: {{ item.username }}
              · password: {{ item.passwordSet ? '已设置' : '未设置' }}
              · {{ item.dialect || '—' }}
              · {{ item.enabled ? '启用' : '停用' }}
            </p>
            <p v-if="item.description" class="catalog-card-content">{{ item.description }}</p>
          </article>
          <div v-if="!state.catalog.datasources.length" class="empty-state">
            当前没有数据源。skill 用到的 JDBC 连接都应在此注册，LLM 只看 jdbcUrl，后端自动注入凭证。
          </div>
        </div>
      </section>

      <!-- ========== P4-B 用户管理 ========== -->
      <section v-if="activeMenu === 'users'" class="workspace-panel catalog-page">
        <div class="admin-toolbar">
          <button class="primary" @click="openCreateUserModal">+ 新建用户</button>
          <button @click="openImportUsersModal">批量导入</button>
        </div>
        <div class="catalog-list">
          <article v-for="item in state.catalog.users" :key="item.id" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.username }} · {{ item.displayName }}</strong>
                <span>{{ item.updatedAt }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="assignUserRolesHandler(item)">分配角色</button>
                <button @click="resetUserPasswordHandler(item.id)">重置密码</button>
                <button @click="deleteUserHandler(item.id)" :disabled="item.id === 'admin'">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">
              id: <code>{{ item.id }}</code>
              · status: {{ item.status }}
              · tenant: {{ item.preferredTenantId || '—' }}
              <span v-if="item.passwordMustChange"> · ⚠ 待改密</span>
              · last_login: {{ item.lastLoginAt || '—' }}
            </p>
            <p v-if="item.email" class="catalog-card-content">📧 {{ item.email }}</p>
          </article>
          <div v-if="!state.catalog.users.length" class="empty-state">还没有用户。</div>
        </div>
      </section>

      <!-- ========== P4-B 租户管理 ========== -->
      <section v-if="activeMenu === 'tenants'" class="workspace-panel catalog-page">
        <div class="admin-toolbar">
          <button class="primary" @click="openCreateTenantModal">+ 新建租户</button>
        </div>
        <div class="catalog-list">
          <article v-for="item in state.catalog.tenants" :key="item.id" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.name }} · {{ item.code }}</strong>
                <span>{{ item.updatedAt }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="editTenantHandler(item)">编辑</button>
                <button @click="deleteTenantHandler(item.id)" :disabled="item.id === 'system'">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">
              id: <code>{{ item.id }}</code>
              · kind: {{ item.kind }} · status: {{ item.status }}
            </p>
            <p v-if="item.description" class="catalog-card-content">{{ item.description }}</p>
          </article>
          <div v-if="!state.catalog.tenants.length" class="empty-state">还没有租户。</div>
        </div>
      </section>

      <!-- ========== P4-B 角色管理 ========== -->
      <section v-if="activeMenu === 'roles'" class="workspace-panel catalog-page">
        <div class="admin-toolbar">
          <button class="primary" @click="openCreateRoleModal">+ 新建角色</button>
        </div>
        <div class="catalog-list">
          <article v-for="item in state.catalog.roles" :key="item.id" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.name }} · {{ item.code }}</strong>
                <span>tenant: {{ item.tenantId }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="editRolePermissionsHandler(item)">编辑权限</button>
                <button @click="editRoleHandler(item)">编辑</button>
                <button @click="deleteRoleHandler(item.id)"
                        :disabled="item.id === 'system-super-admin' || item.id === 'system-user'">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">id: <code>{{ item.id }}</code></p>
            <p v-if="item.description" class="catalog-card-content">{{ item.description }}</p>
          </article>
          <div v-if="!state.catalog.roles.length" class="empty-state">还没有角色。</div>
          <article class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title"><strong>权限目录（只读）</strong></div>
            </div>
            <p class="catalog-card-content" style="font-family:ui-monospace,monospace;font-size:12px;line-height:1.6;">
              <span v-for="p in state.catalog.permissions" :key="p.code">
                <code>{{ p.code }}</code> [{{ p.category }}] —— {{ p.description }}<br />
              </span>
            </p>
          </article>
        </div>
      </section>

      <!-- ========== P4-B OAuth 客户端管理 ========== -->
      <section v-if="activeMenu === 'oauth-clients'" class="workspace-panel catalog-page">
        <div class="admin-toolbar">
          <button class="primary" @click="openCreateOauthClientModal">+ 新建外部应用</button>
        </div>
        <div class="catalog-list">
          <article v-for="item in state.catalog.oauthClients" :key="item.clientId" class="catalog-card">
            <div class="catalog-card-head">
              <div class="catalog-card-title">
                <strong>{{ item.displayName }}</strong>
                <span>{{ item.updatedAt }}</span>
              </div>
              <div class="catalog-row-actions">
                <button @click="editOauthClientHandler(item)">编辑</button>
                <button @click="rotateOauthSecretHandler(item.clientId)">重新生成 secret</button>
                <button @click="deleteOauthClientHandler(item.clientId)">删除</button>
              </div>
            </div>
            <p class="catalog-card-content">
              client_id: <code>{{ item.clientId }}</code>
              · status: {{ item.status }}
              · owner: {{ item.ownerUserId || '—' }}
            </p>
            <p class="catalog-card-content"><strong>redirect_uris:</strong> <code>{{ item.redirectUris }}</code></p>
            <p class="catalog-card-content"><strong>scopes:</strong> <code>{{ item.scopes }}</code></p>
            <p v-if="item.description" class="catalog-card-content">{{ item.description }}</p>
          </article>
          <div v-if="!state.catalog.oauthClients.length" class="empty-state">还没有外部应用。</div>
        </div>
      </section>

    </section>

    <!-- 分配角色对话框 -->
    <div v-if="assignRolesModal.open" class="modal-backdrop" @click.self="closeAssignRolesModal">
      <div class="modal-card catalog-modal">
        <div class="modal-head">
          <h3>为 {{ assignRolesModal.user?.username }} 分配角色</h3>
          <button @click="closeAssignRolesModal">关闭</button>
        </div>
        <label>
          <span>租户</span>
          <select v-model="assignRolesModal.tenantId" @change="loadAssignRolesData">
            <option v-for="t in state.catalog.tenants" :key="t.id" :value="t.id">{{ t.name }} ({{ t.id }})</option>
            <option v-if="!state.catalog.tenants.length" :value="assignRolesModal.tenantId">{{ assignRolesModal.tenantId }}</option>
          </select>
        </label>
        <p class="modal-copy">勾选下面的角色将<strong>覆盖</strong>该用户在此租户内的整组角色。不勾任何即清空。</p>
        <div v-if="assignRolesModal.loading" class="empty-state">加载中…</div>
        <div v-else-if="!assignRolesModal.availableRoles.length" class="empty-state">该租户暂无角色,先去「角色」页新建。</div>
        <div v-else class="multi-select-list">
          <label v-for="r in assignRolesModal.availableRoles" :key="r.id">
            <input type="checkbox"
                   :checked="assignRolesModal.selectedRoleIds.includes(r.id)"
                   @change="toggleAssignRole(r.id)" />
            <div class="ms-row-main">
              <div class="ms-row-title">
                <strong>{{ r.name }}</strong>
                <code>{{ r.code }}</code>
              </div>
              <div class="ms-row-meta">
                id: {{ r.id }}<span v-if="r.description"> · {{ r.description }}</span>
              </div>
            </div>
          </label>
        </div>
        <div class="dock-actions">
          <button class="primary" @click="submitAssignRoles">保存</button>
          <button @click="closeAssignRolesModal">取消</button>
        </div>
      </div>
    </div>

    <!-- 编辑角色权限对话框 -->
    <div v-if="editRolePermsModal.open" class="modal-backdrop" @click.self="closeEditRolePermsModal">
      <div class="modal-card catalog-modal">
        <div class="modal-head">
          <h3>编辑 {{ editRolePermsModal.role?.name }} 的权限</h3>
          <button @click="closeEditRolePermsModal">关闭</button>
        </div>
        <p class="modal-copy">勾选下面的权限码将<strong>覆盖</strong>该角色的整组权限。注意:租户管理员不应拥有 <code>session.read.all</code> / <code>tenant.manage</code> / <code>permission.manage</code> 等跨租户码。</p>
        <div class="multi-select-toolbar">
          <input type="search" v-model="editRolePermsModal.filter" placeholder="按 code 或描述过滤..." />
          <button @click="selectVisiblePermissions">勾选当前过滤</button>
          <button @click="selectAllPermissions">全选</button>
          <button @click="clearAllPermissions">清空</button>
          <span class="ms-count">已选 {{ editRolePermsModal.selectedCodes.length }} / {{ state.catalog.permissions.length }}</span>
        </div>
        <div v-if="editRolePermsModal.loading" class="empty-state">加载中…</div>
        <div v-else class="multi-select-list">
          <template v-for="g in groupedPermissions" :key="g.title">
            <div class="ms-section-title">{{ g.title }}（{{ g.items.length }}）</div>
            <label v-for="p in g.items" :key="p.code">
              <input type="checkbox"
                     :checked="editRolePermsModal.selectedCodes.includes(p.code)"
                     @change="togglePermissionCode(p.code)" />
              <div class="ms-row-main">
                <div class="ms-row-title">
                  <code>{{ p.code }}</code>
                  <span class="ms-category" :data-cat="p.category">{{ p.category }}</span>
                </div>
                <div v-if="p.description" class="ms-row-meta">{{ p.description }}</div>
              </div>
            </label>
          </template>
          <div v-if="!groupedPermissions.length" class="empty-state">没有匹配的权限码。</div>
        </div>
        <div class="dock-actions">
          <button class="primary" @click="submitEditRolePerms">保存（共 {{ editRolePermsModal.selectedCodes.length }} 项）</button>
          <button @click="closeEditRolePermsModal">取消</button>
        </div>
      </div>
    </div>

    <!-- 一次性敏感值显示对话框（临时密码 / OAuth secret） -->
    <div v-if="secretModal.open" class="modal-backdrop" @click.self="closeSecretModal">
      <div class="modal-card">
        <div class="modal-head">
          <h3>{{ secretModal.title }}</h3>
          <button @click="closeSecretModal">关闭</button>
        </div>
        <p v-if="secretModal.message" class="modal-copy" style="white-space:pre-line;">{{ secretModal.message }}</p>
        <div class="secret-box">
          <input ref="secretInput" :value="secretModal.secret" readonly class="secret-value" @focus="$event.target.select()" />
          <button class="primary" @click="copySecret">{{ secretModal.copied ? '已复制' : '复制' }}</button>
        </div>
        <p class="modal-copy" style="color:#a8370e;">⚠ 关闭后无法再看到。请立即妥善保存。</p>
        <div class="dock-actions">
          <button @click="closeSecretModal">我已保存,关闭</button>
        </div>
      </div>
    </div>

    <!-- ========== P4-B 管理后台对话框（统一渲染处） ========== -->
    <div v-if="adminModal.type" class="modal-backdrop" @click.self="closeAdminModal">
      <div class="modal-card catalog-modal">
        <div class="modal-head">
          <h3>
            {{
              adminModal.type === 'user' ? '新建用户'
              : adminModal.type === 'import' ? '批量导入用户'
              : adminModal.type === 'tenant' ? (tenantForm.id ? '编辑租户' : '新建租户')
              : adminModal.type === 'role' ? (roleForm.id ? '编辑角色' : '新建角色')
              : adminModal.type === 'oauth-client' ? (oauthClientForm.clientId && state.catalog.oauthClients.some(c => c.clientId === oauthClientForm.clientId) ? '编辑外部应用' : '新建外部应用')
              : ''
            }}
          </h3>
          <button @click="closeAdminModal">关闭</button>
        </div>

        <!-- 用户：新建 -->
        <template v-if="adminModal.type === 'user'">
          <label>
            <span>username（必填）</span>
            <input v-model="userForm.username" autofocus placeholder="字母/数字/下划线" />
          </label>
          <label>
            <span>显示名</span>
            <input v-model="userForm.displayName" placeholder="留空时用 username" />
          </label>
          <label>
            <span>email</span>
            <input v-model="userForm.email" placeholder="可选" />
          </label>
          <label>
            <span>默认租户 id</span>
            <input v-model="userForm.preferredTenantId" placeholder="如 system / xmap" />
          </label>
          <p class="modal-copy">创建后会生成一次性临时密码。首次登录后被强制改密。</p>
          <div class="dock-actions">
            <button class="primary" @click="submitUserCreate">创建</button>
            <button @click="closeAdminModal">取消</button>
          </div>
        </template>

        <!-- 用户：批量导入 -->
        <template v-if="adminModal.type === 'import'">
          <p class="modal-copy">
            支持两种格式 ——<br>
            JSON 数组：<code>[{"username":"alice","email":"a@x"}]</code><br>
            CSV（逗号分隔）：<code>username,email,displayName,preferredTenantId</code>
          </p>
          <textarea v-model="importForm.raw" rows="8"
                    placeholder='[{"username":"alice","email":"a@x.com"}]' />
          <div v-if="importForm.results.length" style="font-family:ui-monospace,monospace;font-size:12px;max-height:200px;overflow-y:auto;border:1px solid var(--line);padding:8px;border-radius:6px;">
            <p v-for="(r, idx) in importForm.results" :key="idx">
              <span v-if="r.ok">✅ {{ r.username }} → {{ r.userId }} 临时密码 <code>{{ r.temporaryPassword }}</code></span>
              <span v-else>❌ {{ r.username }} → {{ r.error }}</span>
            </p>
            <p style="color:#999;">⚠ 临时密码仅显示一次，关闭对话框前请保存。</p>
          </div>
          <div class="dock-actions">
            <button class="primary" :disabled="importForm.busy" @click="submitBatchImport">
              {{ importForm.busy ? '导入中...' : '提交' }}
            </button>
            <button @click="closeAdminModal">关闭</button>
          </div>
        </template>

        <!-- 租户：创建/编辑 -->
        <template v-if="adminModal.type === 'tenant'">
          <label>
            <span>id</span>
            <input v-model="tenantForm.id" placeholder="留空自动生成" :disabled="!!tenantForm.id" />
          </label>
          <label>
            <span>code（唯一）</span>
            <input v-model="tenantForm.code" autofocus placeholder="例 'xmap'" />
          </label>
          <label>
            <span>名称</span>
            <input v-model="tenantForm.name" placeholder="显示名" />
          </label>
          <label>
            <span>类型</span>
            <select v-model="tenantForm.kind">
              <option value="TENANT">TENANT</option>
              <option value="SYSTEM">SYSTEM</option>
            </select>
          </label>
          <label>
            <span>状态</span>
            <select v-model="tenantForm.status">
              <option value="ACTIVE">ACTIVE</option>
              <option value="DISABLED">DISABLED</option>
            </select>
          </label>
          <label>
            <span>描述</span>
            <textarea v-model="tenantForm.description" rows="2" />
          </label>
          <div class="dock-actions">
            <button class="primary" @click="submitTenantSave">保存</button>
            <button @click="closeAdminModal">取消</button>
          </div>
        </template>

        <!-- 角色：创建/编辑 -->
        <template v-if="adminModal.type === 'role'">
          <label>
            <span>id</span>
            <input v-model="roleForm.id" placeholder="留空自动生成" :disabled="!!roleForm.id" />
          </label>
          <label>
            <span>tenant id（必填）</span>
            <input v-model="roleForm.tenantId" placeholder="例 'system' / 'xmap'" />
          </label>
          <label>
            <span>code</span>
            <input v-model="roleForm.code" autofocus placeholder="例 'TENANT_ADMIN'" />
          </label>
          <label>
            <span>名称</span>
            <input v-model="roleForm.name" placeholder="显示名" />
          </label>
          <label>
            <span>描述</span>
            <textarea v-model="roleForm.description" rows="2" />
          </label>
          <p class="modal-copy">保存后请使用列表里的"编辑权限"再赋具体权限码。</p>
          <div class="dock-actions">
            <button class="primary" @click="submitRoleSave">保存</button>
            <button @click="closeAdminModal">取消</button>
          </div>
        </template>

        <!-- OAuth 客户端：创建/编辑 -->
        <template v-if="adminModal.type === 'oauth-client'">
          <label>
            <span>client_id</span>
            <input v-model="oauthClientForm.clientId" placeholder="留空自动生成" :disabled="!!oauthClientForm.clientId && state.catalog.oauthClients.some(c => c.clientId === oauthClientForm.clientId)" />
          </label>
          <label>
            <span>应用名</span>
            <input v-model="oauthClientForm.displayName" autofocus />
          </label>
          <label>
            <span>redirect_uris（JSON 数组）</span>
            <input v-model="oauthClientForm.redirectUris" placeholder='["https://app.example.com/cb"]' />
          </label>
          <label>
            <span>scopes（JSON 数组）</span>
            <input v-model="oauthClientForm.scopes" placeholder='["openid","profile"]' />
          </label>
          <label>
            <span>状态</span>
            <select v-model="oauthClientForm.status">
              <option value="ACTIVE">ACTIVE</option>
              <option value="DISABLED">DISABLED</option>
            </select>
          </label>
          <label>
            <span>owner user_id</span>
            <input v-model="oauthClientForm.ownerUserId" placeholder="可选" />
          </label>
          <label>
            <span>描述</span>
            <textarea v-model="oauthClientForm.description" rows="2" />
          </label>
          <p class="modal-copy">新建时会生成一次性 client_secret 弹出显示；编辑表单不会改 secret，要更换请用列表里的「重新生成 secret」按钮。</p>
          <div class="dock-actions">
            <button class="primary" @click="submitOauthClientSave">保存</button>
            <button @click="closeAdminModal">取消</button>
          </div>
        </template>
      </div>
    </div>

    <!-- 最近会话改成右侧滑出抽屉,会话窗口右上角的按钮控制开关 -->
    <transition name="recent-drawer">
      <aside v-if="recentSessionsDrawer.open" class="recent-drawer">
        <div class="recent-drawer-head">
          <h3>最近会话</h3>
          <div class="recent-drawer-actions">
            <button class="rail-action" @click="createNewSessionFromDrawer">新建会话</button>
            <button class="recent-drawer-close" @click="closeRecentSessionsDrawer">关闭</button>
          </div>
        </div>
        <div class="session-list">
          <button
            v-for="session in recentSessions"
            :key="session.sessionId"
            class="session-link"
            @click="openSessionFromDrawer(session)"
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
      </aside>
    </transition>
    <transition name="recent-drawer-bg">
      <div v-if="recentSessionsDrawer.open" class="recent-drawer-backdrop" @click="closeRecentSessionsDrawer"></div>
    </transition>

    <div
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
    >
      <button @click="renameSessionTitle(contextMenu.session)">编辑标题</button>
      <button class="danger" @click="removeSession(contextMenu.session)">删除</button>
    </div>

    <div v-if="titleModal.visible" class="modal-backdrop">
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

    <div
      v-if="catalogModal.visible"
      class="modal-backdrop"
    >
      <div class="modal-card catalog-modal">
        <div class="modal-head">
          <h3>
            {{
              catalogModal.type === "llms"
                ? (catalogModal.mode === "create" ? "添加 LLM 配置" : "编辑 LLM 配置")
                : catalogModal.type === "knowledge"
                ? (catalogModal.mode === "create" ? "添加知识" : "编辑知识")
                : catalogModal.type === "tools"
                  ? (catalogModal.mode === "create" ? "添加工具" : "编辑工具")
                  : (catalogModal.mode === "create" ? "添加 Skill" : "编辑 Skill")
            }}
          </h3>
          <button @click="closeCatalogModal">关闭</button>
        </div>

        <template v-if="catalogModal.type === 'llms'">
          <div class="compose-grid search-grid">
            <label><span>Config ID</span><input v-model="llmForm.id" placeholder="留空自动生成" /></label>
            <label>
              <span>Provider</span>
              <select v-model="llmForm.provider">
                <option v-for="provider in llmProviderOptions" :key="provider" :value="provider">{{ provider }}</option>
              </select>
            </label>
            <label><span>Display Name</span><input v-model="llmForm.displayName" /></label>
          </div>
          <div class="compose-grid search-grid">
            <label><span>统一 URL（全模型共用）</span><input v-model="llmForm.baseUrl" /></label>
            <label><span>Stream</span><select v-model="llmForm.stream"><option :value="true">true</option><option :value="false">false</option></select></label>
            <label><span>Enabled</span><select v-model="llmForm.enabled"><option :value="true">true</option><option :value="false">false</option></select></label>
          </div>
          <div class="compose-grid search-grid">
            <label><span>Default</span><select v-model="llmForm.defaultConfig"><option :value="false">false</option><option :value="true">true</option></select></label>
          </div>
          <div class="llm-model-editor">
            <div class="llm-model-editor-head">
              <span>模型列表（双击行编辑）</span>
              <button type="button" @click="addLlmModelRow">添加模型</button>
            </div>
            <div class="llm-model-table">
              <div class="llm-model-row llm-model-header">
                <span>显示名称</span>
                <span>接口模型名</span>
                <span>操作</span>
              </div>
              <div
                v-for="row in llmModelRows"
                :key="row.id"
                class="llm-model-row"
                @dblclick="enableLlmModelRowEdit(row.id)"
              >
                <template v-if="row.editing">
                  <input v-model="row.displayName" placeholder="如：GLM-4.7" @keyup.enter="finishLlmModelRowEdit(row.id)" @blur="finishLlmModelRowEdit(row.id)" />
                  <input v-model="row.apiModel" placeholder="如：Pro/zai-org/GLM-4.7" @keyup.enter="finishLlmModelRowEdit(row.id)" @blur="finishLlmModelRowEdit(row.id)" />
                  <button type="button" @click="finishLlmModelRowEdit(row.id)">完成</button>
                </template>
                <template v-else>
                  <span>{{ row.displayName || "-" }}</span>
                  <span>{{ row.apiModel || "-" }}</span>
                  <button type="button" @click="removeLlmModelRow(row.id)">删除</button>
                </template>
              </div>
            </div>
          </div>
          <label><span>API Key</span><textarea v-model="llmForm.apiKey" rows="4"></textarea></label>
          <div class="dock-actions">
            <button class="primary" @click="submitLlm">保存 LLM</button>
            <button @click="closeCatalogModal">取消</button>
          </div>
        </template>

        <template v-else-if="catalogModal.type === 'knowledge'">
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
          <label class="skill-agents-label">
            <span>绑定的 Agents（M:N，可多选）</span>
            <div class="skill-agents-chips">
              <button
                v-for="agent in agents"
                :key="agent.agentId"
                type="button"
                class="skill-agent-chip"
                :class="{ active: skillForm.agentIds.includes(agent.agentId) }"
                @click="toggleSkillAgent(agent.agentId)"
              >
                {{ agent.displayName || agent.agentId }}
              </button>
              <span v-if="!agents.length" class="muted">还没有 agent。先到 Agents 菜单建一个。</span>
            </div>
          </label>
          <label><span>Description</span><textarea v-model="skillForm.description" rows="3"></textarea></label>
          <label><span>Prompt Template</span><textarea v-model="skillForm.promptTemplate" rows="8"></textarea></label>
          <label>
            <span>Trigger Keywords（JSON 数组，命中才激活 skill；空=永远激活）</span>
            <textarea
              v-model="skillForm.triggerKeywords"
              rows="2"
              placeholder='例: ["覆盖分析","弱覆盖","扇区统计"]'
            ></textarea>
          </label>
          <label><span>Config JSON（whitelistTables / planStepIds / planStepRules / strict）</span>
            <textarea v-model="skillForm.configJson" rows="6"></textarea>
          </label>
          <div class="dock-actions">
            <button class="primary" @click="submitSkill">保存 Skill</button>
            <button @click="closeCatalogModal">取消</button>
          </div>
        </template>

        <template v-else-if="catalogModal.type === 'datasources'">
          <div class="compose-grid search-grid">
            <label><span>ID</span><input v-model="datasourceForm.id" :disabled="catalogModal.mode === 'edit'" placeholder="留空自动生成" /></label>
            <label><span>显示名称</span><input v-model="datasourceForm.displayName" /></label>
            <label><span>Dialect</span>
              <select v-model="datasourceForm.dialect">
                <option value="postgresql">postgresql</option>
                <option value="mysql">mysql</option>
                <option value="sqlserver">sqlserver</option>
                <option value="oracle">oracle</option>
                <option value="">（不限）</option>
              </select>
            </label>
            <label><span>启用</span>
              <select v-model="datasourceForm.enabled">
                <option :value="true">true</option>
                <option :value="false">false</option>
              </select>
            </label>
          </div>
          <label><span>JDBC URL</span><input v-model="datasourceForm.jdbcUrl" placeholder="jdbc:postgresql://host:port/db" /></label>
          <div class="compose-grid search-grid">
            <label><span>用户名</span><input v-model="datasourceForm.username" /></label>
            <label>
              <span>密码</span>
              <input type="password" v-model="datasourceForm.password" :placeholder="catalogModal.mode === 'edit' ? '留空则保留原密码' : '新建必填'" />
            </label>
          </div>
          <label><span>说明</span><textarea v-model="datasourceForm.description" rows="3" placeholder="这个数据源做什么用，skill 如何引用"></textarea></label>
          <div class="dock-actions">
            <button class="primary" @click="submitDatasource">保存数据源</button>
            <button @click="closeCatalogModal">取消</button>
          </div>
        </template>
      </div>
    </div>

    <div v-if="mapModal.visible" class="modal-backdrop" @click.self="closeMapModal">
      <div class="modal-card map-modal" style="width:min(1280px, calc(100vw - 16px));">
        <div class="modal-head">
          <h3>绘制地图区域</h3>
          <button @click="closeMapModal">关闭</button>
        </div>
        <div
          ref="mapCanvas"
          class="map-canvas"
          @pointerdown="onMapPointerDown"
          @pointermove="onMapPointerMove"
          @pointerup="finishMapInteraction"
          @pointerleave="finishMapInteraction"
          @pointercancel="finishMapInteraction"
          @click="onMapCanvasClick"
          @dblclick.prevent="finishPolygonDrawing"
          @wheel.prevent="onMapWheel"
        >
          <div class="map-floating-controls" @pointerdown.stop @click.stop @dblclick.stop>
            <div class="map-mode-switch" @pointerdown.stop @click.stop @dblclick.stop>
              <button :class="{ active: mapModal.mode === 'pan' }" @pointerdown.stop @click.stop="setMapMode('pan')">拖动</button>
              <button :class="{ active: mapModal.mode === 'rectangle' }" @pointerdown.stop @click.stop="setMapMode('rectangle')">矩形</button>
              <button :class="{ active: mapModal.mode === 'circle' }" @pointerdown.stop @click.stop="setMapMode('circle')">圆形</button>
              <button :class="{ active: mapModal.mode === 'polygon' }" @pointerdown.stop @click.stop="setMapMode('polygon')">多边形</button>
            </div>
            <button
              v-if="mapModal.mode === 'polygon' && mapModal.polygonPoints.length >= 3"
              class="primary map-finish-button"
              @pointerdown.stop
              @click.stop="finishPolygonDrawing"
            >
              完成多边形
            </button>
          </div>
          <img
            v-for="tile in mapTiles"
            :key="tile.key"
            class="map-tile"
            :src="tile.url"
            alt=""
            draggable="false"
            :style="{ left: `${tile.left}px`, top: `${tile.top}px` }"
          />
          <svg class="map-overlay" :viewBox="`0 0 ${mapViewport.width} ${mapViewport.height}`" preserveAspectRatio="none">
            <template v-for="region in mapRegionOverlays" :key="region.id">
              <rect
                v-if="region.overlayType === 'rect'"
                class="map-region-rect"
                :class="{ draft: region.id.startsWith('draft-') }"
                :x="region.x"
                :y="region.y"
                :width="region.width"
                :height="region.height"
                rx="8"
              />
              <circle
                v-else-if="region.overlayType === 'circle'"
                class="map-region-circle"
                :class="{ draft: region.id.startsWith('draft-') }"
                :cx="region.cx"
                :cy="region.cy"
                :r="region.radius"
              />
              <polygon
                v-else
                class="map-region-polygon"
                :class="{ draft: region.id.startsWith('draft-') }"
                :points="region.pointsAttr"
              />
            </template>
          </svg>
          <div class="map-center-pin"></div>
        </div>
        <div class="map-region-list" :class="{ empty: !mapModal.regions.length }">
          <div v-for="(region, index) in mapModal.regions" :key="region.id" class="map-region-chip">
            <span>
              区域{{ index + 1 }} · {{ region.geometryType }}
              · {{ region.bounds.minLat.toFixed(4) }},{{ region.bounds.minLng.toFixed(4) }}
              ~ {{ region.bounds.maxLat.toFixed(4) }},{{ region.bounds.maxLng.toFixed(4) }}
            </span>
            <button @click="removeMapRegion(region.id)">移除</button>
          </div>
          <div v-if="!mapModal.regions.length" class="empty-state compact">尚未添加区域。</div>
        </div>
        <div class="dock-actions">
          <button class="primary" @click="confirmMapRegions">确认并回填</button>
          <button @click="closeMapModal">取消</button>
        </div>
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
