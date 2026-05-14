<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import MarkdownBlock from "./components/MarkdownBlock.vue";
import ScopeEditor from "./components/ScopeEditor.vue";
import EchartsBlock from "./components/EchartsBlock.vue";
import {
  approve,
  cancelRun,
  openGlobalEventSocket,
  artifactDownloadUrl,
  deleteAdminLlm,
  getActiveRun,
  getRunEventSnapshot,
  listActiveRuns,
  getApproval,
  getMemories,
  getRun,
  getSession,
  listAdminLlms,
  listApprovals,
  listKnowledgeEntries,
  listAgents,
  uploadChatAttachment,
  listAgentDefinitionsAdmin,
  listLlms,
  listMemoryNotesAdmin,
  listRunsBySession,
  listSessions,
  listSessionsPaged,
  listFilterableTenants,
  listFilterableApps,
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
  setEmbedAccessToken,
  getEmbedAccessToken,
  isEmbedMode,
  exchangeOauthToSession,
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
  adminDeleteOauthClient,
  fetchUsageSummary,
  fetchUsageTimeseries,
  fetchUsageRecent,
  labCreateTask,
  labListTasks,
  labTaskDetail,
  labCancelTask,
  labRestartTask,
  labRefineConstraints,
  labRefineGoal,
  labListAllSkills,
  adminListSessions,
  adminGetRunReplay,
  adminGetRunSteps,
  adminListSessionRuns
} from "./api";

const agents = ref([]);
const llms = ref([]);
const approvals = ref([]);

// Token 用量页 state。range 选项映射到 from/to 时间;groupBy 切换聚合维度。
// summary / timeseries / recent 三组数据各自独立加载,刷新按钮一次拉齐。
const usageState = reactive({
  range: "7d",
  groupBy: "tenant",
  loading: false,
  error: "",
  summary: [],
  timeseries: [],
  recent: []
});

// Agent 实验室 state。tasks 列表;currentDetail 是详情页(打开 taskId 时拉);
// polling 是详情页"还在跑"时的自动刷新标志。
const labState = reactive({
  loading: false,
  error: "",
  tasks: [],
  currentDetail: null,    // { task, iterations }
  pollingTimer: null,
  allSkills: []           // 拉一次缓存,Skill 选择下拉用
});
const labForm = reactive({
  title: "",
  goalDescription: "",
  // 用户不再手写测试用例,而是写若干约束规则 + 可选粘贴参考文档。
  // 后端 meta-LLM 据此自动派生测试场景并迭代调试。
  // 整个流程对用户:目标描述 → 约束规则(+参考文档) → 让 AI 跑。
  constraintRules: "",
  referenceDocuments: "",
  maxIterations: 5,
  // mode: 针对 Skill 的 NEW / EXISTING / CLONE_FROM
  mode: "NEW",
  // host agent: hostAgentMode 三选一(跟 Skill 模式同思路)
  hostAgentMode: "EXISTING",            // EXISTING / NEW / CLONE_FROM
  hostAgentId: "",                      // EXISTING 时填(从下拉选)
  newHostAgentId: "",                   // NEW / CLONE_FROM 时填
  newHostAgentDisplayName: "",          // NEW / CLONE_FROM 时可选
  newHostAgentSeedPrompt: "",           // NEW 时可选 — 一句话描述,meta-LLM 据此扩写
  newHostAgentScopeType: "SYSTEM",
  newHostAgentScopeTenantId: "",
  cloneFromHostAgentId: "",             // CLONE_FROM 时填(选已有源)
  // 是否允许迭代过程中也改 Agent(默认关 — 只改 Skill)
  allowAgentEvolution: false,
  // target Skill
  targetSkillName: "",         // EXISTING 用(从下拉选)
  newSkillName: "",            // NEW / CLONE_FROM 用(填新 skill 名)
  cloneFromSkillName: "",      // CLONE_FROM 用(从下拉选)
  // 新 Skill 的 scope
  targetScopeType: "SYSTEM",
  targetScopeTenantId: "",
  // LLM 双栏 —— 跟会话面板一致,(configId, model) 一对一选择,
  // 同一个 LLM 配置下挂多个 model 时也能区分。空 = 走 default。
  metaLlmConfigId: "",         // 设计师 LLM(留空 = default)
  metaLlmModel: "",            // 设计师 LLM 的 apiModel
  testLlmConfigId: "",         // 测试 LLM(留空 = Agent 默认)
  testLlmModel: ""             // 测试 LLM 的 apiModel
});
const labShowCreate = ref(false);

// 详情页内嵌编辑器:允许"换 LLM / 改约束规则 / 改参考文档"后一键 保存+重启迭代。
// 跟创建 modal 共用同一套字段语义,只是这里源是 currentDetail.task。
const labDetailEditor = reactive({
  taskId: "",
  title: "",
  goalDescription: "",
  maxIterations: 5,
  constraintRules: "",
  referenceDocuments: "",
  metaLlmKey: "|",        // "configId|apiModel"
  testLlmKey: "|",
  saving: false,
  expanded: false
});

function labLoadDetailEditor(task) {
  if (!task) return;
  labDetailEditor.taskId = task.id;
  labDetailEditor.title = task.title || "";
  labDetailEditor.goalDescription = task.goalDescription || "";
  labDetailEditor.maxIterations = task.maxIterations || 5;
  labDetailEditor.constraintRules = task.constraintRules || "";
  labDetailEditor.referenceDocuments = task.referenceDocuments || "";
  labDetailEditor.metaLlmKey = `${task.metaLlmConfigId || task.llmConfigId || ""}|${task.metaLlmModel || ""}`;
  labDetailEditor.testLlmKey = `${task.testLlmConfigId || ""}|${task.testLlmModel || ""}`;
  // 失败 / 取消 态自动展开 —— 用户进来八成就是要改 LLM 或规则再跑,
  // 折叠等于让他们多点一下;成功 / 跑中态保持折叠,免打扰看历史。
  const status = (task.status || "").toUpperCase();
  labDetailEditor.expanded = ["FAILED", "FAILED_META_LLM", "CANCELLED"].includes(status);
}

async function labDetailEditorSaveAndRestart() {
  if (!labDetailEditor.taskId) return;
  if (!labDetailEditor.constraintRules.trim()) {
    labState.error = "约束规则不能为空"; return;
  }
  if (!confirm("用编辑器里的约束规则 / LLM / 参考文档覆盖原任务,AI 重新派生测试场景并从第 1 轮调试。已有迭代记录保留。")) return;
  try {
    labDetailEditor.saving = true;
    const [metaCfg, metaModel] = String(labDetailEditor.metaLlmKey || "|").split("|");
    const [testCfg, testModel] = String(labDetailEditor.testLlmKey || "|").split("|");
    await labRefineConstraints(labDetailEditor.taskId, {
      title: labDetailEditor.title.trim() || null,
      goalDescription: labDetailEditor.goalDescription.trim() || null,
      maxIterations: Number(labDetailEditor.maxIterations) || null,
      constraintRules: labDetailEditor.constraintRules.trim(),
      referenceDocuments: labDetailEditor.referenceDocuments.trim() || null,
      metaLlmConfigId: metaCfg || null,
      metaLlmModel: metaModel || null,
      testLlmConfigId: testCfg || null,
      testLlmModel: testModel || null
    });
    await labReloadDetail(labDetailEditor.taskId);
    labStartPolling(labDetailEditor.taskId);
  } catch (e) { labState.error = e.message; }
  finally { labDetailEditor.saving = false; }
}

// 创建 modal 嵌入式"运行状态"面板:用户提交后 modal 不关,这里保留 activeTaskId
// 并轮询 detail,显示当前迭代/状态/最近一轮总结,允许停 / 重启 / 改写约束规则后再调试。
const labCreateModal = reactive({
  activeTaskId: "",
  detail: null,            // 完整 detail(task + iterations)
  pollTimer: null,
  pollError: "",
  submitting: false,
  refining: false          // 改写约束规则提交中
});

async function labStartCreateModalPolling(taskId) {
  labCreateModal.activeTaskId = taskId;
  labCreateModal.pollError = "";
  labStopCreateModalPolling();
  // 立刻拉一次,然后每 3s 轮询直到 task 不在运行态(或用户关掉 modal)
  await labPollCreateModalOnce();
  labCreateModal.pollTimer = setInterval(async () => {
    try { await labPollCreateModalOnce(); }
    catch (e) { labCreateModal.pollError = e.message || "poll failed"; }
  }, 3000);
}
async function labPollCreateModalOnce() {
  if (!labCreateModal.activeTaskId) return;
  const detail = await labTaskDetail(labCreateModal.activeTaskId);
  labCreateModal.detail = detail;
  // 终态自动停轮询
  const status = detail?.task?.status;
  if (status && !["PENDING", "RUNNING"].includes(status)) {
    labStopCreateModalPolling();
  }
}
function labStopCreateModalPolling() {
  if (labCreateModal.pollTimer) {
    clearInterval(labCreateModal.pollTimer);
    labCreateModal.pollTimer = null;
  }
}
function labCloseCreateModal() {
  labStopCreateModalPolling();
  labCreateModal.activeTaskId = "";
  labCreateModal.detail = null;
  labCreateModal.pollError = "";
  labShowCreate.value = false;
}
async function labCreateModalCancel() {
  if (!labCreateModal.activeTaskId) return;
  if (!confirm("取消当前迭代?当前轮会被中断,已有的轮次记录保留。")) return;
  try {
    await labCancelTask(labCreateModal.activeTaskId);
    await labPollCreateModalOnce();
  } catch (e) { labState.error = e.message; }
}
async function labCreateModalRestart() {
  if (!labCreateModal.activeTaskId) return;
  if (!confirm("从第 1 轮重新设计 + 测试?约束规则不变,只是重跑。已有迭代记录保留。")) return;
  try {
    await labRestartTask(labCreateModal.activeTaskId);
    labStartCreateModalPolling(labCreateModal.activeTaskId);
  } catch (e) { labState.error = e.message; }
}
async function labCreateModalRefineRules() {
  if (!labCreateModal.activeTaskId) return;
  if (!labForm.constraintRules.trim()) { labState.error = "约束规则不能为空"; return; }
  if (!confirm("用当前表单里的约束规则覆盖原任务,AI 重新派生测试场景并从第 1 轮调试。已有迭代记录保留。")) return;
  try {
    labCreateModal.refining = true;
    await labRefineConstraints(labCreateModal.activeTaskId, {
      constraintRules: labForm.constraintRules.trim(),
      referenceDocuments: labForm.referenceDocuments.trim() || null
    });
    labStartCreateModalPolling(labCreateModal.activeTaskId);
  } catch (e) { labState.error = e.message; }
  finally { labCreateModal.refining = false; }
}

// Lab modal 的 LLM 下拉源:展平成 (LLM × model) 对,跟会话面板的 llmModelOptions 同思路。
// 一个 LLM 配置可能挂多个 model(modelMappingJson.models),用户实际选的就是
// "用哪个 LLM 服务的哪个 model 跑"。option.value 用 "configId|apiModel" 拼接。
// 数据源优先 admin catalog(含 disabled、跨租户;sysadmin 看全),回退到 /api/llms。
const labLlmOptions = computed(() => {
  const source = (state.catalog.llms && state.catalog.llms.length > 0)
    ? state.catalog.llms
    : (llms.value || []);
  const out = [];
  for (const llm of source) {
    const configId = llm.id || llm.configId;
    if (!configId) continue;
    const enabled = llm.enabled !== false;
    const models = parseLlmModelMappings(llm.modelMappingJson, llm.model);
    if (!models.length) {
      out.push({
        key: `${configId}|`,
        configId,
        apiModel: "",
        label: `${llm.displayName || configId}${enabled ? "" : " [disabled]"}`,
        enabled
      });
      continue;
    }
    for (const m of models) {
      out.push({
        key: `${configId}|${m.apiModel}`,
        configId,
        apiModel: m.apiModel,
        label: `${llm.displayName || configId} - ${m.displayName || m.apiModel}${enabled ? "" : " [disabled]"}`,
        enabled
      });
    }
  }
  return out;
});

// 写入器:metaLlmKey 跟 form 的 metaLlmConfigId+metaLlmModel 双向绑定
const labMetaLlmKey = computed({
  get() { return `${labForm.metaLlmConfigId || ""}|${labForm.metaLlmModel || ""}`; },
  set(value) {
    const [configId, apiModel] = String(value || "").split("|");
    labForm.metaLlmConfigId = configId || "";
    labForm.metaLlmModel = apiModel || "";
  }
});
const labTestLlmKey = computed({
  get() { return `${labForm.testLlmConfigId || ""}|${labForm.testLlmModel || ""}`; },
  set(value) {
    const [configId, apiModel] = String(value || "").split("|");
    labForm.testLlmConfigId = configId || "";
    labForm.testLlmModel = apiModel || "";
  }
});

// 管理员会话审计页 state。
// fromDays: 0 = 不限,7 = 最近 7 天,等。
const adminSessionsState = reactive({
  loading: false,
  error: "",
  rows: [],
  totalSessions: 0,
  totalRuns: 0,
  totalMessages: 0,
  totalTokens: 0,
  page: 0,
  size: 20,
  // 联动下拉的数据源(进入页面时一次性拉,缓存到内存)
  catalogLoaded: false,
  tenants: [],          // [{id, name}, ...]
  oauthClients: [],     // [{clientId, tenantId, displayName}, ...]
  agents: []            // [{agentId, displayName, scopeType, scopeTenantId, appId}, ...]
});
// 会话审计批量选择 state(仅缓存当前页可管理行的 sessionId)
const adminSessionsSelection = reactive(new Set());

// 权限判定:跟后端 SessionVisibility 三档同步。
// 匿名兜底放行(application-prod.yml anonymous-enabled=false 时实际不会落到匿名);
// 否则按当前 principal 的 permissions 决定。
const sessionVisibilityForUi = computed(() => {
  if (!authUser || authUser.anonymous) {
    return { readAll: true, readTenant: false, tenantId: "", userId: "" };
  }
  const perms = new Set(authUser.permissions || []);
  return {
    readAll: perms.has("session.read.all"),
    readTenant: perms.has("session.read.tenant") || perms.has("session.read.all"),
    tenantId: authUser.activeTenantId || "",
    userId: authUser.userId || ""
  };
});
function canManageSession(row) {
  const v = sessionVisibilityForUi.value;
  if (v.readAll) return true;
  if (v.readTenant) return row.tenantId === v.tenantId;
  return row.userId === v.userId;
}
const manageableAdminSessions = computed(() =>
  adminSessionsState.rows.filter(canManageSession)
);
const adminSessionsAllSelected = computed(() => {
  const items = manageableAdminSessions.value;
  if (items.length === 0) return false;
  return items.every(r => adminSessionsSelection.has(r.sessionId));
});
const adminSessionsFilter = reactive({
  userId: "",
  tenantId: "",
  appId: "",
  agentId: "",
  fromDays: 7
});
const adminReplayState = reactive({
  open: false,
  loading: false,
  runId: "",
  data: null,
  // V42:行级事件由后端按 category 落表后返回。空数组表示尚未加载;
  // 没有 steps 时 fallback 走 data.eventLogJson(老 run 兼容路径,服务端会代为解析)。
  steps: [],
  error: "",
  // 复盘分类过滤:用户可以只看 AI 调用 / 数据库 / 文件 / 产物 等
  activeCategories: new Set(["ai", "db", "file", "artifact", "plan", "tool-other", "lifecycle"])
});
// 选 run 复盘的中间 modal:某 session 下所有 run 列表,管理员选一个再展开 trace。
const adminRunsModal = reactive({
  open: false,
  sessionId: "",
  sessionTitle: "",
  loading: false,
  runs: [],
  error: ""
});

// embed 模式标志:URL 带 ?embed=1 立即生效,渲染前就决定了 → 不会闪左栏
const embedMode = computed(() => route.query.embed === "1");
const embedReady = ref(false);

// embed 模式:宿主侧(xmap-ol-front)经 SDK postMessage 注册过来的"可调方法名"列表。
// 每次发送 chat 都会把这个列表当作 host_methods.json 附件塞进去,LLM 看了知道能调啥。
const embedHostMethods = ref([]);
// /ws/bridge 单例 —— 首条消息发出后(session 创建好)开一次,用于把 LLM 的 host.invoke 转发给宿主。
let embedBridgeSocket = null;

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
// 全局 WebSocket 单例。登录后建一条到 /ws/events,后端按权限把所有可见 session 的事件流推过来,
// 整个会话生命周期常驻,不每发一条消息 / 切一个 session 就开一条新 WS。openGlobalEventSocket
// 内部已经做断线指数退避重连,这里只需要拿到句柄等登出时主动 close。
const globalSocket = ref(null);
// 受全局 socket 推送过来的事件,默认全部走 pushEvent(state.events 的 dedup + render)。
// 但当事件 sessionId 不是用户当前正在看的 form.sessionId 时,我们改成更新 sidebar 的"活动时间"
// + 终态时标红点,不刷主对话流。这是"多管理员共视一个会话"的实现支撑面。
const sessionActivity = reactive({});  // sessionId → { lastEventAt, terminalType, runId }
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

// 重连状态以前是 per-run 的:每发一条消息开一条 WS,WS 断了走 reconnectCtl 指数退避。
// 新通信模型下 globalSocket 是登录态长连接,openGlobalEventSocket 内部自己管断线 + 指数退避;
// 这里不再需要业务层维护一份重连 state。

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
    // thinking:推理类模型(o1/R1/qwen-thinking)在正式回答前 / 边想边产时吐出的
    // reasoning_content 累积文本。跟 content 平行展示,前端单独区块(可折叠)。
    thinking: "",
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
  // 历史/僵尸 run 在前端没有 WebSocket 流，要靠这份 DB 状态兜底显示完成/失败状态。
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
  { id: "usage",       group: "工作区", label: "Token 用量",  hint: "按租户/应用/用户/模型统计 token 消耗" },

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
  { id: "oauth-clients", group: "系统管理", label: "OAuth 应用", hint: "外部应用 client_id/secret", permission: "oauth.client.manage" },
  { id: "lab",           group: "系统管理", label: "Agent 实验室", hint: "AI 自动迭代设计 Agent / Skill", permission: "menu.lab" },
  { id: "admin-sessions",group: "系统管理", label: "会话审计",     hint: "跨租户/用户/应用的会话列表 + run 复盘", permission: "session.read.tenant" }
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
// 通用 scope 默认值:新建资源时如果 admin 不显式选,后端 service 会按 principal 推断;
// 编辑时 editXxx() 会用资源现有 scope 覆盖。
const DEFAULT_SCOPE = Object.freeze({ scopeType: "PUBLIC", scopeTenantId: "", appId: "", scopeUserId: "" });

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
  defaultConfig: false,
  scope: { ...DEFAULT_SCOPE }
});
const llmModelRows = ref([]);
const lastSentRequest = ref(null);
const knowledgeForm = reactive({ id: "", title: "", content: "", contentType: "markdown", source: "database", tagsJson: "[]", enabled: true, scope: { ...DEFAULT_SCOPE } });
const toolForm = reactive({ id: "", toolName: "", displayName: "", description: "", schemaJson: "{}", toolType: "builtin", configJson: "{}", enabled: true, approvalRequired: false, scope: { ...DEFAULT_SCOPE } });
const skillForm = reactive({
  id: "",
  agentIds: [],
  skillName: "",
  description: "",
  promptTemplate: "",
  configJson: "{}",
  triggerKeywords: "[]",
  enabled: true,
  scope: { ...DEFAULT_SCOPE }
});
const datasourceForm = reactive({ id: "", displayName: "", jdbcUrl: "", username: "", password: "", dialect: "postgresql", description: "", enabled: true, scope: { ...DEFAULT_SCOPE } });

// 把后端 view/response 里的 scope 4 字段提到 form.scope 上;反过来 submit 时拍平回 4 字段。
function readScopeFromView(item) {
  return {
    scopeType: item?.scopeType || "PUBLIC",
    scopeTenantId: item?.scopeTenantId || "",
    appId: item?.appId || "",
    scopeUserId: item?.scopeUserId || ""
  };
}
function flattenScopeForRequest(scope) {
  return {
    scopeType: scope?.scopeType || null,
    scopeTenantId: scope?.scopeTenantId || null,
    appId: scope?.appId || null,
    scopeUserId: scope?.scopeUserId || null
  };
}

// 聊天里只该出现:user / assistant / 最终交付物的 tool message(artifact / markdown)。
// db.query / plan.* / db.schema.inspect 这些中间步骤的 tool message 全部踢出 ——
// 这种 SQL/rows JSON dump 是给 Run 复盘 / admin 查的内部数据,糊在用户气泡里就是噪音。
// 数据库 session_message 表还是照常存,只是这里渲染前过滤。
function isUserFacingTranscriptMessage(message) {
  if (!message) return false;
  const role = message.role;
  if (role === "user" || role === "assistant") return true;
  if (role === "tool") {
    const payload = parseJson(message.toolResultJson);
    const dt = payload?.displayType;
    return dt === "markdown" || dt === "artifact";
  }
  return false;
}

const transcriptMessages = computed(() => {
  const persisted = (state.session?.messages || []).filter(isUserFacingTranscriptMessage);
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
// 是否有 run 正在跑:只看后端权威信号(state.run.status),不再看是否持有 WebSocket 句柄 ——
// globalSocket 是登录态长连接,跟 run 状态无关。
const chatRunBusy = computed(() => loading.sending || isActiveRunStatus(state.run?.status));

// 把 llms × 每个 llm.modelMappingJson 里的 models 数组展平成"LLM-模型"扁平列表,
// 给单一下拉用。option.value 用 "configId|apiModel" 拼接,display 用 "LLM 名 - 模型名"。
// 没配置 modelMappingJson 时退回到 llm.model 单条。
const llmModelOptions = computed(() => {
  const out = [];
  for (const llm of llms.value || []) {
    const models = parseLlmModelMappings(llm.modelMappingJson, llm.model);
    if (!models.length) {
      out.push({
        key: `${llm.configId}|`,
        configId: llm.configId,
        apiModel: "",
        label: llm.displayName || llm.configId,
        defaultConfig: !!llm.defaultConfig
      });
      continue;
    }
    for (const m of models) {
      out.push({
        key: `${llm.configId}|${m.apiModel}`,
        configId: llm.configId,
        apiModel: m.apiModel,
        label: `${llm.displayName || llm.configId} - ${m.displayName || m.apiModel}`,
        defaultConfig: !!llm.defaultConfig
      });
    }
  }
  return out;
});
const selectedLlmModelKey = computed({
  get() {
    return `${form.llmConfigId || ""}|${form.llmModel || ""}`;
  },
  set(value) {
    const [configId, apiModel] = String(value || "").split("|");
    form.llmConfigId = configId || "";
    form.llmModel = apiModel || "";
  }
});
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
  if (state.events.some((event) => event.runId === runId && (event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED"))) {
    return true;
  }
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
    // TOOL_COMPLETED 带渲染负载时,如果 transcript 里还没刷出对应的 tool message
    // (session refresh 有 250ms 延迟),立即把 event 转成临时 render message 显示出来,
    // 避免渲染结果"晚一拍才出现"。这条路径独立于 shouldShowTimelineEvent,
    // 不受时间线事件过滤影响。
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
    if (shouldShowTimelineEvent(event)) {
      runMap.get(runId).push({
        kind: "event",
        key: event.id,
        event
      });
    }
  }

  // 即使 content 为空,只要有 thinking,也要展示 live 气泡(推理模型在 reasoning 阶段
  // content 为空、reasoning_content 有内容,这种情况要让用户看到思考)
  if (state.liveAssistant.runId && (state.liveAssistant.content || state.liveAssistant.thinking)) {
    if (!runMap.has(state.liveAssistant.runId)) {
      runMap.set(state.liveAssistant.runId, []);
      orderedRuns.push(state.liveAssistant.runId);
    }
    runMap.get(state.liveAssistant.runId).push({
      kind: "live",
      key: `live-${state.liveAssistant.runId}`,
      content: state.liveAssistant.content,
      thinking: state.liveAssistant.thinking
    });
  }

  return orderedRuns.map((runId) => {
    const blocks = runMap.get(runId) || [];
    const userBlocks = blocks.filter((block) => isUserBlock(block));
    const runTerminal = isRunTerminalByEvents(runId);

    // run 已结束 → 坍缩为 [user, finalResult] 干净视图(渲染块 / 有内容的 assistant 气泡)。
    // run 进行中 → 展开完整时间线,让 MODEL_OUTPUT / MODEL_THINKING / 后续工具调用都能流式可见,
    //              避免一旦先生成 artifact.markdown 后续 AI 修正/继续输出被吞掉看起来"冻结"。
    if (runTerminal) {
      const finalResultBlock = pickSingleFinalResultBlock(blocks);
      if (finalResultBlock) {
        return {
          runId,
          blocks: [...userBlocks, finalResultBlock]
        };
      }
    } else if (blocks.some((block) => !isUserBlock(block))) {
      return { runId, blocks };
    }

    // run 进行中但还没任何中间内容(用户刚发完消息),fallback 链给个"在动"的提示:
    //   1) 最近一条带渲染负载的 TOOL_COMPLETED 事件
    //   2) liveAssistant 当前内容/思考
    //   3) 最近一条值得展示的进度事件
    //   4) 啥都没有 → 只显示用户气泡
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

    if (state.liveAssistant.runId === runId && (state.liveAssistant.content || state.liveAssistant.thinking)) {
      return {
        runId,
        blocks: [
          ...userBlocks,
          {
            kind: "live",
            key: `latest-live-${runId}`,
            content: state.liveAssistant.content,
            thinking: state.liveAssistant.thinking
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

// 附件双路径:
//   - 小文件(< INLINE_THRESHOLD,默认 512KB)且是文本/图片 → base64 内联进 chat 请求,简单快
//   - 大文件(>= 阈值,或者非文本/图片)→ multipart 流式上传 /api/chat/attachments,落工作区,
//     chat 请求里只带 {name, path}。doc.normalize / file.read 直接用 path 找文件。
// 上限 1GB(后端 ChatAttachmentUploadController.MAX_FILE_BYTES + nginx client_max_body_size 1g)。
const INLINE_THRESHOLD = 512 * 1024;             // 512KB 以下走老 base64 内联
const MAX_FILE_BYTES = 1024 * 1024 * 1024;       // 1GB 总上限

async function addAttachmentFromFile(file, mode) {
  const imageMode = mode === "image";
  if (file.size > MAX_FILE_BYTES) {
    state.error = `${file.name} 超过上限 ${Math.floor(MAX_FILE_BYTES / 1024 / 1024)}MB`;
    return;
  }
  const isText = !imageMode && isTextLikeFile(file);

  // 大文件 / 二进制非图片 → 走 multipart 上传
  if (!imageMode && (file.size >= INLINE_THRESHOLD || !isText)) {
    try {
      const result = await uploadChatAttachment(file, { sessionId: form.sessionId, agentId: form.agentId });
      const attachment = {
        name: result.name,
        displayLabel: nextAttachmentDisplayLabel("file"),
        contentType: result.contentType || file.type || "application/octet-stream",
        path: result.path,             // 工作区相对路径,后端 doc.normalize/file.read 都用这个
        sizeBytes: result.size
      };
      form.attachments.push(attachment);
      appendComposerToken(attachment.displayLabel);
      return;
    } catch (error) {
      state.error = `上传 ${file.name} 失败:${error.message || error}`;
      return;
    }
  }

  // 图片或小文本 → 老路径
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

// uploadAttachment 已迁到 api.js 的 uploadChatAttachment,这里直接调。

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
  // 在 xmap 的 iframe 里,优先走 xmap 真实地图的 draw 方法 ——
  // 用户在 xmap 上画完图直接回传到聊天附件,体验比内嵌 canvas 流畅得多。
  if (embedMode.value && embedHostMethods.value.includes("draw")) {
    embedDrawPicker.visible = true;
    embedDrawPicker.busy = false;
    embedDrawPicker.error = "";
    return;
  }
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

// embed 模式下的小型类型选择器:用户挑形状 → 调 xmap.draw → 拿 result → 进附件。
// 不复用 mapModal 是因为 xmap 本身就是地图,没必要再叠一层 canvas;只需一个轻量的
// "选哪种形状"的弹层就够了。
const embedDrawPicker = reactive({
  visible: false,
  busy: false,
  busyType: "",
  error: ""
});

function closeEmbedDrawPicker() {
  embedDrawPicker.visible = false;
  embedDrawPicker.busy = false;
  embedDrawPicker.busyType = "";
  embedDrawPicker.error = "";
}

async function embedDrawShape(type) {
  if (embedDrawPicker.busy) return;
  embedDrawPicker.busy = true;
  embedDrawPicker.busyType = type;
  embedDrawPicker.error = "";
  // 选完形状后聊天面板让位 —— 用户需要在 xmap 真实地图上画图,聊天 panel 占着右侧
  // 1/3 屏会挡视野。draw 完(成功/失败/取消)再恢复显示。
  let chatHidden = false;
  try {
    if (window.parent) {
      window.parent.postMessage({ type: "jc.embed.hide" }, "*");
      chatHidden = true;
    }
    // 调 xmap 的 draw 方法。这条 Promise 会等用户在真实地图上把形状画完才 resolve。
    // xmap.draw 返回 { type, wktString, geoJSON } —— geoJSON 是 GeoJSON geometry。
    const result = await hostInvoke("draw", { type });
    const attachment = embedDrawResultToAttachment(result, type);
    form.attachments.push(attachment);
    appendComposerToken(attachment.displayLabel);
    closeEmbedDrawPicker();
  } catch (error) {
    console.error("[App.embed] draw failed:", error);
    embedDrawPicker.error = String(error?.message || error || "绘制失败");
    embedDrawPicker.busy = false;
    embedDrawPicker.busyType = "";
  } finally {
    // 无论成功失败都恢复聊天面板,不要把用户卡在隐藏状态
    if (chatHidden && window.parent) {
      window.parent.postMessage({ type: "jc.embed.show" }, "*");
    }
  }
}

// 把 xmap.draw 返回的 {type, wktString, geoJSON} 转成跟内置 mapModal 相同的附件结构,
// 后端 / LLM 那侧不需要分支 —— 都是 application/vnd.java-claw.map-regions+json,内含
// 一个 region 数组。这样后续逻辑(geometry 提取、空间过滤等)零改动。
function embedDrawResultToAttachment(result, requestedType) {
  const data = result?.data || result || {};
  const geom = data.geoJSON || data.geometry || null;
  const wkt = data.wktString || "";
  const xmapType = data.type || requestedType;

  // xmap 的 type 是中文,统一映射成内部 geometryType
  const geometryTypeMap = {
    "点": "point",
    "圆形": "circle",
    "矩形": "rectangle",
    "多边形": "polygon"
  };
  const geometryType = geometryTypeMap[xmapType] || "polygon";

  // 从 GeoJSON geometry 提取 polygon coords + bounds(xmap 已把 Circle 转成 Polygon)
  let polygon = [];
  let bounds = null;
  if (geom && Array.isArray(geom.coordinates)) {
    if (geom.type === "Polygon" && Array.isArray(geom.coordinates[0])) {
      polygon = geom.coordinates[0].map((p) => [Number(p[0]), Number(p[1])]);
    } else if (geom.type === "Point") {
      polygon = [[Number(geom.coordinates[0]), Number(geom.coordinates[1])]];
    }
    if (polygon.length) {
      const xs = polygon.map((p) => p[0]);
      const ys = polygon.map((p) => p[1]);
      bounds = {
        minLng: Math.min(...xs),
        maxLng: Math.max(...xs),
        minLat: Math.min(...ys),
        maxLat: Math.max(...ys)
      };
    }
  }

  const region = {
    id: `xmap-${Date.now()}`,
    name: "区域1",
    geometryType,
    srid: 4326,
    geometry: geom,
    wkt,
    bounds,
    polygon,
    source: "xmap.draw"
  };
  const payload = {
    type: "map-regions",
    generatedAt: new Date().toISOString(),
    source: "xmap.draw",
    regions: [region]
  };
  return {
    name: `map-regions-${Date.now()}.json`,
    displayLabel: nextAttachmentDisplayLabel("map"),
    contentType: "application/vnd.java-claw.map-regions+json",
    content: JSON.stringify(payload, null, 2)
  };
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

// 下拉选项的本地缓存:agents / llms 两个列表都按 tenant 分键缓存到 localStorage,
// TTL 7 天。bootstrap 启动 / refresh 时:
//   1) 先用缓存值立即把 agents.value / llms.value 填上 —— UI 不会出现下拉空 → 按钮
//      disabled → 用户什么也点不动的死锁;
//   2) 再异步发请求拉最新数据;
//      - 成功 → 用最新数据覆盖 + 写回 localStorage;
//      - 失败 → 不改 .value,继续用缓存,等下次自然刷新重试。
// 之前因为按钮的 chatRunBusy / hasAvailableLlms 联动,只要 listAgents 一时抖动就让
// "重新发送上一条请求""发送"按钮全部 disabled,用户被卡住没出路。缓存兜底解决这个。
const DROPDOWN_CACHE_TTL_MS = 7 * 24 * 3600 * 1000;
function dropdownCacheKey(kind) {
  const tenant = authUser.activeTenantId || "default";
  return `javaclaw.cache.${tenant}.${kind}`;
}
function readDropdownCache(kind) {
  try {
    const raw = window.localStorage.getItem(dropdownCacheKey(kind));
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return null;
    if (!Array.isArray(parsed.value)) return null;
    if (typeof parsed.savedAt !== "number") return null;
    if (Date.now() - parsed.savedAt > DROPDOWN_CACHE_TTL_MS) return null;
    return parsed.value;
  } catch (error) {
    return null;
  }
}
function writeDropdownCache(kind, value) {
  try {
    if (!Array.isArray(value)) return;
    window.localStorage.setItem(dropdownCacheKey(kind), JSON.stringify({
      savedAt: Date.now(),
      value
    }));
  } catch (error) {
    // localStorage 满 / 隐私模式禁用 → 静默忽略,缓存只是 UX 优化不是必需。
  }
}
function clearDropdownCache() {
  try {
    for (const kind of ["agents", "llms"]) {
      window.localStorage.removeItem(dropdownCacheKey(kind));
    }
  } catch (error) { /* ignore */ }
}

// 最近一次访问的 session id 也按 tenant 分键缓存。bootstrap 完毕后如果 form.sessionId
// 仍空,优先用这个缓存值自动恢复 —— 用户重开 iframe / 刷主控台时直接落到上次的会话上,
// 不用每次从空白开始。
function lastSessionCacheKey() {
  const tenant = authUser.activeTenantId || "default";
  return `javaclaw.cache.${tenant}.lastSession`;
}
function readLastSessionId() {
  try {
    const raw = window.localStorage.getItem(lastSessionCacheKey());
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return null;
    if (typeof parsed.sessionId !== "string" || !parsed.sessionId) return null;
    if (typeof parsed.savedAt !== "number") return null;
    if (Date.now() - parsed.savedAt > DROPDOWN_CACHE_TTL_MS) return null;
    return { sessionId: parsed.sessionId, agentId: parsed.agentId || "" };
  } catch (error) { return null; }
}
function writeLastSessionId(sessionId, agentId) {
  if (!sessionId) return;
  try {
    window.localStorage.setItem(lastSessionCacheKey(), JSON.stringify({
      savedAt: Date.now(),
      sessionId,
      agentId: agentId || ""
    }));
  } catch (error) { /* ignore */ }
}
function clearLastSessionId() {
  try { window.localStorage.removeItem(lastSessionCacheKey()); } catch (error) { /* ignore */ }
}

async function bootstrap() {
  // 步骤 1: 立刻从 localStorage 读缓存填进 agents.value / llms.value。
  // 即使后续 listAgents / listLlms 失败,UI 也不会出现下拉为空 → 按钮全 disabled
  // → 用户被锁死的情况(典型场景:listAgents 一时 401 / 瞬时网络抖,bootstrap 跑完
  // agents.value 是空数组,顶部 agent 下拉显示"自动路由"但其它选项缺失,而依赖
  // hasAvailableLlms / chatRunBusy 的"重新发送"按钮也同样不可点)。
  const cachedAgents = readDropdownCache("agents");
  const cachedLlms = readDropdownCache("llms");
  if (cachedAgents && agents.value.length === 0) agents.value = cachedAgents;
  if (cachedLlms && llms.value.length === 0) llms.value = cachedLlms;

  // 步骤 2: 用 allSettled 把两条请求隔离,任何一条失败不影响另一条。
  // 成功的那条覆盖 .value 并写回缓存;失败的那条保持缓存值不动,下次自然重试。
  const [agentsResult, llmsResult] = await Promise.allSettled([
    listAgents(),
    listLlms()
  ]);
  if (agentsResult.status === "fulfilled") {
    agents.value = agentsResult.value;
    writeDropdownCache("agents", agentsResult.value);
  } else {
    console.warn("[bootstrap] listAgents failed:", agentsResult.reason);
  }
  if (llmsResult.status === "fulfilled") {
    llms.value = llmsResult.value;
    writeDropdownCache("llms", llmsResult.value);
  } else {
    console.warn("[bootstrap] listLlms failed:", llmsResult.reason);
  }
  if (!form.agentId && agents.value.length > 0) {
    form.agentId = agents.value[0].agentId;
    searchForm.agentId = agents.value[0].agentId;
  }
  if (!form.llmConfigId && llms.value.length > 0) {
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
  // embed 模式下另有内嵌方式(挂在用户消息下方),不再用顶部独立面板。
  if (embedMode.value) return false;
  return Array.isArray(state.runPlan.steps) && state.runPlan.steps.length > 0;
});

// embed 模式下 plan 不再走顶部横条,而是内嵌到 conversation timeline 里、紧跟用户
// 消息显示。判定:仅 embed + 当前 run 跟 plan 关联 + 仍有未 COMPLETED/SKIPPED step。
// plan 全部 step 完成后自动隐藏(用户已经在主气泡看到完整报告,plan 信息冗余)。
function shouldShowInlinePlan(runId) {
  if (!embedMode.value) return false;
  if (!runId || state.runPlan.runId !== runId) return false;
  const steps = state.runPlan.steps;
  if (!Array.isArray(steps) || !steps.length) return false;
  return steps.some((step) => step.status !== "COMPLETED" && step.status !== "SKIPPED");
}
function firstUserBlockOf(group) {
  if (!group || !Array.isArray(group.blocks)) return null;
  return group.blocks.find((b) => isUserBlock(b)) || null;
}

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

  if (normalized.type === "TOKEN_USAGE") {
    // run 结束时后端会发一条 TOKEN_USAGE,content 是 JSON {prompt,completion,total}。
    // 实时把数字塞到 liveAssistant.tokenUsage,run 终止时 reload 拉到 message 字段后会
    // 接管显示;短促一两秒的过渡里 live bubble 也能看到最新数字,体验更连贯。
    try {
      const payload = JSON.parse(normalized.content || "{}");
      state.liveAssistant.tokenUsage = {
        prompt: payload.prompt || 0,
        completion: payload.completion || 0,
        total: payload.total || 0
      };
    } catch (error) {
      console.warn("[token_usage.parse_failed]", normalized.content, error);
    }
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
        // 中间状态输出走"替换式":只有一个 live bubble,新 step 的 MODEL_OUTPUT 直接
        // 覆盖上一 step 残留的 content/thinking,不再冻结成独立气泡堆叠。每个 step 自检
        // / 过渡说明 / 中间分析的实时可见性由这个 live bubble 提供;真正的最终交付物
        // 由 artifact.markdown 等渲染工具落成 transcript message 永久保留。
        const stepMatch = raw.match(/\bstep=(\d+)\b/);
        const nextStep = stepMatch ? parseInt(stepMatch[1], 10) : null;
        const prevStep = state.liveAssistant.step;
        const sameRun = state.liveAssistant.runId === evtRunId;
        if (sameRun && prevStep !== null && nextStep !== null && nextStep !== prevStep) {
          state.liveAssistant.thinking = "";
        }
        state.liveAssistant.runId = evtRunId;
        state.liveAssistant.content = streamed;
        if (nextStep !== null) state.liveAssistant.step = nextStep;
      }
    } else if (phase === "MODEL_THINKING") {
      // 推理模型的 reasoning_content 累积流,跟 MODEL_OUTPUT 平行,前端单独显示(可折叠)。
      const streamed = stripRunStatusPrefix(raw).trim();
      if (streamed) {
        const evtRunId = normalized.runId || state.currentRunId;
        state.liveAssistant.runId = evtRunId;
        state.liveAssistant.thinking = streamed;
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
    state.liveAssistant.thinking = "";
    state.liveAssistant.step = null;
  }

  if (normalized.type === "RUN_FAILED") {
    delete state.renderCompletionHintByRun[normalized.runId];
    state.liveAssistant.runId = "";
    state.liveAssistant.content = "";
    state.liveAssistant.thinking = "";
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
  // RUN_STARTED 之后给 SimpleAgentRunner 留点时间种 plan + RunPlanPersister.sync 落 plan_json,
  // 然后从 run_record 把 plan_json 拉过来 hydrate。这是"PLAN_UPDATED 事件链路有问题"的兜底:
  // 哪怕 ws 事件丢了 / 被某层过滤吃了,前端也能从 DB 拉到 plan 显示出来。
  if (normalized.type === "RUN_STARTED") {
    scheduleRunRefresh(800);
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
  // 关键:embed=1 必须跨导航保留 —— 任何子路由跳走丢了它,embedMode 立刻变 false,左栏就回来。
  if (route.query.embed) {
    query.embed = route.query.embed;
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

// 哪些 displayType 配在聊天气泡里直接展示。原则:**只有给用户看的最终交付物**才上聊天。
//   - markdown / artifact:用户最终产物(报告、文件) → 上
//   - table / tool_error / 等:db.query 中间结果 / 工具诊断日志 → 不上,只去复盘看
// 之前所有 displayType 都被塞进聊天,导致 LLM 每次 db.query 的 SQL/rows JSON 直接糊在屏幕上,
// 用户感觉是"内部迭代日志泄漏"。
const CHAT_RENDERABLE_DISPLAY_TYPES = new Set(["markdown", "artifact"]);

function toolEventRenderPayload(event) {
  const parsedContent = parseToolEventContent(event?.content);
  const parsed = parseJson(parsedContent.payload);
  if (!parsed?.displayType) {
    return null;
  }
  return CHAT_RENDERABLE_DISPLAY_TYPES.has(parsed.displayType) ? parsed : null;
}

// 从事件 content 的 "tool=X" 抽真实工具名(db.query / artifact.markdown / db.schema.inspect 等)。
// 之前 displayType="table" 被翻成假工具名 "table.render",误导用户以为有这么个工具
// —— 实际上是 db.query 在返回 displayType="table" 的展示载荷。
function toolNameFromEvent(event, displayType) {
  const content = String(event?.content || "");
  const match = content.match(/\btool=([\w.-]+)/);
  if (match) return match[1];
  // 兜底:实在抽不到才按 displayType 推断,artifact.markdown 仍合法,其它 displayType
  // 没有真工具名时返 "artifact" 通用标签,避免回到 "table.render"。
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
    toolName: toolNameFromEvent(event, payload.displayType),
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
    toolName: toolNameFromEvent(event, payload.displayType),
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
    // 真后端分页:每次只拉当前页(默认 15 条),关键字搜索也走后端,翻页/搜索都调接口。
    const response = await listSessionsPaged({
      agentId: state.resolvedAgentId || form.agentId || undefined,
      page: recentSessionsPage.page,
      size: recentSessionsPage.size,
      keyword: recentSessionsFilter.keyword
    });
    recentSessions.value = response.rows || [];
    recentSessionsTotal.value = response.total || 0;
  } catch (error) {
    state.error = error.message;
  }
}

function closeContextMenu() {
  contextMenu.visible = false;
  contextMenu.session = null;
}

// 切会话 / 退出 / 清屏时:清掉本地 chat 视图状态,但**不**关 globalSocket —— 它跟登录一起活,
// 跟会话无关。globalSocket 的关闭只发生在 logout / 401 时,由 ensureGlobalEventSocket 那一头管。
function clearStream() {
  // 这里只是个钩子,真要清空 chat 视图状态(transcript / events / liveAssistant)请调
  // resetChatViewState 之类。保留这个函数是为了不破坏旧调用点;暂时是 no-op。
}

/**
 * 启动 / 维护持久 WebSocket。已经存在就不再开,断了由 openGlobalEventSocket 内部指数退避重连。
 * onMounted 拿到 authUser 之后调一次,logout / 401 时 closeGlobalEventSocket。
 */
function ensureGlobalEventSocket() {
  if (globalSocket.value) return;
  console.log("[global.ws.ensure]");
  globalSocket.value = openGlobalEventSocket({
    onOpen: () => {
      // 真连上之后清掉可能残留的"连接中断"banner
      if (state.error && /连接|流中断|重连|WebSocket/.test(state.error)) {
        state.error = "";
      }
    },
    onEvent: dispatchGlobalEvent,
    onClose: () => {
      console.log("[global.ws.closed]");
    },
    onError: () => {
      console.warn("[global.ws.error]");
    }
  });
}

function closeGlobalEventSocket() {
  if (globalSocket.value?.close) {
    try { globalSocket.value.close(); } catch (_) { /* ignore */ }
  }
  globalSocket.value = null;
}

/**
 * 全局事件分发器:globalSocket 收到的每条事件都先过这里。
 *
 * 路由规则:
 *   1) event.sessionId === 当前打开的 form.sessionId → 喂给 pushEvent,刷主对话流
 *   2) 否则只记 sidebar 活动 / 终态红点,不污染当前视图
 *   3) RUN_COMPLETED/FAILED/CANCELLED 时不管是不是当前 session,都顺手刷 sessionRunStatus
 *      缓存(refreshRunStatuses 会做),确保 sidebar 标的"任务执行中"badge 跟 DB 一致
 */
function dispatchGlobalEvent(event) {
  if (!event || !event.sessionId) return;
  const isCurrent = event.sessionId === form.sessionId;
  if (isCurrent) {
    pushEvent(event);
    if (event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED" || event.type === "RUN_CANCELLED") {
      refreshRun();
      refreshSession();
      refreshMemories();
      refreshRecentSessions();
    }
    return;
  }
  // 不是当前 session 的事件:别去碰主对话流,只更新 sidebar 元数据
  const slot = sessionActivity[event.sessionId] || { lastEventAt: 0, terminalType: null, runId: null };
  slot.lastEventAt = Date.now();
  slot.runId = event.runId || slot.runId;
  if (event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED" || event.type === "RUN_CANCELLED") {
    slot.terminalType = event.type;
  }
  sessionActivity[event.sessionId] = slot;
  // 终态时也顺便刷一下最近会话列表 / 该 session 的 run status,sidebar 的"执行中"角标该掉色就掉色
  if (slot.terminalType) {
    refreshRecentSessions();
  }
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
  // 之前这里见到 llms.value 是空就直接弹"后端没加载到 LLM"阻断发送 —— 太死板:很多时候
  // 是 listLlms 那条请求暂时失败导致前端误判。这里先尝试主动刷一次 LLM 列表,真不行再
  // 让请求带空 llmConfigId 走后端的 default 解析,后端自己会从 DB 找 enabled=true 的兜底。
  if (!hasAvailableLlms.value) {
    try {
      const fresh = await listLlms();
      llms.value = fresh;
      writeDropdownCache("llms", fresh);
    } catch { /* swallow, continue */ }
  }
  if (isActiveRunStatus(state.run?.status)) {
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
    // embed 模式 + 宿主注册过方法 → 自动追加 host_methods.json 让 LLM 看见可调方法清单
    if (embedMode.value && embedHostMethods.value.length) {
      outgoingAttachments.push({
        name: "host_methods.json",
        contentType: "application/json",
        content: JSON.stringify({
          channel: "host.invoke",
          usage: "Call host.invoke({method, payload}) to invoke any of these host methods.",
          methods: embedHostMethods.value
        })
      });
    }
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
    // 后端检测到 session 已经有 run 在跑 → 返回那个 run 的 runId + status="already_running"。
    // 不消费用户输入(消息留在 composer 里给他完成后重发),直接 attach 到正在跑的 run,把它的
    // 事件流接到当前 UI,让用户能看到正在做什么。比单纯弹一个"session is busy"有用得多。
    if (accepted && accepted.status === "already_running") {
      console.log("[chat.send.already_running] attach to", accepted.runId);
      form.sessionId = accepted.sessionId;
      state.error = "当前会话有正在运行的任务，已接入它的实时事件流。完成后请重新发送您的消息。";
      try {
        await attachWebSocketRun({
          runId: accepted.runId,
          sessionId: accepted.sessionId,
          agentId: accepted.agentId,
          llmConfigId: accepted.llmConfigId,
          llmModel: accepted.llmModel,
          userId: payload.userId || form.userId
        });
      } catch (attachErr) {
        console.warn("[chat.send.already_running.attach_failed]", attachErr?.message || attachErr);
      }
      return;
    }
    lastSentRequest.value = {
      message: outgoingMessage,
      references: outgoingReferences,
      attachments: outgoingAttachments
    };

    form.sessionId = accepted.sessionId;
    // session 一拿到就开桥 socket,后续 LLM 调 host.invoke 才能传回浏览器执行
    if (embedMode.value) {
      ensureEmbedBridge(accepted.sessionId);
    }
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

    // 新通信架构:不再开 per-run / per-session WebSocket。POST 已经让后端 fire-and-forget 启动
    // run,事件会通过常驻的 globalSocket 推过来(dispatchGlobalEvent 路由到当前 form.sessionId
    // 的视图刷新)。前端只负责确保 globalSocket 在跑就行。
    ensureGlobalEventSocket();

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
  // 不再开 session-level WS,实时事件由 globalSocket 总线推过来 —— 这里只确保它已经在跑。
  ensureGlobalEventSocket();
  try {
    state.session = await getSession(form.sessionId);
    syncPendingMessagesWithSession();
    reconcileLiveAssistant();
    await Promise.all([
      refreshRecentSessions(),
      refreshRunStatuses()
    ]);
    // 进入 / 刷新会话时,如果有 active run,先把它累积到现在的事件 snapshot 拉一份灌进 state.events,
    // 这样 UI 立刻能看到当前进度;之后新事件由 globalSocket 自然续上,processedEventSigs 去重。
    try {
      await recoverActiveRunForSession();
    } catch (recoverErr) {
      console.warn("[refreshSession.recover_failed]", recoverErr?.message || recoverErr);
    }
  } catch (error) {
    // session 不在(404 / 被删除 / 跨租户访问被拒)→ 静默回落到"新对话"状态。
    // 但**有个关键陷阱**:刚发完新消息时,后端那一边 createSession 的事务还没 commit,
    // 这个瞬间 GET /api/sessions/{id} 会短暂 404 —— 此时绝对不能跳新对话(会把用户
    // 刚发的消息整个吞掉)。判定逻辑:只有在"页面已经持续显示这个 sessionId 一段时间
    // 都拉不到"才认为真不在。
    //   - state.currentRunId 存在 → 当前肯定刚发了消息 / 有活跃 run,这段时间内的 404
    //     都是事务延迟,要重试不要跳新对话
    //   - state.pendingMessages 非空 → 用户消息还没 echo 回来,同理
    //   - 否则才安全跳新对话(纯粹是地址栏里塞了个老 sessionId / localStorage 缓存里有个
    //     被别人删掉的 sessionId)。
    const status = error?.status;
    const looksLikeNotFound = status === 404
      || /not\s*found|不存在|无此会话|does not exist/i.test(String(error?.message || ""));
    const justSentOrRunning = !!state.currentRunId || state.pendingMessages.length > 0;
    if (looksLikeNotFound && !justSentOrRunning) {
      console.info("[refreshSession.fallback_new]", form.sessionId, status);
      try { clearLastSessionId(); } catch (_) { /* ignore */ }
      createNewSession();
      return;
    }
    // 事务延迟造成的瞬时 404 不报错也不跳走 —— 下一次 scheduleSessionRefresh(250)
    // 自然就拉到了。只在确实不是 404 / 不是事务延迟的场景才把错误条挂出来。
    if (!looksLikeNotFound) {
      state.error = error.message;
    }
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
  // 总用 DB plan_json 覆盖。之前这里有"已经从 PLAN_UPDATED 拿到 snapshot 就不覆盖"
  // 的保护,但实际上 preflight 自动把 wave-0 step 跑成 COMPLETED 并 RunPlanPersister.sync
  // 到 DB,**没再 emit PLAN_UPDATED**;再加上 embed 模式下 ws 事件偶发丢失,
  // 这条保护反而让前端永远卡在最初 seed 的 PENDING 状态。RunPlanPersister.sync 同样会
  // 在每次 plan.update 后同步,DB 不会落后于事件。直接覆盖最稳。
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

/**
 * "刷新 / 切回会话时把 active run 状态接进来"的轻量版本。
 *
 * 不再开 WebSocket —— globalSocket 会自然推 live 事件;这里只做两件:
 *   1) 把 state.currentRunId / state.run / liveAssistant 同步成那个 run 的视图
 *   2) HTTP 拉 run 累积的事件 snapshot 灌进 state.events,让用户立刻看到当前进度
 *
 * 接下来 live 事件由 globalSocket 自然续上,processedEventSigs 内部按
 * type|runId|timestamp|content 去重,snapshot 跟 globalSocket 推过来的重叠不会双计。
 */
async function attachWebSocketRun(run) {
  if (!run?.runId) return;
  state.currentRunId = run.runId;
  state.run = run;
  state.resolvedAgentId = run.agentId || state.resolvedAgentId || form.agentId;
  searchForm.runId = run.runId;
  searchForm.sessionId = run.sessionId || form.sessionId;
  searchForm.agentId = run.agentId || searchForm.agentId;
  state.liveAssistant.runId = run.runId;
  try {
    const events = await getRunEventSnapshot(run.runId);
    if (Array.isArray(events) && events.length) {
      for (const evt of events) {
        pushEvent(evt);
      }
    }
  } catch (err) {
    console.warn("[run.snapshot.failed]", run.runId, err?.message || err);
  }
}

async function recoverActiveRunForSession() {
  if (!form.sessionId) return;
  try {
    const activeRun = await getActiveRun(form.sessionId);
    if (activeRun && isActiveRunStatus(activeRun.status)) {
      await attachWebSocketRun(activeRun);
    }
  } catch (error) {
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

const memorySelection = reactive(new Set());
// 前端切片分页:页索引 0-based,size 调整时回到第 0 页
const memoryPage = reactive({ page: 0, size: 12 });
const pagedMemories = computed(() => {
  const start = memoryPage.page * memoryPage.size;
  return state.memories.slice(start, start + memoryPage.size);
});
const memoryTotalPages = computed(() =>
  Math.max(1, Math.ceil(state.memories.length / memoryPage.size))
);
const memoryAllSelected = computed(() => {
  // "全选"指当前页全选(避免选中跨页)
  if (!pagedMemories.value.length) return false;
  return pagedMemories.value.every(n => memorySelection.has(n.id));
});
function toggleMemorySelection(noteId, checked) {
  if (checked) memorySelection.add(noteId);
  else memorySelection.delete(noteId);
}
function toggleSelectAllMemories(checked) {
  if (checked) pagedMemories.value.forEach(n => memorySelection.add(n.id));
  else pagedMemories.value.forEach(n => memorySelection.delete(n.id));
}
// 截断长文本(给列表预览用),完整内容用 :title 鼠标悬浮可看。
function truncateText(text, max = 100) {
  if (text == null) return "";
  const s = String(text);
  return s.length > max ? s.slice(0, max) + "..." : s;
}

/**
 * 通用列表分页 + 搜索 + 多选 controller。
 * 给所有"列表管理"页面统一行为 —— 搜索 / 翻页 / 改 size / 批量选 / 批量删都走同一套 state。
 *
 * 用法:
 *   const ctrl = makePagedList(
 *     computed(() => state.catalog.agents),     // 反应式 rows 源
 *     {
 *       idGetter: r => r.agentId,                // 唯一 id
 *       searchFields: ["agentId", "displayName", "description"],
 *       size: 12
 *     }
 *   );
 * 模板里:
 *   v-for="row in ctrl.paged.value"
 *   <input type="checkbox" :checked="ctrl.selection.has(ctrl.idOf(row))" @change="ctrl.toggle(ctrl.idOf(row), $event.target.checked)" />
 */
function makePagedList(rowsRef, opts) {
  const idGetter = opts.idGetter;
  const searchFields = opts.searchFields || [];
  const state = reactive({
    page: 0,
    size: opts.size || 12,
    keyword: ""
  });
  const selection = reactive(new Set());
  const filtered = computed(() => {
    const kw = state.keyword.trim().toLowerCase();
    if (!kw) return rowsRef.value || [];
    return (rowsRef.value || []).filter(row =>
      searchFields.some(f => String(row[f] ?? "").toLowerCase().includes(kw))
    );
  });
  const paged = computed(() => {
    const start = state.page * state.size;
    return filtered.value.slice(start, start + state.size);
  });
  const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / state.size)));
  const allSelected = computed(() =>
    paged.value.length > 0 && paged.value.every(r => selection.has(idGetter(r)))
  );
  function toggle(id, checked) {
    if (checked) selection.add(id);
    else selection.delete(id);
  }
  function toggleAll(checked) {
    if (checked) paged.value.forEach(r => selection.add(idGetter(r)));
    else paged.value.forEach(r => selection.delete(idGetter(r)));
  }
  // keyword 改 → 回到第 1 页 (在 watch 里实现)
  watch(() => state.keyword, () => { state.page = 0; });
  return { state, selection, filtered, paged, totalPages, allSelected, toggle, toggleAll, idOf: idGetter };
}

async function batchDeleteMemories() {
  const ids = Array.from(memorySelection);
  if (!ids.length) return;
  if (!confirm(`确认批量删除 ${ids.length} 条 memory note?不可恢复。`)) return;
  let failed = 0;
  for (const id of ids) {
    try { await deleteMemoryNoteAdmin(id); }
    catch (error) { failed++; console.warn("[memory.batch_delete_failed]", id, error); }
  }
  memorySelection.clear();
  await refreshMemories();
  if (failed > 0) state.error = `批量删除 ${ids.length - failed} 条成功,${failed} 条失败`;
}

// 通用 scope 归属判定 —— 跟 SessionVisibility 三档一致:
//   sysadmin (session.read.all)        → 看到/管理所有
//   tenant-admin (session.read.tenant) → 仅本租户
//   普通用户                            → 仅本人 scope
// 用在 LLM/Skill/Knowledge/Tool/Datasource/Agent 等带 scope 字段的资源。
function canManageScoped(row) {
  const v = sessionVisibilityForUi.value;
  if (v.readAll) return true;
  const t = row.scopeType || row.scope?.scopeType || "PUBLIC";
  if (t === "PUBLIC") return v.readAll; // 公共资源仅 sysadmin 能改
  const tenantId = row.scopeTenantId || row.scope?.scopeTenantId || "";
  const userId = row.scopeUserId || row.scope?.scopeUserId || "";
  if (v.readTenant) return tenantId === v.tenantId;
  if (t === "USER") return userId === v.userId;
  return false;
}

// ===== 各列表统一控制器(分页/搜索/多选)=====
const agentsList     = makePagedList(computed(() => state.catalog.agents),       { idGetter: r => r.agentId,  searchFields: ["agentId", "displayName", "description"], size: 12 });
const llmsList       = makePagedList(computed(() => state.catalog.llms),         { idGetter: r => r.id,       searchFields: ["id", "displayName", "model", "provider", "baseUrl"], size: 12 });
const knowledgeList  = makePagedList(computed(() => state.catalog.knowledge),    { idGetter: r => r.id,       searchFields: ["id", "title", "content", "source"], size: 12 });
const toolsList      = makePagedList(computed(() => state.catalog.tools),        { idGetter: r => r.id,       searchFields: ["id", "toolName", "displayName", "description"], size: 12 });
const skillsList     = makePagedList(computed(() => state.catalog.skills),       { idGetter: r => r.id,       searchFields: ["id", "skillName", "description", "agentId"], size: 12 });
const datasourcesList= makePagedList(computed(() => state.catalog.datasources),  { idGetter: r => r.id,       searchFields: ["id", "displayName", "jdbcUrl", "username", "dialect"], size: 12 });
const usersList      = makePagedList(computed(() => state.catalog.users),        { idGetter: r => r.id,       searchFields: ["id", "username", "displayName", "email", "preferredTenantId"], size: 12 });
const tenantsList    = makePagedList(computed(() => state.catalog.tenants),      { idGetter: r => r.id,       searchFields: ["id", "code", "name", "kind", "description"], size: 12 });
const rolesList      = makePagedList(computed(() => state.catalog.roles),        { idGetter: r => r.id,       searchFields: ["id", "code", "name", "description", "tenantId"], size: 12 });
const oauthClientsList = makePagedList(computed(() => state.catalog.oauthClients), { idGetter: r => r.clientId, searchFields: ["clientId", "displayName", "ownerUserId", "description"], size: 12 });

// ===== 通用批量删除模板:每个列表一个 =====
async function runBatchDelete(list, label, deleter, refresher) {
  const ids = Array.from(list.selection).filter(Boolean);
  if (!ids.length) return;
  if (!confirm(`确认批量删除 ${ids.length} 条${label}?不可恢复。`)) return;
  let failed = 0;
  for (const id of ids) {
    try { await deleter(id); }
    catch (error) { failed++; console.warn(`[${label}.batch_delete_failed]`, id, error); }
  }
  list.selection.clear();
  await refresher();
  if (failed > 0) state.error = `批量删除 ${ids.length - failed} 条成功,${failed} 条失败`;
}
const batchDeleteAgents      = () => runBatchDelete(agentsList,     "Agent",      deleteAgentDefinitionAdmin, async () => { await refreshCatalog(); agents.value = await listAgents(); });
const batchDeleteLlms        = () => runBatchDelete(llmsList,       "LLM 配置",   deleteAdminLlm,             async () => { await refreshCatalog(); llms.value = await listLlms(); writeDropdownCache("llms", llms.value); });
const batchDeleteKnowledge   = () => runBatchDelete(knowledgeList,  "知识条目",   deleteKnowledgeEntry,       refreshCatalog);
const batchDeleteTools       = () => runBatchDelete(toolsList,      "工具定义",   deleteToolDefinition,       refreshCatalog);
const batchDeleteSkills      = () => runBatchDelete(skillsList,     "Skill 定义", deleteSkillDefinition,      refreshCatalog);
const batchDeleteDatasources = () => runBatchDelete(datasourcesList,"数据源",     deleteDatasource,           refreshCatalog);
const batchDeleteUsers       = () => runBatchDelete(usersList,      "用户",       async (id) => { if (id === "admin") throw new Error("内置 admin 不可删"); await adminDeleteUser(id); }, refreshAdminCatalog);
const batchDeleteTenants     = () => runBatchDelete(tenantsList,    "租户",       async (id) => { if (id === "system") throw new Error("system 租户不可删"); await adminDeleteTenant(id); }, refreshAdminCatalog);
const batchDeleteRoles       = () => runBatchDelete(rolesList,      "角色",       async (id) => { if (id === "system-super-admin" || id === "system-user") throw new Error("内置角色不可删"); await adminDeleteRole(id); }, refreshAdminCatalog);
const batchDeleteOauthClients= () => runBatchDelete(oauthClientsList,"OAuth 客户端", adminDeleteOauthClient, refreshAdminCatalog);

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
  enabled: true,
  scope: { ...DEFAULT_SCOPE }
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
    agentEditor.scope = readScopeFromView(existing);
  } else {
    agentEditor.isNew = true;
    agentEditor.agentId = "";
    agentEditor.displayName = "";
    agentEditor.description = "";
    agentEditor.systemPrompt = "";
    agentEditor.agentMarkdown = "";
    agentEditor.memoryMarkdown = "";
    agentEditor.enabled = true;
    agentEditor.scope = { ...DEFAULT_SCOPE };
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
      enabled: agentEditor.enabled,
      ...flattenScopeForRequest(agentEditor.scope)
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

// Token 用量页:把 range 选项映射到 [from,to] ISO 字符串,然后并行拉三组数据。
// summary 按当前 groupBy 维度;timeseries 用 day 桶在 30d 用 hour 不太合理,固定 day;
// recent 拿最近 50 条明细。任一失败把 error 显示在面板顶部。
async function reloadUsage() {
  usageState.loading = true;
  usageState.error = "";
  try {
    const now = new Date();
    const rangeMs = usageState.range === "1d" ? 86400_000
                  : usageState.range === "30d" ? 30 * 86400_000
                  : 7 * 86400_000;
    const fromIso = new Date(now.getTime() - rangeMs).toISOString();
    const toIso = now.toISOString();
    const interval = usageState.range === "1d" ? "hour" : "day";
    const [summary, timeseries, recent] = await Promise.all([
      fetchUsageSummary({ from: fromIso, to: toIso, groupBy: usageState.groupBy }),
      fetchUsageTimeseries({ from: fromIso, to: toIso, interval, groupBy: "none" }),
      fetchUsageRecent({ limit: 50 })
    ]);
    usageState.summary = Array.isArray(summary) ? summary : [];
    usageState.timeseries = Array.isArray(timeseries) ? timeseries : [];
    usageState.recent = Array.isArray(recent) ? recent : [];
  } catch (error) {
    usageState.error = error.message || "load usage failed";
  } finally {
    usageState.loading = false;
  }
}

// 总计 = 当前 summary 全部行求和;groupBy='none' 时 summary 只有 1 行,直接拿即可。
const usageTotal = computed(() => {
  let prompt = 0, completion = 0, total = 0, msg = 0, run = 0, sess = 0;
  for (const row of usageState.summary) {
    prompt += row.promptTokens;
    completion += row.completionTokens;
    total += row.totalTokens;
    msg += row.messageCount;
    run += row.runCount;
    sess += row.sessionCount;
  }
  return { promptTokens: prompt, completionTokens: completion, totalTokens: total,
           messageCount: msg, runCount: run, sessionCount: sess };
});

// 时间序列折线图 ECharts option。groupBy='none' 时一条线;否则按维度多条线。
// 当前简化:只画 totalTokens 一条 series,prompt/completion 留给表格细看。
const usageChartOption = computed(() => {
  const points = usageState.timeseries;
  if (!points.length) return null;
  const xs = points.map(p => p.bucketStart.replace("T", " ").slice(0, 16));
  const ys = points.map(p => p.totalTokens);
  return {
    tooltip: { trigger: "axis" },
    grid: { left: 56, right: 16, top: 16, bottom: 32 },
    xAxis: { type: "category", data: xs, axisLabel: { rotate: 30 } },
    yAxis: { type: "value", name: "tokens" },
    series: [{ name: "total", type: "line", data: ys, smooth: true, areaStyle: {} }]
  };
});

const usageGroupByLabel = computed(() => ({
  none: "总计", tenant: "租户", app: "应用", user: "用户", agent: "Agent", model: "模型"
})[usageState.groupBy] || usageState.groupBy);

// ---------------------------------------------------------------------------------------
// Agent 实验室 methods

async function labReload() {
  labState.loading = true;
  labState.error = "";
  try {
    const [tasks, skills] = await Promise.all([
      labListTasks(),
      labState.allSkills.length ? Promise.resolve(labState.allSkills) : labListAllSkills().catch(() => [])
    ]);
    labState.tasks = tasks;
    if (skills && Array.isArray(skills)) labState.allSkills = skills;
  } catch (error) {
    labState.error = error.message || "load tasks failed";
  } finally {
    labState.loading = false;
  }
}

async function labOpenDetail(taskId) {
  // 切换详情页 + 启动轮询(只在 task 仍在跑时轮询,终止后停)
  labStopPolling();
  labState.currentDetail = null;
  // 切详情时清空编辑器 taskId,labReloadDetail 才会重新 prefill
  labDetailEditor.taskId = "";
  if (!taskId) {
    if (route.name === "lab" && route.params.taskId) {
      router.push({ name: "lab" });
    }
    return;
  }
  router.push({ name: "lab", params: { taskId } });
  await labReloadDetail(taskId);
  if (labIsRunning(labState.currentDetail?.task)) {
    labStartPolling(taskId);
  }
}

async function labReloadDetail(taskId) {
  try {
    labState.currentDetail = await labTaskDetail(taskId);
    // 详情页内嵌编辑器跟着 task 同步:每次拉到新 detail 就 prefill 最新值,
    // 避免用户手改了一半我们覆盖 → 只在 taskId 切换时重置
    if (labDetailEditor.taskId !== taskId) {
      labLoadDetailEditor(labState.currentDetail.task);
    }
    if (!labIsRunning(labState.currentDetail.task)) {
      labStopPolling();
    }
  } catch (error) {
    labState.error = error.message || "load detail failed";
    labStopPolling();
  }
}

function labIsRunning(task) {
  if (!task) return false;
  return task.status === "PENDING" || task.status === "RUNNING";
}

function labStartPolling(taskId) {
  labStopPolling();
  labState.pollingTimer = setInterval(() => labReloadDetail(taskId), 3000);
}

function labStopPolling() {
  if (labState.pollingTimer) {
    clearInterval(labState.pollingTimer);
    labState.pollingTimer = null;
  }
}

// (V38: 旧的 labStructuredTestCasesToJson / labJsonToStructuredTestCases 已删除 ——
//  用户不再手写测试用例,改成写 constraintRules,由后端 meta-LLM 派生场景。)
// 打开创建 modal 前强制刷一次 admin catalog,保证 LLM/Agent 下拉源是最新的。
// 之前只信任 init 时的 refreshCatalog,如果用户中途新增 LLM 然后打开 lab,下拉里就少。
async function labOpenCreateModal() {
  labShowCreate.value = true;
  try {
    await Promise.all([
      refreshCatalog(),
      llms.value && llms.value.length === 0 ? listLlms().then(v => { llms.value = v; }) : Promise.resolve()
    ]);
  } catch (err) {
    console.warn("[lab.open_create.refresh_failed]", err);
  }
}

// (V38: labAddTestCase 已删除 —— UI 不再有 "+ 添加用例" 按钮。)

// === "AI 帮我完善目标描述" 临时对话 state ===
// 不持久化,关 modal 就丢。chat 记 [{role:'user|assistant', content}],
// candidate 是 AI 给出的最新草稿(用户点 "采用此版本" 时整段替换 labForm.goalDescription)。
// llmKey 是这个面板自己的 (configId|apiModel),独立于父 modal 的 metaLlmKey,
// 用户可以临时换一个更适合"扩写文本"的 LLM 来辅助写目标描述。
const labGoalChat = reactive({
  visible: false,
  loading: false,
  error: "",
  history: [],
  candidate: "",
  inputBuffer: "",
  llmKey: ""           // "configId|apiModel";空 = "|" = default
});

function labOpenGoalChat() {
  labGoalChat.visible = true;
  labGoalChat.error = "";
  labGoalChat.candidate = labForm.goalDescription || "";
  labGoalChat.inputBuffer = "";
  // LLM 默认值优先级:
  //   1) 父 modal 已选的 Meta-LLM (labForm.metaLlm*)
  //   2) labLlmOptions 第一条(避免 default 走到 application-default 的兜底坏 token)
  //   3) "|"(让后端走 LlmProvider 的 default)
  if (!labGoalChat.llmKey) {
    if (labForm.metaLlmConfigId) {
      labGoalChat.llmKey = `${labForm.metaLlmConfigId}|${labForm.metaLlmModel || ""}`;
    } else if (labLlmOptions.value.length > 0) {
      labGoalChat.llmKey = labLlmOptions.value[0].key;
    } else {
      labGoalChat.llmKey = "|";
    }
  }
  if (!labGoalChat.history.length) {
    labGoalChat.history = [{
      role: "assistant",
      content: "我可以帮你把目标描述写得更具体、更完整。你可以告诉我:输入是什么 / 期望输出什么 / 用到哪些工具或数据。我会基于当前草稿改一版给你看,你不满意可以继续让我改。"
    }];
  }
}

function labCloseGoalChat(commitDraft) {
  if (commitDraft && labGoalChat.candidate && labGoalChat.candidate !== labForm.goalDescription) {
    labForm.goalDescription = labGoalChat.candidate;
  }
  labGoalChat.visible = false;
}

function labResetGoalChat() {
  labGoalChat.history = [];
  labGoalChat.candidate = labForm.goalDescription || "";
  labGoalChat.error = "";
  labGoalChat.inputBuffer = "";
}

async function labSendGoalChat() {
  const msg = labGoalChat.inputBuffer.trim();
  if (!msg || labGoalChat.loading) return;
  labGoalChat.loading = true;
  labGoalChat.error = "";
  // 即时插入用户气泡(失败时也保留,方便用户看自己问了啥)
  labGoalChat.history.push({ role: "user", content: msg });
  labGoalChat.inputBuffer = "";
  try {
    // history 给后端只发对话(不含本轮),draft 用当前 candidate(AI 上一轮给的或 form 里现有的)
    const historyForServer = labGoalChat.history
      .slice(0, -1)   // 排除刚 push 的本轮 user 消息
      .filter(t => t && t.role && t.content);
    const [chatConfigId, chatModel] = String(labGoalChat.llmKey || "|").split("|");
    const resp = await labRefineGoal({
      draft: labGoalChat.candidate || labForm.goalDescription || "",
      userMessage: msg,
      history: historyForServer,
      llmConfigId: chatConfigId || null,
      llmModel: chatModel || null
    });
    if (resp.refinedGoal) {
      labGoalChat.candidate = resp.refinedGoal;
    }
    labGoalChat.history.push({
      role: "assistant",
      content: resp.assistantMessage || "(AI 没有提供解释)"
    });
  } catch (err) {
    labGoalChat.error = err.message || "调用失败";
    // 失败的本轮 user 消息留下,加一条 assistant 错误提示
    labGoalChat.history.push({
      role: "assistant",
      content: "❌ 调用失败:" + (err.message || "未知错误") + " —— 检查 Meta-LLM 配置或网络后再试。"
    });
  } finally {
    labGoalChat.loading = false;
  }
}
// (V38: labRemoveTestCase / labToggleTestCasesAdvanced 已删除 —— 用户不写用例了。)

async function labSubmitCreate() {
  if (!labForm.constraintRules.trim()) {
    labState.error = "约束规则不能为空 —— 用自然语言写若干规则,AI 据此派生测试场景";
    return;
  }
  if (!labForm.title.trim() || !labForm.goalDescription.trim()) {
    labState.error = "任务标题、目标描述都不能为空";
    return;
  }
  try {
    labCreateModal.submitting = true;
    const created = await labCreateTask({
      title: labForm.title.trim(),
      goalDescription: labForm.goalDescription.trim(),
      constraintRules: labForm.constraintRules.trim(),
      referenceDocuments: labForm.referenceDocuments.trim() || null,
      maxIterations: labForm.maxIterations,
      mode: labForm.mode,
      hostAgentMode: labForm.hostAgentMode,
      hostAgentId: labForm.hostAgentMode === "EXISTING" ? (labForm.hostAgentId.trim() || null) : null,
      newHostAgentId: labForm.hostAgentMode !== "EXISTING" ? (labForm.newHostAgentId.trim() || null) : null,
      newHostAgentDisplayName: labForm.hostAgentMode !== "EXISTING" ? (labForm.newHostAgentDisplayName.trim() || null) : null,
      newHostAgentSeedPrompt: labForm.hostAgentMode === "NEW" ? (labForm.newHostAgentSeedPrompt.trim() || null) : null,
      newHostAgentScopeType: labForm.hostAgentMode !== "EXISTING" ? labForm.newHostAgentScopeType : null,
      newHostAgentScopeTenantId: labForm.hostAgentMode !== "EXISTING" ? (labForm.newHostAgentScopeTenantId.trim() || null) : null,
      cloneFromHostAgentId: labForm.hostAgentMode === "CLONE_FROM" ? (labForm.cloneFromHostAgentId.trim() || null) : null,
      targetSkillName: labForm.targetSkillName.trim() || null,
      newSkillName: labForm.newSkillName.trim() || null,
      cloneFromSkillName: labForm.cloneFromSkillName.trim() || null,
      targetScopeType: labForm.targetScopeType,
      targetScopeTenantId: labForm.targetScopeTenantId.trim() || null,
      allowAgentEvolution: !!labForm.allowAgentEvolution,
      metaLlmConfigId: labForm.metaLlmConfigId.trim() || null,
      metaLlmModel: labForm.metaLlmModel.trim() || null,
      testLlmConfigId: labForm.testLlmConfigId.trim() || null,
      testLlmModel: labForm.testLlmModel.trim() || null
    });
    // 不关 modal —— 状态面板嵌在 modal 底部,实时显示进度 + 允许停/重启/改写规则。
    labCreateModal.activeTaskId = created.id;
    labCreateModal.detail = null;
    labStartCreateModalPolling(created.id);
    await labReload();
  } catch (error) {
    labState.error = error.message || "create failed";
  } finally {
    labCreateModal.submitting = false;
  }
}

async function labCancel(taskId) {
  if (!confirm("确认取消该任务?当前轮迭代会被中断")) return;
  try {
    await labCancelTask(taskId);
    await labReloadDetail(taskId);
    await labReload();
  } catch (error) {
    labState.error = error.message;
  }
}

async function labRestart(taskId) {
  if (!confirm("重启会从第 1 轮重新设计 + 测试,已有迭代记录会保留")) return;
  try {
    await labRestartTask(taskId);
    await labReloadDetail(taskId);
    await labReload();
    labStartPolling(taskId);
  } catch (error) {
    labState.error = error.message;
  }
}

// 切到 /lab 时初次加载;路由参数变化时切换详情
watch(
  () => [activeMenu.value, route.params?.taskId],
  ([menu, taskId]) => {
    if (menu !== "lab") {
      labStopPolling();
      return;
    }
    labReload();
    if (taskId) labReloadDetail(taskId);
  },
  { immediate: false }
);

// ---------------------------------------------------------------------------------------
// 管理员会话审计 methods

// 一次性加载租户/应用/agent 三个数据源,给筛选下拉用。
// 错误不阻塞主流程 —— 任一失败下拉就退化成"全部"选项。
async function adminLoadCatalog() {
  if (adminSessionsState.catalogLoaded) return;
  const [tRes, cRes, aRes] = await Promise.allSettled([
    adminListTenants(),
    adminListOauthClients(),
    listAgentDefinitionsAdmin()
  ]);
  if (tRes.status === "fulfilled") adminSessionsState.tenants = tRes.value || [];
  if (cRes.status === "fulfilled") adminSessionsState.oauthClients = cRes.value || [];
  if (aRes.status === "fulfilled") adminSessionsState.agents = aRes.value || [];
  adminSessionsState.catalogLoaded = true;
}

// 应用下拉:租户筛选过的;租户选"全部"则展示全部应用
const adminAppsForSelect = computed(() => {
  const selectedTenant = adminSessionsFilter.tenantId;
  const all = adminSessionsState.oauthClients;
  if (!selectedTenant) return all;
  return all.filter(c => c.tenantId === selectedTenant);
});

// agent 下拉:租户 + 应用一起 filter;两者都"全部"则展示全部 agent
const adminAgentsForSelect = computed(() => {
  const selectedTenant = adminSessionsFilter.tenantId;
  const selectedApp = adminSessionsFilter.appId;
  return adminSessionsState.agents.filter(a => {
    if (selectedTenant && a.scopeTenantId && a.scopeTenantId !== selectedTenant) return false;
    if (selectedApp && a.appId && a.appId !== selectedApp) return false;
    return true;
  });
});

async function adminSessionsReload() {
  adminSessionsState.loading = true;
  adminSessionsState.error = "";
  try {
    await adminLoadCatalog();
    const days = parseInt(adminSessionsFilter.fromDays, 10) || 0;
    const fromIso = days > 0
      ? new Date(Date.now() - days * 86400000).toISOString()
      : null;
    const response = await adminListSessions({
      userId: adminSessionsFilter.userId.trim() || null,
      tenantId: adminSessionsFilter.tenantId.trim() || null,
      appId: adminSessionsFilter.appId.trim() || null,
      agentId: adminSessionsFilter.agentId.trim() || null,
      from: fromIso,
      page: adminSessionsState.page,
      size: adminSessionsState.size
    });
    adminSessionsState.rows = response.rows || [];
    adminSessionsState.totalSessions = response.totalSessions || 0;
    adminSessionsState.totalRuns = response.totalRuns || 0;
    adminSessionsState.totalMessages = response.totalMessages || 0;
    adminSessionsState.totalTokens = response.totalTokens || 0;
  } catch (error) {
    adminSessionsState.error = error.message || "load sessions failed";
  } finally {
    adminSessionsState.loading = false;
  }
}

function toggleAdminSessionSelection(sessionId, checked) {
  if (checked) adminSessionsSelection.add(sessionId);
  else adminSessionsSelection.delete(sessionId);
}
function toggleAdminSessionsAllSelected(checked) {
  // 全选只针对当前用户能管理的行,没权限的不会被选中(避免后端 403 一片)
  if (checked) manageableAdminSessions.value.forEach(r => adminSessionsSelection.add(r.sessionId));
  else manageableAdminSessions.value.forEach(r => adminSessionsSelection.delete(r.sessionId));
}
async function adminBatchDeleteSessions() {
  const ids = Array.from(adminSessionsSelection);
  if (!ids.length) return;
  if (!confirm(`确认批量删除 ${ids.length} 个会话?这会一并删除消息 / run / event log,不可恢复。`)) {
    return;
  }
  adminSessionsState.loading = true;
  let failed = 0;
  for (const id of ids) {
    try { await deleteSession(id); }
    catch (error) { failed++; console.warn("[admin.batch_delete_session_failed]", id, error); }
  }
  adminSessionsSelection.clear();
  await adminSessionsReload();
  if (failed > 0) adminSessionsState.error = `批量删除 ${ids.length - failed} 成功,${failed} 失败`;
}

// 删除会话(管理员):走已有的 deleteSession API
async function adminDeleteSession(sessionId, sessionTitle) {
  if (!confirm(`确认删除会话?\n\n标题:${sessionTitle || '(无标题)'}\nID:${sessionId}\n\n会话下的所有消息、run、event log 都会一并删除,不可恢复。`)) {
    return;
  }
  try {
    await deleteSession(sessionId);
    await adminSessionsReload();
  } catch (error) {
    adminSessionsState.error = error.message || "delete failed";
  }
}

// 列出 session 下所有 run,让管理员从中挑一个复盘
async function adminOpenSessionRuns(sessionId, sessionTitle) {
  adminRunsModal.open = true;
  adminRunsModal.sessionId = sessionId;
  adminRunsModal.sessionTitle = sessionTitle || "";
  adminRunsModal.loading = true;
  adminRunsModal.runs = [];
  adminRunsModal.error = "";
  try {
    adminRunsModal.runs = await adminListSessionRuns(sessionId);
  } catch (error) {
    adminRunsModal.error = error.message || "load runs failed";
  } finally {
    adminRunsModal.loading = false;
  }
}

function adminCloseSessionRuns() {
  adminRunsModal.open = false;
  adminRunsModal.runs = [];
  adminRunsModal.sessionId = "";
}

// 从 run 列表 modal 点击"复盘"时:关掉 list modal,弹 replay modal
async function adminOpenReplayFromList(runId) {
  adminCloseSessionRuns();
  await adminOpenReplay(runId);
}

async function adminOpenReplay(runId) {
  adminReplayState.open = true;
  adminReplayState.loading = true;
  adminReplayState.runId = runId;
  adminReplayState.data = null;
  adminReplayState.steps = [];
  adminReplayState.error = "";
  try {
    // 头部信息 + 行级事件并发拉。steps 是 V42 主路径,server 已经分好类;
    // 老 run 服务端会代为解析 event_log_json 拆成同样的行结构返回。
    const [header, steps] = await Promise.all([
      adminGetRunReplay(runId),
      adminGetRunSteps(runId, null).catch(() => [])
    ]);
    adminReplayState.data = header;
    adminReplayState.steps = Array.isArray(steps) ? steps : [];
  } catch (error) {
    adminReplayState.error = error.message || "load replay failed";
  } finally {
    adminReplayState.loading = false;
  }
}

function adminCloseReplay() {
  adminReplayState.open = false;
  adminReplayState.data = null;
  adminReplayState.steps = [];
  adminReplayState.runId = "";
}

// 事件源:优先 V42 行级 steps(后端 SQL 已分类、可过滤),fallback 才解析整段 JSON。
// 都归一到 {seq, ts, type, data, _category} 这一种形状,模板下游无需感知数据源。
const adminReplayEvents = computed(() => {
  if (Array.isArray(adminReplayState.steps) && adminReplayState.steps.length) {
    return adminReplayState.steps.map(s => {
      let data = {};
      if (s.payloadJson) {
        try { data = JSON.parse(s.payloadJson) || {}; } catch (_e) { data = {}; }
      }
      return {
        seq: s.seq,
        ts: s.ts,
        type: s.eventType,
        data,
        _category: s.category,
        _toolName: s.toolName || null,
        _summary: s.summary || ""
      };
    });
  }
  // 老路径兜底:服务端没拆行 + 没解析 JSON 的极端情况(理论上 service 已经代为解析,这里基本不会走)
  const raw = adminReplayState.data?.eventLogJson;
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr)) return [];
    return arr.map(evt => ({ ...evt, _category: classifyReplayEvent(evt) }));
  } catch (error) {
    return [];
  }
});

// 把单条事件归到一个分类。toolName 来自 data.content 里的 "tool=xxx" 片段,
// 或 data.toolName 字段。
function classifyReplayEvent(evt) {
  const type = evt?.type || "";
  const content = typeof evt?.data?.content === "string" ? evt.data.content : "";
  const toolName = extractToolName(content) || evt?.data?.toolName || "";
  // 1. AI 调用
  if (type === "PROMPT_SENT" || type === "LLM_RESPONSE" || type === "LLM_ATTEMPT_FAILED") return "ai";
  // 2. 工具相关 —— 进一步按 tool 名细分
  if (type === "TOOL_REQUESTED" || type === "TOOL_STARTED" || type === "TOOL_COMPLETED"
      || type === "TOOL_EXECUTED" || type === "TOOL_POLICY_DECISION" || type === "APPROVAL_REQUIRED") {
    if (toolName.startsWith("db.") || toolName === "database.query") return "db";
    if (toolName.startsWith("file.") || toolName === "workspace.path"
        || toolName === "doc.normalize" || toolName.startsWith("workspace.")) return "file";
    if (toolName.startsWith("artifact.")) return "artifact";
    return "tool-other";
  }
  // 3. 计划 / 阶段切换
  if (type === "PLAN_UPDATED") return "plan";
  // 4. RUN_STATUS:看 phase 标签里有没有 MODEL_THINKING / MODEL_RETRY,有就归 AI;否则 lifecycle
  if (type === "RUN_STATUS") {
    if (content.includes("phase=MODEL_THINKING") || content.includes("phase=MODEL_RETRY")) return "ai";
    return "lifecycle";
  }
  // 5. 运行生命周期 + token usage 总结
  if (type === "RUN_STARTED" || type === "RUN_COMPLETED" || type === "RUN_FAILED"
      || type === "RUN_CANCELLED" || type === "TOKEN_USAGE") return "lifecycle";
  return "tool-other";
}

function extractToolName(content) {
  if (!content) return null;
  const m = content.match(/tool=([\w.-]+)/);
  return m ? m[1] : null;
}

// 过滤后的事件
const adminReplayEventsFiltered = computed(() =>
  adminReplayEvents.value.filter(e => adminReplayState.activeCategories.has(e._category))
);

// 每个分类在当前 run 里的事件数(给 chip 显示徽章)
const adminReplayCategoryCounts = computed(() => {
  const counts = { ai: 0, db: 0, file: 0, artifact: 0, plan: 0, "tool-other": 0, lifecycle: 0 };
  for (const e of adminReplayEvents.value) {
    if (counts[e._category] !== undefined) counts[e._category]++;
  }
  return counts;
});

function adminToggleReplayCategory(cat) {
  if (adminReplayState.activeCategories.has(cat)) {
    adminReplayState.activeCategories.delete(cat);
  } else {
    adminReplayState.activeCategories.add(cat);
  }
}
function adminReplaySetCategories(cats) {
  adminReplayState.activeCategories = new Set(cats);
}
function replayCategoryLabel(cat) {
  return {
    ai: "AI",
    db: "数据库",
    file: "文件",
    artifact: "产物",
    plan: "Plan",
    "tool-other": "工具",
    lifecycle: "生命周期"
  }[cat] || cat;
}

// 切到会话审计页时初次加载;改 filter / page 时也 reload
watch(
  () => [activeMenu.value, adminSessionsState.page, adminSessionsState.size],
  ([menu]) => {
    if (menu === "admin-sessions") adminSessionsReload();
  },
  { immediate: false }
);

// 联动:切租户时清空当前 app/agent(如果它们不在新的过滤集合里);
// 切 app 时清空 agent(如果不再匹配)。
watch(
  () => adminSessionsFilter.tenantId,
  () => {
    if (adminSessionsFilter.appId &&
        !adminAppsForSelect.value.some(c => c.clientId === adminSessionsFilter.appId)) {
      adminSessionsFilter.appId = "";
    }
    if (adminSessionsFilter.agentId &&
        !adminAgentsForSelect.value.some(a => a.agentId === adminSessionsFilter.agentId)) {
      adminSessionsFilter.agentId = "";
    }
  }
);
watch(
  () => adminSessionsFilter.appId,
  () => {
    if (adminSessionsFilter.agentId &&
        !adminAgentsForSelect.value.some(a => a.agentId === adminSessionsFilter.agentId)) {
      adminSessionsFilter.agentId = "";
    }
  }
);

async function refreshCatalog() {
  loading.catalog = true;
  try {
    state.catalog.llms = await listAdminLlms();
    state.catalog.datasources = await listDatasources();
    state.catalog.agents = await listAgentDefinitionsAdmin();
    // Skills 拉<b>全量</b>(传空 agentId,后端 listSkillDefinitions(null) 走 findAll + scope 过滤)。
    // 这样 lab 创建的 skill 也能在 Skills 管理页看到 —— 之前按当前 chat agent 过滤会把 lab-host 的
    // skill 漏掉。Knowledge / Tools 暂时仍按当前 agent 过滤(它们的接口要求 agentId 必填)。
    state.catalog.skills = await listSkillDefinitions("");
    const agentId = state.resolvedAgentId || form.agentId;
    if (agentId) {
      const [knowledge, tools] = await Promise.all([
        listKnowledgeEntries(agentId),
        listToolDefinitions(agentId)
      ]);
      state.catalog.knowledge = knowledge;
      state.catalog.tools = tools;
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

// 最近会话搜索 + 真分页:每次都调 /api/sessions/paged 后端接口。
// keyword + page + size 都参与过滤,后端按 SessionVisibility 过滤后返 total + rows。
const recentSessionsFilter = reactive({ keyword: "" });
const recentSessionsPage = reactive({ page: 0, size: 15 });
const recentSessionsTotal = ref(0);
const recentSessionsTotalPages = computed(() =>
  Math.max(1, Math.ceil(recentSessionsTotal.value / recentSessionsPage.size))
);

// 搜索关键字 / 翻页 / 改 size 时都触发后端 reload(简单 debounce 避免每键打字一次请求)。
let recentSearchTimer = null;
watch(() => recentSessionsFilter.keyword, () => {
  recentSessionsPage.page = 0;
  if (recentSearchTimer) clearTimeout(recentSearchTimer);
  recentSearchTimer = setTimeout(() => refreshRecentSessions(), 250);
});
watch(() => [recentSessionsPage.page, recentSessionsPage.size], () => refreshRecentSessions());

// 顶部工具栏的"刷新下拉"按钮:用户主动触发,绕过 localStorage 缓存重拉 agents+llms。
// 失败的请求保持现有 .value,成功的覆盖并写回缓存。loadingDropdownRefresh 让按钮在
// 请求过程中显示 loading 态,避免用户连点抖。
const dropdownRefreshing = ref(false);
async function refreshDropdownLists() {
  if (dropdownRefreshing.value) return;
  dropdownRefreshing.value = true;
  try {
    const [a, l] = await Promise.allSettled([listAgents(), listLlms()]);
    if (a.status === "fulfilled") {
      agents.value = a.value;
      writeDropdownCache("agents", a.value);
    } else {
      console.warn("[dropdown.refresh.agents_failed]", a.reason);
    }
    if (l.status === "fulfilled") {
      llms.value = l.value;
      writeDropdownCache("llms", l.value);
    } else {
      console.warn("[dropdown.refresh.llms_failed]", l.reason);
    }
  } finally {
    dropdownRefreshing.value = false;
  }
}

// embed 浮动面板里"跳转到主控台当前会话"按钮:embed 浮动面板 UI 极简,
// 用户经常想跳到完整 web-console 看完整 timeline / artifacts / approvals。
// 这个按钮只在 embed 模式下出现(非 embed 用户已经在主控台,没必要跳)。
//
// 凭证传递:embed iframe 用的是 OAuth access_token(Bearer),主控台原本靠 cookie
// AGENT_TOKEN 登录 —— 不一定有,即使有也是另一个用户的。所以把当前的 OAuth token
// 作为 URL query "token=" 带过去,主控台 onMounted 检测后调 setEmbedAccessToken
// 让所有请求都带 Bearer,身份就跟 iframe 内一致。token 用完后立刻从 URL 清掉,
// 防 history/书签泄漏。
function openCurrentSessionInConsole() {
  const sessionId = form.sessionId;
  const agentId = form.agentId || state.resolvedAgentId || "";
  const token = getEmbedAccessToken();
  // sessionId 走 path 参数 —— router 配的就是 /chat/:sessionId?,query string 形式
  // 进来 route.params.sessionId 取不到,主控台会停在新会话空白态。
  // agentId / token 仍走 query。
  const tail = new URLSearchParams();
  if (agentId) tail.set("agentId", agentId);
  if (token) tail.set("token", token);
  const query = tail.toString();
  const sessionPath = sessionId ? `/chat/${encodeURIComponent(sessionId)}` : `/chat`;
  const href = `${window.location.origin}${sessionPath}${query ? "?" + query : ""}`;
  // embed 模式新窗口打开,不替换 iframe 自身。非 embed 不显示按钮,这个分支不会走到。
  window.open(href, "_blank", "noopener,noreferrer");
}
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
  status: "ACTIVE",
  // displayEnabled = UI 显示开关,跟 status(功能开关)解耦。
  // status=ACTIVE + displayEnabled=true → xmap 端 AI 按钮显示 + 功能可用
  // status=ACTIVE + displayEnabled=false → AI 按钮藏(__forceShowAi(true) 可强显),功能可用
  // status=DISABLED + 任意 displayEnabled → AI 按钮藏,功能禁用(token 颁发都被拒)
  displayEnabled: true,
  ownerUserId: "", description: ""
});

function openCreateUserModal() {
  Object.assign(userForm, { username: "", email: "", displayName: "", preferredTenantId: "" });
  openAdminModal("user");
}

// 编辑已有用户:displayName / email / status / preferredTenantId(仅 sysadmin 改租户)。
// 跟"新建"独立一个 modal type,因为编辑里 username 不可改 + 多了 status/原值预填。
const userEditForm = reactive({
  id: "",
  username: "",
  displayName: "",
  email: "",
  status: "ACTIVE",
  preferredTenantId: "",
  passwordMustChange: false
});
function openEditUserModal(user) {
  if (!user) return;
  Object.assign(userEditForm, {
    id: user.id,
    username: user.username || "",
    displayName: user.displayName || "",
    email: user.email || "",
    status: user.status || "ACTIVE",
    preferredTenantId: user.preferredTenantId || "",
    passwordMustChange: !!user.passwordMustChange
  });
  openAdminModal("userEdit");
}
async function submitUserEdit() {
  if (!userEditForm.id) return;
  try {
    await adminUpdateUser(userEditForm.id, {
      displayName: userEditForm.displayName.trim() || null,
      email: userEditForm.email.trim() || null,
      status: userEditForm.status || null,
      preferredTenantId: userEditForm.preferredTenantId.trim() || null
    });
    closeAdminModal();
    await refreshAdminCatalog();
  } catch (error) {
    state.error = error.message;
  }
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
    status: "ACTIVE", displayEnabled: true, ownerUserId: "", description: ""
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
        status: "ACTIVE", displayEnabled: true, ownerUserId: "", description: ""
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
        status: "ACTIVE", displayEnabled: true, ownerUserId: "", description: ""
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
    status: item.status,
    displayEnabled: item.displayEnabled !== false,  // 老数据没字段时默认 true(向后兼容)
    ownerUserId: item.ownerUserId || "",
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
  // embed 模式下不跳登录(父 SDK 用 OAuth token 换的身份,不能跳走父页面)。
  if (authUser.anonymous && route.name !== "login" && !embedMode.value) {
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
    loginForm.password = "";
    // 登录成功直接 **full page reload** 跳 /chat。原因:
    //  1. token 过期重登场景下,旧 Vue 实例还残留着 currentRunId / pending message / 各种 ref,
    //     就算 initAfterAuth 把 globalSocket 重连了,旧的 state.events / sidebar 缓存还在,
    //     表现就是"WebSocket 连上但 UI 看起来卡死,新消息也不显示"
    //  2. 整页 reload 走一遍 onMounted → whoami → initAfterAuth → ensureGlobalEventSocket
    //     的全套初始化,带新 cookie 重新拉一切,跟首次登录完全一致
    //  3. 副作用 = 用户登录界面里输的 username/password 在表单状态里清空(已经发出去登成功了
    //     不需要保留)、新 token 是 HttpOnly cookie 浏览器自动带,reload 后照样有
    window.location.href = "/chat";
  } catch (error) {
    loginForm.error = error?.message || String(error);
  } finally {
    loginForm.submitting = false;
  }
}

async function submitLogout() {
  await apiLogout();
  // 关掉常驻 WS:不属于这个用户的事件流不该再灌进来,而且后端的 AGENT_TOKEN cookie 也会被 logout
  // 清掉,继续 reconnect 也只会被 /ws/events 拒绝。
  closeGlobalEventSocket();
  // 登出清掉所有下拉缓存,避免下个用户首屏看到上一个账号能看到的 LLM/agent 列表。
  // 同时清掉 lastSession,下个账号不要被自动加载到上一个用户的会话上。
  clearDropdownCache();
  clearLastSessionId();
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
    passwordChangeForm.oldPassword = "";
    passwordChangeForm.newPassword = "";
    // 跟 submitLogin 同样思路:全页 reload 进 /chat。后端改完密 cookie 已经写好,
    // reload 后走 onMounted → whoami → initAfterAuth 全套初始化,状态干净。
    window.location.href = "/chat";
  } catch (error) {
    passwordChangeForm.error = error?.message || String(error);
  } finally {
    passwordChangeForm.submitting = false;
  }
}

// embed 模式下"关闭"按钮点击 —— 通过 postMessage 通知父 SDK 收起浮动容器。
// 不能在模板里直接写 window.parent.postMessage(),Vue template scope 看不到 window。
function hideEmbedPanel() {
  if (typeof window !== "undefined" && window.parent) {
    window.parent.postMessage({ type: "jc.embed.hide" }, "*");
  }
}

// embed 模式 host.invoke 桥:LLM 调 host.invoke 工具 → 后端 ExternalBridgeGateway 经 /ws/bridge
// 把 invoke 推给我们 → 我们 postMessage 给宿主 → 宿主执行后回 jc.embed.invoke.result → 我们送回 socket。
function ensureEmbedBridge(sessionId) {
  console.log("[App.bridge] ensureEmbedBridge called", { sessionId, embedMode: embedMode.value });
  if (!embedMode.value || !sessionId) return;
  if (embedBridgeSocket && embedBridgeSocket.readyState === WebSocket.OPEN) {
    console.log("[App.bridge] already open, skip");
    return;
  }
  if (embedBridgeSocket && embedBridgeSocket.readyState === WebSocket.CONNECTING) {
    console.log("[App.bridge] already connecting, skip");
    return;
  }
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const params = new URLSearchParams({ sessionId });
  const tok = getEmbedAccessToken();
  if (tok) params.set("access_token", tok);
  const url = `${protocol}//${window.location.host}/ws/bridge?${params.toString()}`;
  console.log("[App.bridge] connecting", url);
  const socket = new WebSocket(url);
  embedBridgeSocket = socket;
  socket.onopen = () => console.log("[App.bridge] OPEN", sessionId);
  socket.onmessage = (event) => {
    console.log("[App.bridge] raw msg from server", event.data);
    let payload = null;
    try { payload = JSON.parse(event.data); } catch (e) {
      console.warn("[App.bridge] non-JSON message dropped");
      return;
    }
    if (payload?.type !== "invoke") {
      console.log("[App.bridge] non-invoke msg dropped", payload?.type);
      return;
    }
    console.log("[App.bridge] forwarding invoke to parent SDK", payload);
    if (window.parent) {
      window.parent.postMessage({
        type: "jc.embed.invoke",
        // 把当前 sessionId 一并带过去 —— SDK 后续通过 HTTP /api/bridge/invoke-result
        // 回写时要拿来对账。这条 invoke 是从 sessionId 这条 bridge socket 里来的,
        // 所以这里的 sessionId 一定是 LLM 当前期待的会话。
        sessionId: sessionId,
        method: payload.method,
        payload: payload.payload,
        requestId: payload.requestId,
        timeoutMs: payload.timeoutMs
      }, "*");
    }
  };
  socket.onclose = (e) => {
    console.log("[App.bridge] CLOSE", e.code, e.reason);
    embedBridgeSocket = null;
  };
  socket.onerror = (error) => {
    console.warn("[App.bridge] ERROR", error);
  };
}

// embed 模式:监听父 SDK 投来的 jc.embed.init,拿 token + agent/userId 等后正式 bootstrap。
// 后续也接 jc.embed.send(从父页面主动发消息)。host.invoke / methods 列表先记下,留给后面接桥。
// hostInvoke:iframe 内部代码主动调用宿主(xmap-ol-front 注册到 window.AI 的)方法。
// 跟 LLM 那条 host.invoke 工具调用是两条独立路径 —— 这里 iframe 自己发起、自己拿 result,
// 不经服务端 bridge。requestId 用来在 iframe 这一侧对账多个并发调用。
const hostInvokePending = new Map();
function hostInvoke(method, payload) {
  return new Promise((resolve, reject) => {
    if (!embedMode.value) {
      reject(new Error("hostInvoke 仅在 embed 模式下可用"));
      return;
    }
    if (!window.parent) {
      reject(new Error("没有 window.parent,无法 host invoke"));
      return;
    }
    const requestId = `host-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const timer = setTimeout(() => {
      if (hostInvokePending.has(requestId)) {
        hostInvokePending.delete(requestId);
        reject(new Error(`hostInvoke timeout: ${method}`));
      }
    }, 120000); // 给 2 分钟,绘制类操作要等用户在地图上点
    hostInvokePending.set(requestId, { resolve, reject, timer });
    window.parent.postMessage({
      type: "jc.embed.host_invoke",
      requestId,
      method,
      payload: payload || {}
    }, "*");
  });
}

function setupEmbedListener() {
  window.addEventListener("message", async (event) => {
    const data = event.data;
    if (!data || typeof data !== "object" || !data.type) return;
    if (data.type === "jc.embed.host_invoke.result") {
      // hostInvoke 的回应,按 requestId 找到 pending 的 Promise 解开
      const entry = hostInvokePending.get(data.requestId);
      if (!entry) return;
      hostInvokePending.delete(data.requestId);
      clearTimeout(entry.timer);
      if (data.success) entry.resolve(data.result);
      else entry.reject(new Error(data.error || "host invoke failed"));
      return;
    }
    if (data.type === "jc.embed.init") {
      const payload = data.payload || {};
      const token = payload.accessToken || "";
      if (!token) {
        console.warn("[App] embed init received without accessToken; cannot bootstrap");
        return;
      }
      setEmbedAccessToken(token);
      if (payload.agentId) {
        form.agentId = payload.agentId;
        state.resolvedAgentId = payload.agentId;
      }
      if (payload.userId) {
        form.userId = payload.userId;
      }
      // init 时 SDK 已经把第一批方法名一起带过来(xmap-ol-front 注册了 34 个),先存上
      if (Array.isArray(payload.methods)) {
        embedHostMethods.value = payload.methods.map((m) => String(m || "").trim()).filter(Boolean);
      }
      try {
        await refreshWhoami();
        if (authUser.anonymous) {
          console.warn("[App] embed token rejected by whoami, see backend log");
          return;
        }
        await bootstrap();
        await refreshMemories();
        await refreshCatalog();
        await refreshActiveRuns();
        embedReady.value = true;
        // 通知父页面我们准备好了 —— 带 sessionId,父 SDK 拿它后面 HTTP 回写 invoke_result 要用
        window.parent?.postMessage({ type: "jc.embed.ready", initialized: true, sessionId: form.sessionId || "" }, "*");
      } catch (error) {
        console.error("[App] embed bootstrap failed:", error);
        window.parent?.postMessage({ type: "jc.embed.error", payload: { message: String(error?.message || error) } }, "*");
      }
      return;
    }
    if (data.type === "jc.embed.send") {
      const text = data.payload?.message || "";
      if (text) {
        form.message = text;
        await runChat();
      }
      return;
    }
    if (data.type === "jc.embed.registerMethods") {
      // 宿主每次扩充方法都会再投一次完整列表,直接覆盖
      const list = data.payload?.methods;
      embedHostMethods.value = Array.isArray(list)
        ? list.map((m) => String(m || "").trim()).filter(Boolean)
        : [];
      console.log("[App] embed host methods registered:", embedHostMethods.value.length);
      return;
    }
    if (data.type === "jc.embed.parent_visible") {
      // 父 SDK 把 iframe 重新显示出来时投这条 —— 利用机会把 LLM/agents 列表
      // 刷新一遍。宿主在 iframe 隐藏期间可能新建/禁用过 LLM 配置,用户重新打开
      // 应该看到最新视图,而不是上次离开时的快照。成功的请求顺手写一遍 localStorage
      // 缓存,下次再起 iframe 即便瞬时网络抖也不会下拉空。
      if (authUser.loaded && !authUser.anonymous) {
        try {
          const [a, l] = await Promise.allSettled([listAgents(), listLlms()]);
          if (a.status === "fulfilled") {
            agents.value = a.value;
            writeDropdownCache("agents", a.value);
          }
          if (l.status === "fulfilled") {
            llms.value = l.value;
            writeDropdownCache("llms", l.value);
          }
        } catch (error) {
          console.warn("[embed.parent_visible.refresh_failed]", error?.message || error);
        }
      }
      return;
    }
  });
  // 第一拉手:告诉父 SDK 我已经挂载好,等 init
  window.parent?.postMessage({ type: "jc.embed.ready", initialized: false, sessionId: "" }, "*");
}

// 任何 fetch 收到 401 都走这里 —— 立刻清掉认证态,跳到 /login。
// 这条路径覆盖"cookie 过期"/"后端被重启 JWT secret 没变但 token 过期"等场景。
function handleUnauthorized() {
  if (authUser.anonymous && route.name === "login") return;
  // 鉴权失效:关掉常驻 WS。让它继续 reconnect 没意义,后端会一直拒。
  closeGlobalEventSocket();
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

// 登录成功后(走 onMounted 直进或走 submitLogin)都要跑这套初始化:
// bootstrap (agents+llms) → 会话/审批/记忆/目录/活跃 run → 起 4s 轮询 → 滚动到底。
// idempotent: 重复调用不会重复设 setInterval、不会叠加 click listener。
let initAfterAuthDone = false;
async function initAfterAuth() {
  // 登录成功的第一件事:把全局事件 WebSocket 拉起来。它跟登录态等长,后端推过来的所有
  // 用户有权看的 session 事件都从这条 socket 走。 idempotent — 已经开了的话 ensure 直接 no-op。
  ensureGlobalEventSocket();
  await bootstrap();
  // 没显式带 sessionId 进来(URL query 没传)时,看 localStorage 里有没有上次访问的会话。
  // 有就自动恢复 —— 用户重开 iframe / 刷主控台不用每次从空白起步。会话不存在(被删了
  // 等)时 refreshSession 会失败,catch 一下清掉缓存避免下次仍然尝试。
  if (!form.sessionId) {
    const cachedSession = readLastSessionId();
    if (cachedSession?.sessionId) {
      form.sessionId = cachedSession.sessionId;
      if (cachedSession.agentId && !form.agentId) {
        form.agentId = cachedSession.agentId;
      }
      searchForm.sessionId = cachedSession.sessionId;
    }
  }
  if (form.sessionId) {
    try {
      await refreshSession();
      await refreshApprovals();
      await recoverActiveRunForSession();
    } catch (error) {
      console.warn("[initAfterAuth.lastSession_load_failed]", error?.message || error);
      // 缓存的 sessionId 已不可用(被删除/跨租户等),清掉防止下次再尝试,form.sessionId 也复原。
      clearLastSessionId();
      form.sessionId = "";
    }
  }
  // 没有 sessionId(新登录 / 没缓存)→ 在 chat 菜单下默认进会话列表页,后台拉一份数据
  if (activeMenu.value === "chat" && !form.sessionId) {
    await refreshSessionsList(0).catch(error =>
      console.warn("[initAfterAuth.sessions_list_load_failed]", error?.message || error));
  }
  await refreshMemories();
  await refreshCatalog();
  await refreshActiveRuns();
  if (!activeRunsTimer) {
    activeRunsTimer = setInterval(refreshActiveRuns, 4000);
  }
  if (!initAfterAuthDone) {
    window.addEventListener("click", closeContextMenu);
    initAfterAuthDone = true;
  }
  syncComposerEditor();
  await nextTick();
  scrollConversationToBottom({ force: true });
}

onMounted(async () => {
  window.addEventListener("auth:unauthorized", handleUnauthorized);
  // embed 模式:URL 带 ?embed=1 → 等父 SDK postMessage 的 jc.embed.init 把 token 灌过来,
  // 之后才 refreshWhoami + bootstrap;路由强制留在 /chat,不参与正常的登录流。
  if (embedMode.value) {
    setupEmbedListener();
    syncStateFromRoute();
    if (route.name !== "chat") {
      router.replace({ name: "chat" });
    }
    return;
  }
  // 非 embed 入口:URL 上若带 ?token=xxx,说明是从 iframe 浮动面板"跳转到主控台"按钮
  // 投过来的,token 是 OAuth Bearer。
  //
  // 关键:之前直接 setEmbedAccessToken 把 token 塞进 JS 内存变量,刷新就丢 → 跳回登录页。
  // 现在改成调 /api/auth/exchange-oauth 让后端用同一个 user/tenant/app 签一个 session JWT
  // 写 AGENT_TOKEN cookie。cookie 是 HttpOnly + maxAge,刷新页面后浏览器自动带回,后端
  // JwtAuthWebFilter 照常解析,身份跨刷新持续有效。
  //
  // 兜底:exchange 失败(网络 / token 过期 / 后端没起)时退回到旧的 Bearer 内存模式,
  // 至少当前会话能用,只是刷新就丢 —— 比没法登录强。
  const incomingToken = typeof route.query.token === "string" ? route.query.token : "";
  if (incomingToken) {
    try {
      await exchangeOauthToSession(incomingToken);
      // cookie 已写,后续所有 authedFetch 走同源 cookie 路径,不再需要 embedAccessToken。
    } catch (error) {
      console.warn("[onMounted.exchange_oauth_failed]", error?.message || error);
      // 兜底用旧路径,Bearer 模式撑住当前会话,刷新仍会丢登录态。
      setEmbedAccessToken(incomingToken);
    }
    const cleanQuery = { ...route.query };
    delete cleanQuery.token;
    await router.replace({ name: "chat", query: cleanQuery });
  }
  syncStateFromRoute();
  await refreshWhoami();
  // 匿名 + 强制登录场景下,refreshWhoami 已经把路由 replace 到 /login 了;
  // 这里直接 return,不要再启动 bootstrap / 轮询,免得在登录页就把后端打 401 一遍。
  // 登录页用户登录成功后由 submitLogin 显式调 initAfterAuth。
  if (authUser.anonymous) {
    return;
  }
  await initAfterAuth();
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
  // Liveness 兜底:WebSocket 可能静默死(对端 RST 丢失 / 路由黑洞,onclose 没触发),
  // 导致 chatRunBusy 永久卡在 true(currentStream 没清 + state.run.status 还是 MODEL_RUNNING)。
  // 这里每 4s 醒一下,如果本地以为还在跑的 run 已经不在后端活跃列表里,直接拉一次 run 详情
  // 校正状态,把 chatRunBusy 关掉。
  try {
    await reconcileBusyAgainstActiveRuns();
  } catch (error) {
    console.warn("[chat.reconcile_busy_failed]", error?.message || error);
  }
}

async function reconcileBusyAgainstActiveRuns() {
  const localRunId = state.currentRunId || state.run?.runId;
  if (!localRunId) return;
  // 我们以为它在跑(本地 state.run.status active) —— 但活跃列表里没有这个 runId
  const stillActiveOnServer = (activeRuns.value || []).some(r => r?.runId === localRunId);
  const localThinksBusy = isActiveRunStatus(state.run?.status);
  if (!localThinksBusy || stillActiveOnServer) {
    return;
  }
  // 服务端已经不认这个 run 还在跑了 → 强行校正:拉 DB 真值并刷会话
  try {
    const run = await getRun(localRunId);
    if (run && !isActiveRunStatus(run.status)) {
      console.warn("[chat.reconcile.run_terminal_but_local_busy]", { runId: localRunId, serverStatus: run.status });
      state.run = run;
      loading.sending = false;
      await refreshSession();
    }
  } catch (error) {
    // getRun 失败(可能后端短暂挂)→ 下一轮 4s 再试,不强行清状态
    console.warn("[chat.reconcile.getRun_failed]", error?.message || error);
  }
}

async function terminateRun(runId) {
  if (!runId) return;
  if (!window.confirm(`终止当前 run (${runId.slice(0, 8)})?`)) return;
  // UI 立即生效:把当前 run 标 CANCELLED;后端 cancelRun 在后台跑,失败不回滚 UI ——
  // "点了就停"才是合理体验。事件流由 globalSocket 自然给我们最终终态。
  if (state.currentRunId === runId) {
    state.liveAssistant.content = state.liveAssistant.content || "";
    if (state.run && state.run.runId === runId) {
      state.run = { ...state.run, status: "CANCELLED", detail: "cancelled by user" };
    }
  }
  // 后端 cancel 异步打,即使失败也不回滚 UI —— 用户点了就该停,服务端走自己的清理路径
  try {
    const result = await cancelRun(runId);
    console.info("run.cancel", runId, result?.message);
  } catch (error) {
    console.warn("[run.cancel.failed]", runId, error?.message || error);
  }
  await refreshActiveRuns();
  await refreshSession();
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
    // 进入 chat 菜单但没指定 sessionId → 展示会话列表(列表组件 v-if 已经触发了模板切换,
    // 这里负责拉数据)。比起每次切到列表都重新拉一次,我们只在"从别处进 chat 菜单/从 chat-session
    // 切回 list"时拉。
    if (activeMenu.value === "chat" && !form.sessionId) {
      if (previousMenu !== "chat" || previousSessionId) {
        await refreshSessionsList(0);
      }
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

// form.sessionId 一变就写 localStorage(非空时),用户切会话/发新消息后下次重开会自动落回此会话。
// 空值不清缓存 —— "用户主动新建会话" form.sessionId 暂时为空,但缓存里仍是上次的,下次自然回到那条;
// 显式登出时由 clearLastSessionId 才清。
watch(
  () => form.sessionId,
  (next) => {
    if (next) writeLastSessionId(next, form.agentId || state.resolvedAgentId || "");
  }
);

// 切到 Token 用量页或调整 range/groupBy 时自动 reload。range/groupBy 同步触发,
// 避免用户每改一次还要手动点刷新。
watch(
  () => [activeMenu.value, usageState.range, usageState.groupBy],
  ([menu]) => {
    if (menu === "usage") reloadUsage();
  },
  { immediate: false }
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

// ============== 会话列表页(activeMenu==='chat' && !form.sessionId) =========================
// 进入"会话"菜单时不直接打开旧 sessionId 的 chat 视图,而是先展示一个权限范围内的会话列表 +
// 过滤器。点 row / 进入按钮才把 form.sessionId 设上跳进 chat 视图。
const sessionsListState = reactive({
  rows: [],
  total: 0,
  page: 0,
  size: 20,
  loading: false,
  error: "",
  keyword: "",
  userId: "",
  tenantId: "",
  appId: "",
  agentId: "",
  runStatus: "all"
});

// 过滤下拉的数据源:租户列表 + 应用列表。后端按 SessionVisibility 自动收窄,所以
// tenantsCanFilter=false 时前端把租户下拉禁用(非 sysadmin)。
// 应用下拉永远开放(因为后端已经按租户收窄过 rows)。
const filterOptions = reactive({
  tenants: [],
  tenantsCanFilter: false,
  apps: [],
  loaded: false,
  loading: false
});

async function loadFilterOptions(force = false) {
  if (filterOptions.loaded && !force) return;
  if (filterOptions.loading) return;
  filterOptions.loading = true;
  try {
    const [t, a] = await Promise.all([
      listFilterableTenants(),
      listFilterableApps()
    ]);
    filterOptions.tenants = t?.rows || [];
    filterOptions.tenantsCanFilter = !!t?.canFilter;
    filterOptions.apps = a?.rows || [];
    filterOptions.loaded = true;
  } catch (error) {
    console.warn("[filter-options.load_failed]", error?.message || error);
  } finally {
    filterOptions.loading = false;
  }
}

async function refreshSessionsList(page = sessionsListState.page) {
  sessionsListState.loading = true;
  sessionsListState.error = "";
  // 顺手把过滤下拉数据拉一次 —— 进列表页时第一次需要,后续 force=false 直接复用缓存
  loadFilterOptions().catch(() => { /* 已在 loadFilterOptions 里 warn 过 */ });
  try {
    const data = await listSessionsPaged({
      page,
      size: sessionsListState.size,
      keyword: sessionsListState.keyword,
      userId: sessionsListState.userId,
      tenantId: sessionsListState.tenantId,
      appId: sessionsListState.appId,
      agentId: sessionsListState.agentId,
      runStatus: sessionsListState.runStatus
    });
    sessionsListState.rows = data.rows || [];
    sessionsListState.total = data.total || 0;
    sessionsListState.page = data.page ?? page;
    sessionsListState.size = data.size ?? sessionsListState.size;
  } catch (error) {
    sessionsListState.error = error?.message || String(error);
    sessionsListState.rows = [];
    sessionsListState.total = 0;
  } finally {
    sessionsListState.loading = false;
  }
}

function resetSessionsListFilters() {
  sessionsListState.keyword = "";
  sessionsListState.userId = "";
  sessionsListState.tenantId = "";
  sessionsListState.appId = "";
  sessionsListState.agentId = "";
  sessionsListState.runStatus = "all";
  sessionsListState.page = 0;
  refreshSessionsList(0);
}

function goSessionsListPage(page) {
  refreshSessionsList(page);
}

// 点 row / "进入"按钮 → 真正切到 chat-session 视图。
async function enterSessionFromList(sessionId, agentId) {
  if (!sessionId) return;
  resetSessionScopedState();
  form.sessionId = sessionId;
  searchForm.sessionId = sessionId;
  if (agentId) {
    form.agentId = agentId;
    state.resolvedAgentId = agentId;
    searchForm.agentId = agentId;
  }
  navigateToMenu("chat", { sessionId, agentId });
  // 进入后立刻拉会话详情 + 接 active run 历史 snapshot,globalSocket 自动推 live 事件
  try {
    await refreshSession();
  } catch (error) {
    state.error = error?.message || String(error);
  }
}

// 在列表页点"新建会话":本质就是 createNewSession,然后路由不变(因为 sessionId 仍空,template
// 还是会显示列表 —— 但 composer 已经清空。用户在 composer 里发第一条消息时 sendChat 会建出新
// session,系统跳到 chat-session 视图。
function openNewSessionFromList() {
  createNewSession();
  // 没立刻进 chat,先让用户在沉浸式输入框里输内容:不,实际上 createNewSession 已经把 form.sessionId
  // 置空了,template 还是会显示列表页。我们要给"用户期望立刻看到一个空白对话框"的体验 —— 走
  // sendChat 路径太重(要先输内容)。最简单:直接构造一个临时 sessionId 进 chat 视图。
  const tempId = generateClientSessionId();
  enterSessionFromList(tempId, form.agentId || state.resolvedAgentId || "");
}

function generateClientSessionId() {
  // 前端预生成 UUID,sendChat 时会用这个 id 真的注册到后端。规则跟后端 UUID.randomUUID() 兼容。
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  // 兜底:时间戳 + 随机数,够用。
  return "fe-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 10);
}

// 在 chat-session 视图里点"← 返回会话列表":真的离开 chat 状态。
// 后续 globalSocket 收到当前 session 的事件 dispatchGlobalEvent 会因为 form.sessionId 空而静默丢弃,
// 不污染列表页。run 还在跑(POST send 已经 fire-and-forget),回到列表会看到它在"运行中"过滤里。
function leaveSessionToList() {
  resetSessionScopedState();
  form.sessionId = "";
  searchForm.sessionId = "";
  navigateToMenu("chat", { sessionId: undefined });
  refreshSessionsList(sessionsListState.page);
}

function formatTimestamp(ts) {
  if (!ts) return "";
  try {
    const d = new Date(ts);
    if (isNaN(d.getTime())) return String(ts);
    return d.toLocaleString();
  } catch (_) {
    return String(ts);
  }
}

function shortText(text, max = 20) {
  if (text == null) return "";
  const s = String(text);
  return s.length > max ? s.slice(0, max - 1) + "…" : s;
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
    defaultConfig: false,
    scope: { ...DEFAULT_SCOPE }
  });
  llmModelRows.value = [];
}

function resetKnowledgeForm() {
  Object.assign(knowledgeForm, { id: "", title: "", content: "", contentType: "markdown", source: "database", tagsJson: "[]", enabled: true, scope: { ...DEFAULT_SCOPE } });
}

function resetToolForm() {
  Object.assign(toolForm, { id: "", toolName: "", displayName: "", description: "", schemaJson: "{}", toolType: "builtin", configJson: "{}", enabled: true, approvalRequired: false, scope: { ...DEFAULT_SCOPE } });
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
    enabled: true,
    scope: { ...DEFAULT_SCOPE }
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
  Object.assign(llmForm, item, { modelMappingJson, scope: readScopeFromView(item) });
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
      modelMappingJson: normalizedMappingJson,
      ...flattenScopeForRequest(llmForm.scope)
    });
    resetLlmForm();
    closeCatalogModal();
    llms.value = await listLlms();
    writeDropdownCache("llms", llms.value);
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
    writeDropdownCache("llms", llms.value);
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
  Object.assign(knowledgeForm, item, { scope: readScopeFromView(item) });
  openCatalogModal("knowledge", "edit");
}

async function submitKnowledge() {
  try {
    await saveKnowledgeEntry({
      ...knowledgeForm,
      agentId: state.resolvedAgentId || form.agentId,
      ...flattenScopeForRequest(knowledgeForm.scope)
    });
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
  Object.assign(toolForm, item, { scope: readScopeFromView(item) });
  openCatalogModal("tools", "edit");
}

async function submitTool() {
  try {
    await saveToolDefinition({
      ...toolForm,
      agentId: state.resolvedAgentId || form.agentId,
      ...flattenScopeForRequest(toolForm.scope)
    });
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
    enabled: item.enabled !== false,
    scope: readScopeFromView(item)
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
      agentId: agentIds[0] || null,
      ...flattenScopeForRequest(skillForm.scope)
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
    enabled: true,
    scope: { ...DEFAULT_SCOPE }
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
    enabled: item.enabled,
    scope: readScopeFromView(item)
  });
  openCatalogModal("datasources", "edit");
}

async function submitDatasource() {
  try {
    await saveDatasource({
      ...datasourceForm,
      ...flattenScopeForRequest(datasourceForm.scope)
    });
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
  // 主时间线只放需要用户行动 / 关注的关键事件:
  //   - APPROVAL_REQUIRED:需要点同意/拒绝
  //   - RUN_FAILED:错误现场,要看 detail
  // RUN_STATUS / TOOL_REQUESTED / TOOL_STARTED / TOOL_COMPLETED 全部不展示为
  // 独立卡片 —— 它们是过程态:RUN_STATUS 由顶部 live-status 横幅+流式 live bubble
  // 表达;工具调用由 transcript 里的 tool message (含渲染负载)永久承载。再单独
  // 冒一张"执行步骤""工具已选定""工具完成"卡片就是冗余噪音。
  return type === "APPROVAL_REQUIRED" || type === "RUN_FAILED";
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

  <div v-else class="workspace-shell" :class="{ 'embed-mode': embedMode }">
    <aside v-if="!embedMode" class="left-rail">
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
              <span v-if="run.userId" class="active-run-owner" :title="run.userId">{{ shortText(run.userId, 12) }}</span>
            </div>
            <div class="active-run-actions">
              <button
                type="button"
                class="active-run-enter"
                @click="enterSessionFromList(run.sessionId, run.agentId)"
                title="进入此会话"
              >
                进入
              </button>
              <button
                type="button"
                class="active-run-cancel"
                @click="terminateRun(run.runId)"
                title="终止这个 run"
              >
                终止
              </button>
            </div>
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

      <section v-if="activeMenu === 'chat' && !form.sessionId" class="workspace-panel sessions-list-panel">
        <header class="sessions-list-header">
          <div>
            <h2>会话列表</h2>
            <p class="sessions-list-sub">当前权限范围内的所有会话。选一行进入会话视图。</p>
          </div>
          <button class="primary" @click="openNewSessionFromList">新建会话</button>
        </header>
        <div class="sessions-list-filters">
          <label class="sessions-filter-field">
            <span>关键字</span>
            <input v-model="sessionsListState.keyword" placeholder="搜 title / sessionId / agentId"
                   @keyup.enter="refreshSessionsList()">
          </label>
          <label class="sessions-filter-field">
            <span>用户 ID</span>
            <input v-model="sessionsListState.userId" placeholder="精确匹配"
                   @keyup.enter="refreshSessionsList()">
          </label>
          <label class="sessions-filter-field">
            <span>租户</span>
            <select v-model="sessionsListState.tenantId"
                    :disabled="!filterOptions.tenantsCanFilter"
                    :title="filterOptions.tenantsCanFilter ? '' : '只有系统管理员可以跨租户筛选'"
                    @change="refreshSessionsList()">
              <option value="">全部</option>
              <option v-for="t in filterOptions.tenants" :key="t.id" :value="t.id">
                {{ t.name || t.code || t.id }}
              </option>
            </select>
          </label>
          <label class="sessions-filter-field">
            <span>应用</span>
            <select v-model="sessionsListState.appId" @change="refreshSessionsList()">
              <option value="">全部</option>
              <option v-for="a in filterOptions.apps" :key="a.appId" :value="a.appId">
                {{ a.displayName || a.appId }}
              </option>
            </select>
          </label>
          <label class="sessions-filter-field">
            <span>Agent</span>
            <select v-model="sessionsListState.agentId" @change="refreshSessionsList()">
              <option value="">全部</option>
              <option v-for="a in agents" :key="a.agentId" :value="a.agentId">
                {{ a.displayName || a.agentId }}
              </option>
            </select>
          </label>
          <label class="sessions-filter-field">
            <span>运行状态</span>
            <select v-model="sessionsListState.runStatus" @change="refreshSessionsList()">
              <option value="all">全部</option>
              <option value="running">运行中</option>
              <option value="idle">空闲</option>
            </select>
          </label>
          <button class="sessions-filter-apply" @click="refreshSessionsList()">应用</button>
          <button class="sessions-filter-reset" @click="resetSessionsListFilters()">重置</button>
        </div>
        <div v-if="sessionsListState.loading" class="sessions-list-loading">加载中…</div>
        <div v-else-if="sessionsListState.error" class="sessions-list-error">{{ sessionsListState.error }}</div>
        <div v-else-if="!sessionsListState.rows.length" class="sessions-list-empty">
          没有符合条件的会话。点击"新建会话"创建一个。
        </div>
        <table v-else class="sessions-list-table">
          <thead>
            <tr>
              <th>会话</th>
              <th>Agent</th>
              <th>用户</th>
              <th>最近更新</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in sessionsListState.rows" :key="row.sessionId" class="sessions-list-row"
                @click="enterSessionFromList(row.sessionId, row.agentId)">
              <td>
                <div class="sessions-list-title">{{ row.title || '(无标题)' }}</div>
                <div class="sessions-list-id">{{ row.sessionId }}</div>
              </td>
              <td>{{ row.agentId }}</td>
              <td>{{ row.userId }}</td>
              <td>{{ formatTimestamp(row.updatedAt) }}</td>
              <td>
                <button class="sessions-list-enter" @click.stop="enterSessionFromList(row.sessionId, row.agentId)">进入</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="sessionsListState.total > sessionsListState.size" class="sessions-list-pager">
          <button :disabled="sessionsListState.page <= 0" @click="goSessionsListPage(sessionsListState.page - 1)">上一页</button>
          <span>第 {{ sessionsListState.page + 1 }} / {{ Math.ceil(sessionsListState.total / sessionsListState.size) }} 页 · 共 {{ sessionsListState.total }} 条</span>
          <button :disabled="(sessionsListState.page + 1) * sessionsListState.size >= sessionsListState.total"
                  @click="goSessionsListPage(sessionsListState.page + 1)">下一页</button>
        </div>
      </section>

      <section v-if="activeMenu === 'chat' && form.sessionId" class="workspace-panel chat-workspace">
        <div class="chat-back-bar">
          <button class="chat-back-button" @click="leaveSessionToList">← 返回会话列表</button>
        </div>
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
              <strong class="chat-title">{{ embedMode ? 'AI 助手' : currentSessionTitle }}</strong>
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
              <!-- 合并 LLM + 模型成一个下拉。option 是"LLM 名 - 模型名"格式,
                   v-model 同步写入 form.llmConfigId 和 form.llmModel(用 "configId|apiModel" 拼接 key 解。 -->
              <label class="bar-field bar-field-plain">
                <select v-model="selectedLlmModelKey" data-testid="llm-model-select">
                  <option value="|">自动默认</option>
                  <option v-for="opt in llmModelOptions" :key="opt.key" :value="opt.key">
                    {{ opt.label }}
                  </option>
                </select>
              </label>
              <!-- transport 切换 UI 已删:新通信架构下统一走 globalSocket(WebSocket),SSE 路径已下线 -->
              <!-- 刷新下拉:绕过 localStorage 缓存重拉 agents+llms,用户主动触发的兜底 -->
              <button
                type="button"
                class="recent-drawer-trigger icon-only"
                :disabled="dropdownRefreshing"
                @click="refreshDropdownLists"
                :title="dropdownRefreshing ? '刷新中…' : '刷新 Agents / LLM 列表'"
              >
                {{ dropdownRefreshing ? "⟳" : "↻" }}
              </button>
              <button
                type="button"
                class="recent-drawer-trigger"
                @click="toggleRecentSessionsDrawer"
                :title="recentSessionsDrawer.open ? '关闭最近会话' : '查看最近会话'"
              >
                ☰ 最近会话
              </button>
              <!-- 跳转到主控台对应会话页面:仅在 embed 模式下显示(非 embed 用户本来就在主控台)。
                   点击会带上当前 OAuth access_token,主控台拿到后用 Bearer 模式访问,
                   身份与 iframe 保持一致。 -->
              <button
                v-if="embedMode"
                type="button"
                class="recent-drawer-trigger icon-only"
                @click="openCurrentSessionInConsole"
                title="在新窗口打开主控台查看当前会话"
              >
                ↗
              </button>
              <!-- embed 模式独有:隐藏按钮放在按钮组末尾,点了通知父 SDK 收起浮动面板 -->
              <button
                v-if="embedMode"
                type="button"
                class="recent-drawer-trigger embed-hide-inline"
                title="隐藏 AI 助手"
                @click="hideEmbedPanel"
              >
                ✕ 关闭
              </button>
            </div>
          </div>

        </div>

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
                  <template v-if="shouldShowMessageText(block.message, group.runId)">
                    <!-- assistant 文本默认按 markdown 渲染(表格/列表/code/标题等),
                         别的角色(user/tool/system)继续走 plain 文本,避免被注入。 -->
                    <MarkdownBlock
                      v-if="block.message.role === 'assistant'"
                      class="markdown-preview bubble-markdown"
                      :markdown="displayMessageContent(block.message, group.runId)"
                      :to-html="markdownToHtml"
                    />
                    <p v-else>{{ displayMessageContent(block.message, group.runId) }}</p>
                  </template>
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
                  <!-- 这里之前有个 <pre> 兜底,把 db.query 的整段 rows/sql JSON dump 到聊天里 ——
                       彻底删掉。中间工具结果不应该出现在聊天,只有用户最终交付物(artifact / markdown)
                       才走上面的 template 分支显示出来。transcriptMessages 也已经在源头过滤,
                       这里是双保险:即使错放进 transcriptMessages 也不再 dump JSON。 -->
                  <!-- 本次 LLM 调用 token 用量。仅 assistant 气泡且后端返回了 usage 才显示。 -->
                  <div
                    v-if="block.message.role === 'assistant' && block.message.totalTokens"
                    class="bubble-token-usage"
                  >
                    ↳ 本次消耗 {{ block.message.totalTokens.toLocaleString() }} tokens
                    (输入 {{ (block.message.promptTokens || 0).toLocaleString() }} /
                     输出 {{ (block.message.completionTokens || 0).toLocaleString() }})
                  </div>
                </article>
                <article v-else-if="block.kind === 'live'" class="bubble assistant live">
                  <div class="bubble-meta">
                    <strong>assistant</strong>
                    <span>streaming</span>
                  </div>
                  <!-- thinking 块:推理模型(o1/R1/qwen-thinking 等)的 reasoning_content。
                       默认折叠,点头部展开。跟下面的正文是平行流。 -->
                  <details v-if="block.thinking" class="thinking-block" open>
                    <summary class="thinking-summary">
                      <span class="thinking-icon">🧠</span>
                      <span>思考过程</span>
                      <span class="thinking-len">{{ block.thinking.length }} 字</span>
                    </summary>
                    <div class="thinking-content">{{ block.thinking }}</div>
                  </details>
                  <!-- 正文流式 markdown 渲染 -->
                  <MarkdownBlock
                    v-if="block.content"
                    class="markdown-preview bubble-markdown"
                    :markdown="block.content"
                    :to-html="markdownToHtml"
                  />
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
                    <!-- 没有 <pre> 兜底:db.query 的 table JSON / tool_error 等内部数据不再 dump 到聊天。
                         上方 summary 一句话足够提示"这步工具完成了";要看完整 payload 去 Run 复盘。 -->
                  </template>
                  <template v-else-if="block.event.dynamic">
                    <p class="loading-line">
                      <span>任务执行中</span><span class="loading-dots"><i>.</i><i>.</i><i>.</i></span>
                    </p>
                  </template>
                  <p v-else>{{ formatTimelineEventContent(block.event) }}</p>
                </article>
                <!-- embed 模式下 plan 内嵌到 timeline:紧跟该 run 的第一条 user 消息显示。
                     v-for 每次循环都判断"当前 block 是不是 firstUserBlock",只在那一次循环
                     的 article 之后渲染 plan-inline,DOM 顺序正好是 user→plan→其它。
                     plan 全部 step COMPLETED 后 shouldShowInlinePlan 返回 false,自动隐藏。 -->
                <div
                  v-if="block === firstUserBlockOf(group) && shouldShowInlinePlan(group.runId)"
                  class="run-plan-inline"
                  :class="{ collapsed: state.runPlan.collapsed }"
                >
                  <header class="run-plan-inline-head">
                    <span class="run-plan-dot" aria-hidden="true"></span>
                    <strong class="run-plan-inline-title">{{ state.runPlan.title || "任务计划" }}</strong>
                    <span class="run-plan-inline-meta">{{ planProgressLabel || "进行中…" }}</span>
                    <button
                      type="button"
                      class="run-plan-inline-toggle"
                      @click="state.runPlan.collapsed = !state.runPlan.collapsed"
                    >{{ state.runPlan.collapsed ? "展开" : "收起" }}</button>
                  </header>
                  <ol v-if="!state.runPlan.collapsed" class="run-plan-inline-steps">
                    <li
                      v-for="step in state.runPlan.steps"
                      :key="step.id"
                      :class="['run-plan-inline-step', planStepClass(step)]"
                    >
                      <span class="run-plan-step-badge">{{ planStepBadge(step) }}</span>
                      <span class="run-plan-inline-step-title">
                        <span class="run-plan-inline-step-id">{{ step.id }}</span>
                        <span class="run-plan-inline-step-text">{{ step.title }}</span>
                      </span>
                    </li>
                  </ol>
                </div>
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
          <button class="primary" style="margin-left:auto;" @click="openMemoryEditor(null)">新增</button>
        </div>
        <!-- 工具栏(全选 + 批量删除)单独一行,避开 .panel-actions button 28px 压扁规则 -->
        <div class="memory-toolbar">
          <label class="memory-toolbar-check">
            <input type="checkbox" :checked="memoryAllSelected" @change="toggleSelectAllMemories($event.target.checked)" />
            全选
          </label>
          <button :disabled="!memorySelection.size" class="memory-batch-delete"
                  @click="batchDeleteMemories">
            批量删除 ({{ memorySelection.size }})
          </button>
        </div>
        <p class="empty-state" style="margin:0 0 10px; padding:8px 12px;">
          <strong>scope=agent</strong> 的备注跨所有会话可见（类似"长期记忆"）；<strong>scope=session</strong> 仅本会话可见（避免跨会话数据泄露）。
          一键点击"pin/unpin"可在两者间切换。
        </p>
        <div class="memory-grid">
          <article v-for="note in pagedMemories" :key="note.id" class="memory-note"
                   :class="{ 'memory-note-selected': memorySelection.has(note.id) }">
            <button class="memory-select-toggle"
                    :class="{ 'on': memorySelection.has(note.id) }"
                    :title="memorySelection.has(note.id) ? '取消选择' : '选择此条用于批量删除'"
                    @click="toggleMemorySelection(note.id, !memorySelection.has(note.id))">
              {{ memorySelection.has(note.id) ? '✓' : '○' }}
            </button>
            <div class="bubble-meta">
              <strong>
                <span :class="['event-chip', note.scope === 'agent' ? 'scope-agent' : note.scope === 'global' ? 'scope-global' : 'scope-session']">
                  {{ note.scope || 'session' }}
                </span>
                {{ note.source }}
              </strong>
              <span>{{ note.updatedAt || note.createdAt }}</span>
            </div>
            <p class="memory-note-content" :title="note.content">{{ truncateText(note.content, 100) }}</p>
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
        <!-- 长期记忆分页:前端切片,数据量大时仍是全量拉但 UI 不卡 -->
        <div v-if="state.memories.length > memoryPage.size" class="list-pager">
          <button @click="memoryPage.page = Math.max(0, memoryPage.page - 1)" :disabled="memoryPage.page === 0">上一页</button>
          <span>第 {{ memoryPage.page + 1 }} / {{ memoryTotalPages }} 页 · 共 {{ state.memories.length }} 条</span>
          <button @click="memoryPage.page = Math.min(memoryTotalPages - 1, memoryPage.page + 1)"
                  :disabled="memoryPage.page >= memoryTotalPages - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="memoryPage.size" @change="memoryPage.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
        </div>
      </section>

      <section v-if="activeMenu === 'usage'" class="workspace-panel usage-panel">
        <div class="panel-title">
          <h3>Token 用量</h3>
          <div class="usage-controls">
            <select v-model="usageState.range">
              <option value="1d">最近 24 小时</option>
              <option value="7d">最近 7 天</option>
              <option value="30d">最近 30 天</option>
            </select>
            <select v-model="usageState.groupBy">
              <option value="none">总计(不分组)</option>
              <option value="tenant">按租户</option>
              <option value="app">按应用</option>
              <option value="user">按用户</option>
              <option value="agent">按 Agent</option>
              <option value="model">按模型</option>
            </select>
            <button @click="reloadUsage" :disabled="usageState.loading">{{ usageState.loading ? '加载中...' : '刷新' }}</button>
          </div>
        </div>
        <div v-if="usageState.error" class="empty-state" style="color:#c0392b;">{{ usageState.error }}</div>

        <!-- 概览卡片 -->
        <div class="usage-summary-cards">
          <div class="usage-card">
            <div class="usage-card-label">总 token</div>
            <div class="usage-card-value">{{ usageTotal.totalTokens.toLocaleString() }}</div>
          </div>
          <div class="usage-card">
            <div class="usage-card-label">输入 token</div>
            <div class="usage-card-value">{{ usageTotal.promptTokens.toLocaleString() }}</div>
          </div>
          <div class="usage-card">
            <div class="usage-card-label">输出 token</div>
            <div class="usage-card-value">{{ usageTotal.completionTokens.toLocaleString() }}</div>
          </div>
          <div class="usage-card">
            <div class="usage-card-label">会话 / Run / 消息</div>
            <div class="usage-card-value">{{ usageTotal.sessionCount }} / {{ usageTotal.runCount }} / {{ usageTotal.messageCount }}</div>
          </div>
        </div>

        <!-- 时间序列折线 -->
        <div v-if="usageChartOption" class="usage-chart-block">
          <EchartsBlock :option="usageChartOption" />
        </div>
        <div v-else-if="!usageState.loading" class="empty-state">该时间范围无数据。</div>

        <!-- 分组聚合表(groupBy=none 跳过表格,只显示概览) -->
        <table v-if="usageState.summary.length && usageState.groupBy !== 'none'" class="usage-table">
          <thead>
            <tr>
              <th>{{ usageGroupByLabel }}</th>
              <th>输入</th>
              <th>输出</th>
              <th>总计</th>
              <th>消息数</th>
              <th>Run 数</th>
              <th>会话数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in usageState.summary" :key="(row.groupValue || '_null') + row.groupKey">
              <td>{{ row.groupValue || '(未设置)' }}</td>
              <td>{{ row.promptTokens.toLocaleString() }}</td>
              <td>{{ row.completionTokens.toLocaleString() }}</td>
              <td><strong>{{ row.totalTokens.toLocaleString() }}</strong></td>
              <td>{{ row.messageCount }}</td>
              <td>{{ row.runCount }}</td>
              <td>{{ row.sessionCount }}</td>
            </tr>
          </tbody>
        </table>

        <!-- 最近明细 -->
        <h4 style="margin: 24px 0 12px;">最近明细 (50 条)</h4>
        <table v-if="usageState.recent.length" class="usage-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>会话</th>
              <th>租户 / 应用 / 用户</th>
              <th>Agent</th>
              <th>输入</th>
              <th>输出</th>
              <th>总计</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in usageState.recent" :key="row.messageId">
              <td>{{ row.createdAt.replace('T', ' ').slice(0, 16) }}</td>
              <td><code>{{ row.sessionTitle || row.sessionId.slice(0, 8) }}</code></td>
              <td>{{ row.tenantId || '-' }} / {{ row.appId || '-' }} / {{ row.userId || '-' }}</td>
              <td>{{ row.agentId }}</td>
              <td>{{ (row.promptTokens || 0).toLocaleString() }}</td>
              <td>{{ (row.completionTokens || 0).toLocaleString() }}</td>
              <td><strong>{{ (row.totalTokens || 0).toLocaleString() }}</strong></td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="!usageState.loading" class="empty-state">暂无明细。</div>
      </section>

      <section v-if="activeMenu === 'lab'" class="workspace-panel lab-panel">
        <div class="panel-title">
          <h3>Agent 实验室</h3>
          <span class="hint">用 AI 自动迭代设计 Agent / Skill,直到通过你给的测试用例</span>
          <div class="panel-actions">
            <button @click="labReload" :disabled="labState.loading">{{ labState.loading ? '加载中...' : '刷新' }}</button>
            <button class="primary" @click="labOpenCreateModal">新建任务</button>
          </div>
        </div>
        <div v-if="labState.error" class="empty-state" style="color:#c0392b;">{{ labState.error }}</div>

        <!-- 详情视图(选中某个 task 时):任务概览 + iteration 时间线 -->
        <div v-if="labState.currentDetail" class="lab-detail">
          <div class="lab-detail-head">
            <button @click="labOpenDetail(null)">← 返回列表</button>
            <h4>{{ labState.currentDetail.task.title }}</h4>
            <span class="lab-status" :class="'status-' + (labState.currentDetail.task.status || '').toLowerCase()">{{ labState.currentDetail.task.status }}</span>
            <div class="panel-actions" style="margin-left:auto;">
              <button v-if="labIsRunning(labState.currentDetail.task)" @click="labCancel(labState.currentDetail.task.id)">⏹ 取消运行</button>
              <!-- 不再放裸"重启"按钮 —— 失败的任务原参数重跑只会再失败一次。
                   用户改 LLM / 规则后从下方"调参面板"提交 → 自动重启。 -->
            </div>
          </div>
          <div class="lab-detail-meta">
            <div><strong>目标:</strong> {{ labState.currentDetail.task.goalDescription }}</div>
            <div v-if="(labState.currentDetail.task.targetType || 'SKILL') === 'SKILL'">
              <strong>模式:</strong> {{ labState.currentDetail.task.mode }}
              · <strong>Host Agent:</strong> <code>{{ labState.currentDetail.task.targetAgentId }}</code>
              · <strong>目标 Skill:</strong> <code>{{ labState.currentDetail.task.targetSkillName || '(未设)' }}</code>
              <span v-if="labState.currentDetail.task.cloneFromSkillName">
                · <strong>克隆自:</strong> <code>{{ labState.currentDetail.task.cloneFromSkillName }}</code>
              </span>
              <span v-if="labState.currentDetail.task.targetScopeType">
                · <strong>scope:</strong> {{ labState.currentDetail.task.targetScopeType }}<span v-if="labState.currentDetail.task.targetScopeTenantId">/{{ labState.currentDetail.task.targetScopeTenantId }}</span>
              </span>
            </div>
            <div v-else>
              <strong>模式:</strong> AGENT-{{ labState.currentDetail.task.mode }}
              · <strong>目标 Agent:</strong> <code>{{ labState.currentDetail.task.targetAgentId }}</code>
              <span v-if="labState.currentDetail.task.cloneFromAgentId">
                · <strong>克隆自:</strong> <code>{{ labState.currentDetail.task.cloneFromAgentId }}</code>
              </span>
              <span class="lab-legacy-tag">(老任务)</span>
            </div>
            <div>
              <strong>Meta-LLM:</strong> <code>{{ labState.currentDetail.task.metaLlmConfigId || labState.currentDetail.task.llmConfigId || '(default)' }}</code>
              · <strong>Test-LLM:</strong> <code>{{ labState.currentDetail.task.testLlmConfigId || '(Agent 默认)' }}</code>
              · <strong>迭代:</strong> {{ labState.currentDetail.task.currentIteration }} / {{ labState.currentDetail.task.maxIterations }}
            </div>
            <div v-if="labState.currentDetail.task.finalSummary"><strong>结果:</strong> {{ labState.currentDetail.task.finalSummary }}</div>
            <div v-if="labState.currentDetail.task.errorDetail" style="color:#c0392b;"><strong>错误:</strong> {{ labState.currentDetail.task.errorDetail }}</div>
          </div>

          <!-- 详情页内嵌"编辑+重启"面板:换 LLM / 改约束规则 / 改参考文档,一键覆盖+重跑 -->
          <details class="lab-detail-editor"
                   :class="{ 'lab-detail-editor-failed': ['FAILED','FAILED_META_LLM','CANCELLED'].includes((labState.currentDetail.task.status||'').toUpperCase()) }"
                   :open="labDetailEditor.expanded"
                   @toggle="labDetailEditor.expanded = $event.target.open">
            <summary>
              <strong>✏️ 调整 LLM / 约束规则后重新运行</strong>
              <span class="lab-field-hint">
                改任意一项后点底部"保存并重启迭代",AI 会按新设置重新派生测试场景并从第 1 轮跑。
                已有迭代记录保留。
              </span>
            </summary>
            <!-- 失败 / 取消态简短提示 -->
            <div v-if="['FAILED','FAILED_META_LLM','CANCELLED'].includes((labState.currentDetail.task.status||'').toUpperCase())"
                 class="lab-detail-editor-fail-banner">
              ⚠ 上次失败,改 LLM 或规则后重新运行。
            </div>
            <div class="lab-detail-editor-body">
              <div class="lab-form-row">
                <label class="lab-field" style="flex: 2 1 0;">
                  <span class="lab-field-label">任务标题</span>
                  <input v-model="labDetailEditor.title" placeholder="任务标题(可改)" />
                </label>
                <label class="lab-field" style="flex: 0 0 160px;">
                  <span class="lab-field-label">最大迭代次数</span>
                  <input type="number" v-model.number="labDetailEditor.maxIterations" min="1" max="20" />
                </label>
              </div>
              <label class="lab-field">
                <span class="lab-field-label">目标描述</span>
                <textarea v-model="labDetailEditor.goalDescription" rows="3"
                          placeholder="任务目标:输入是什么、期望输出什么、用到哪些工具/数据"></textarea>
              </label>
              <div class="lab-form-row">
                <label class="lab-field">
                  <span class="lab-field-label">Meta-LLM(设计师)</span>
                  <select v-model="labDetailEditor.metaLlmKey">
                    <option value="|">— default —</option>
                    <option v-for="opt in labLlmOptions" :key="'detail-meta-' + opt.key" :value="opt.key">
                      {{ opt.label }}
                    </option>
                  </select>
                </label>
                <label class="lab-field">
                  <span class="lab-field-label">Test-LLM(跑测试)</span>
                  <select v-model="labDetailEditor.testLlmKey">
                    <option value="|">— Agent 默认 —</option>
                    <option v-for="opt in labLlmOptions" :key="'detail-test-' + opt.key" :value="opt.key">
                      {{ opt.label }}
                    </option>
                  </select>
                </label>
              </div>
              <label class="lab-field">
                <span class="lab-field-label">约束规则 <em>*</em></span>
                <textarea v-model="labDetailEditor.constraintRules" rows="6"
                          placeholder="自然语言写若干约束规则,AI 据此派生测试场景"></textarea>
              </label>
              <label class="lab-field">
                <span class="lab-field-label">参考文档(可选)</span>
                <textarea v-model="labDetailEditor.referenceDocuments" rows="4"
                          placeholder="数据字典 / 业务规范 / 样例;留空 = 清空"></textarea>
              </label>
              <div class="lab-detail-editor-actions">
                <button class="primary"
                        :disabled="labDetailEditor.saving"
                        @click="labDetailEditorSaveAndRestart">
                  {{ labDetailEditor.saving ? '提交中...' : '🚀 保存并重新运行' }}
                </button>
                <button @click="labLoadDetailEditor(labState.currentDetail.task)" :disabled="labDetailEditor.saving">
                  ↺ 重置为当前任务值
                </button>
                <span class="lab-field-hint" style="flex:1;">
                  当前 LLM:Meta=<code>{{ labState.currentDetail.task.metaLlmConfigId || '(default)' }}</code>
                  · Test=<code>{{ labState.currentDetail.task.testLlmConfigId || '(Agent 默认)' }}</code>
                </span>
              </div>
            </div>
          </details>

          <h4 style="margin: 24px 0 12px;">迭代时间线 ({{ labState.currentDetail.iterations.length }} 轮)</h4>
          <div v-for="iter in labState.currentDetail.iterations" :key="iter.id" class="lab-iter-card"
               :class="{ 'lab-iter-running': iter.status === 'IN_PROGRESS' }">
            <div class="lab-iter-head">
              <strong>第 {{ iter.iterationNo }} 轮</strong>
              <span class="lab-iter-status" :class="'status-' + (iter.status || '').toLowerCase()">{{ iter.status }}</span>
              <span v-if="iter.totalCount !== null" class="lab-iter-pass">{{ iter.passedCount }} / {{ iter.totalCount }} 通过</span>
              <span class="lab-iter-time">{{ (iter.createdAt || '').replace('T', ' ').slice(0, 19) }}</span>
            </div>
            <!-- 实时进度:IN_PROGRESS 时显示 progressStep,让用户知道正在做什么 -->
            <div v-if="iter.status === 'IN_PROGRESS' && iter.progressStep" class="lab-iter-progress">
              <span class="lab-iter-progress-spinner">⏳</span> {{ iter.progressStep }}
            </div>
            <div v-if="iter.evaluationSummary" class="lab-iter-summary">{{ iter.evaluationSummary }}</div>
            <div v-if="iter.metaLlmError" style="color:#c0392b; padding:8px 0;">meta-LLM 错误: {{ iter.metaLlmError }}</div>
            <details class="lab-iter-details" :open="iter.status === 'IN_PROGRESS'">
              <summary>{{ iter.status === 'IN_PROGRESS' ? '本轮已产出(实时刷)' : '查看本轮 Agent / Skill / 测试结果' }}</summary>
              <div class="lab-snapshot-block">
                <div class="lab-snapshot-label">Agent 配置</div>
                <pre v-if="iter.agentSnapshotJson">{{ iter.agentSnapshotJson }}</pre>
                <div v-else class="muted" style="padding:6px 0;font-style:italic;">(meta-LLM 还没返回)</div>
              </div>
              <div class="lab-snapshot-block">
                <div class="lab-snapshot-label">Skill 列表</div>
                <pre v-if="iter.skillSnapshotsJson && iter.skillSnapshotsJson !== '[]'">{{ iter.skillSnapshotsJson }}</pre>
                <div v-else class="muted" style="padding:6px 0;font-style:italic;">(meta-LLM 还没返回)</div>
              </div>
              <div class="lab-snapshot-block">
                <div class="lab-snapshot-label">测试结果(规则评估)</div>
                <pre v-if="iter.testResultsJson">{{ iter.testResultsJson }}</pre>
                <div v-else class="muted" style="padding:6px 0;font-style:italic;">(还没跑测试场景)</div>
              </div>
              <div class="lab-snapshot-block">
                <div class="lab-snapshot-label">完整运行 trace(每个用例的真实 chat run / tool 调用 / SkillGuard 决策)</div>
                <pre v-if="iter.runTracesJson">{{ iter.runTracesJson }}</pre>
                <div v-else class="muted" style="padding:6px 0;font-style:italic;">(还没跑测试场景)</div>
              </div>
              <div v-if="iter.fixPlanJson" class="lab-snapshot-block">
                <div class="lab-snapshot-label">下一轮修复建议</div>
                <pre>{{ iter.fixPlanJson }}</pre>
              </div>
            </details>
          </div>
          <div v-if="!labState.currentDetail.iterations.length" class="empty-state">还没有迭代,可能 meta-LLM 调用还在进行中...</div>
        </div>

        <!-- 列表视图(默认):任务表格 -->
        <table v-else-if="labState.tasks.length" class="usage-table lab-task-table">
          <thead>
            <tr>
              <th>标题</th>
              <th>状态</th>
              <th>迭代</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in labState.tasks" :key="task.id">
              <td>
                <a @click="labOpenDetail(task.id)" style="cursor:pointer; color:#1a73e8;">{{ task.title }}</a>
                <div style="font-size:11px; color:rgba(0,0,0,0.4);">{{ task.id }}</div>
              </td>
              <td><span class="lab-status" :class="'status-' + (task.status || '').toLowerCase()">{{ task.status }}</span></td>
              <td>{{ task.currentIteration }} / {{ task.maxIterations }}</td>
              <td>{{ task.createdAt.replace('T', ' ').slice(0, 19) }}</td>
              <td>
                <button @click="labOpenDetail(task.id)">详情</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="!labState.loading" class="empty-state">还没有任务,点"新建任务"开始。</div>
      </section>

      <section v-if="activeMenu === 'admin-sessions'" class="workspace-panel admin-sessions-panel">
        <div class="panel-title">
          <h3>会话审计</h3>
          <span class="hint">系统/租户管理员可见。后端按 SessionVisibility 自动过滤(系统管理员看全;租户管理员看本租户)。</span>
          <div class="panel-actions">
            <button @click="adminSessionsReload" :disabled="adminSessionsState.loading">{{ adminSessionsState.loading ? '加载中...' : '刷新' }}</button>
          </div>
        </div>
        <div v-if="adminSessionsState.error" class="empty-state" style="color:#c0392b;">{{ adminSessionsState.error }}</div>

        <!-- 筛选区(租户 → 应用 → agent 三级联动,改一级清空下游) -->
        <div class="admin-sessions-filters">
          <label class="lab-field"><span class="lab-field-label">租户</span>
            <select v-model="adminSessionsFilter.tenantId">
              <option value="">— 全部 —</option>
              <option v-for="t in adminSessionsState.tenants" :key="t.id" :value="t.id">
                {{ t.name || t.id }} ({{ t.id }})
              </option>
            </select>
          </label>
          <label class="lab-field"><span class="lab-field-label">应用</span>
            <select v-model="adminSessionsFilter.appId">
              <option value="">— 全部 —</option>
              <option v-for="c in adminAppsForSelect" :key="c.clientId" :value="c.clientId">
                {{ c.displayName || c.clientId }} ({{ c.clientId }})
              </option>
            </select>
          </label>
          <label class="lab-field"><span class="lab-field-label">Agent</span>
            <select v-model="adminSessionsFilter.agentId">
              <option value="">— 全部 —</option>
              <option v-for="a in adminAgentsForSelect" :key="a.agentId" :value="a.agentId">
                {{ a.displayName || a.agentId }} ({{ a.agentId }})
              </option>
            </select>
          </label>
          <label class="lab-field"><span class="lab-field-label">user_id</span>
            <input v-model="adminSessionsFilter.userId" placeholder="(可选)手填用户 id" /></label>
          <label class="lab-field"><span class="lab-field-label">时间范围</span>
            <select v-model="adminSessionsFilter.fromDays">
              <option :value="1">最近 24 小时</option>
              <option :value="7">最近 7 天</option>
              <option :value="30">最近 30 天</option>
              <option :value="0">不限</option>
            </select>
          </label>
          <button class="primary admin-filter-apply"
                  @click="adminSessionsState.page = 0; adminSessionsReload()">应用筛选</button>
          <button :disabled="!adminSessionsSelection.size"
                  class="memory-batch-delete admin-filter-apply"
                  @click="adminBatchDeleteSessions">
            批量删除 ({{ adminSessionsSelection.size }})
          </button>
        </div>

        <!-- 统计卡 -->
        <div class="usage-summary-cards">
          <div class="usage-card">
            <div class="usage-card-label">会话数</div>
            <div class="usage-card-value">{{ adminSessionsState.totalSessions.toLocaleString() }}</div>
          </div>
          <div class="usage-card">
            <div class="usage-card-label">Run 数</div>
            <div class="usage-card-value">{{ adminSessionsState.totalRuns.toLocaleString() }}</div>
          </div>
          <div class="usage-card">
            <div class="usage-card-label">消息数</div>
            <div class="usage-card-value">{{ adminSessionsState.totalMessages.toLocaleString() }}</div>
          </div>
          <div class="usage-card">
            <div class="usage-card-label">总 token</div>
            <div class="usage-card-value">{{ adminSessionsState.totalTokens.toLocaleString() }}</div>
          </div>
        </div>

        <!-- 列表表格 -->
        <table v-if="adminSessionsState.rows.length" class="usage-table admin-sessions-table">
          <thead>
            <tr>
              <th style="width:32px;" :title="manageableAdminSessions.length ? '全选(仅当前用户可管理的行)' : ''">
                <input type="checkbox" :disabled="!manageableAdminSessions.length"
                       :checked="adminSessionsAllSelected"
                       @change="toggleAdminSessionsAllSelected($event.target.checked)" />
              </th>
              <th>会话</th>
              <th>租户 / 应用 / 用户</th>
              <th>Agent</th>
              <th>状态</th>
              <th>消息 / Run / Token</th>
              <th>更新</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in adminSessionsState.rows" :key="row.sessionId"
                :class="{ 'row-selected': adminSessionsSelection.has(row.sessionId) }">
              <td>
                <input type="checkbox"
                       v-if="canManageSession(row)"
                       :checked="adminSessionsSelection.has(row.sessionId)"
                       @change="toggleAdminSessionSelection(row.sessionId, $event.target.checked)" />
                <span v-else class="text-muted-tiny" title="无权限管理此会话">—</span>
              </td>
              <td>
                <div>{{ row.title || '(无标题)' }}</div>
                <div style="font-size:11px; color:rgba(0,0,0,0.4); font-family: monospace;">{{ row.sessionId }}</div>
              </td>
              <td>{{ row.tenantId || '-' }} / {{ row.appId || '-' }} / {{ row.userId || '-' }}</td>
              <td><code>{{ row.agentId }}</code></td>
              <td><span class="lab-status" :class="'status-' + (row.status || '').toLowerCase()">{{ row.status }}</span></td>
              <td>{{ row.messageCount }} / {{ row.runCount }} / {{ row.totalTokens.toLocaleString() }}</td>
              <td>{{ (row.updatedAt || '').replace('T', ' ').slice(0, 16) }}</td>
              <td>
                <button @click="navigateToMenu('chat', { sessionId: row.sessionId, agentId: row.agentId })">进入会话</button>
                <button @click="adminOpenSessionRuns(row.sessionId, row.title)" style="margin-left:6px;">查看 Run 列表</button>
                <button v-if="canManageSession(row)"
                        @click="adminDeleteSession(row.sessionId, row.title)"
                        style="margin-left:6px; color:#c0392b;">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="!adminSessionsState.loading" class="empty-state">无符合条件的会话。</div>

        <!-- 简单分页 -->
        <div v-if="adminSessionsState.rows.length" class="admin-sessions-pager">
          <button @click="adminSessionsState.page = Math.max(0, adminSessionsState.page - 1)"
                  :disabled="adminSessionsState.page === 0">上一页</button>
          <span>第 {{ adminSessionsState.page + 1 }} 页 (每页 {{ adminSessionsState.size }})</span>
          <button @click="adminSessionsState.page = adminSessionsState.page + 1"
                  :disabled="adminSessionsState.rows.length < adminSessionsState.size">下一页</button>
          <span style="margin-left: 16px;">每页:</span>
          <select v-model.number="adminSessionsState.size" @change="adminSessionsState.page = 0">
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
          </select>
        </div>
      </section>

      <!-- Session 下 run 列表 modal:管理员选一个 run 进入复盘 -->
      <div v-if="adminRunsModal.open" class="modal-backdrop" @click.self="adminCloseSessionRuns">
        <div class="modal-card catalog-modal" style="max-width: 900px;">
          <div class="panel-title">
            <h3>选择要复盘的 Run</h3>
            <div class="panel-actions">
              <button @click="adminCloseSessionRuns" title="关闭">✕</button>
            </div>
          </div>
          <div class="lab-detail-meta" style="margin-top: 0;">
            <div><strong>Session:</strong> {{ adminRunsModal.sessionTitle || '(无标题)' }}</div>
            <div style="font-family: monospace; font-size: 11px; color: rgba(0,0,0,0.4);">{{ adminRunsModal.sessionId }}</div>
          </div>
          <div v-if="adminRunsModal.loading" class="empty-state">加载中...</div>
          <div v-else-if="adminRunsModal.error" class="empty-state" style="color:#c0392b;">{{ adminRunsModal.error }}</div>
          <table v-else-if="adminRunsModal.runs.length" class="usage-table">
            <thead>
              <tr>
                <th>Run ID</th>
                <th>状态</th>
                <th>LLM</th>
                <th>事件数</th>
                <th>时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="run in adminRunsModal.runs" :key="run.runId">
                <td><code style="font-size: 11px;">{{ run.runId }}</code></td>
                <td><span class="lab-status" :class="'status-' + (run.status || '').toLowerCase()">{{ run.status }}</span></td>
                <td>{{ run.llmModel || run.llmConfigId || '-' }}</td>
                <td>
                  <span v-if="run.hasEventLog">{{ run.eventCount }} 条</span>
                  <span v-else style="color: rgba(0,0,0,0.3);">无 log</span>
                </td>
                <td>{{ (run.createdAt || '').replace('T', ' ').slice(0, 19) }}</td>
                <td>
                  <button class="primary" @click="adminOpenReplayFromList(run.runId)" :disabled="!run.hasEventLog">
                    {{ run.hasEventLog ? '复盘' : '无可用 log' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-state">该会话下还没有 run。</div>
        </div>
      </div>

      <!-- Run 复盘 modal:展示 event_log 的时间线 -->
      <div v-if="adminReplayState.open" class="modal-backdrop" @click.self="adminCloseReplay">
        <div class="modal-card catalog-modal lab-create-modal">
          <div class="panel-title">
            <h3>Run 复盘 <code style="font-size:13px; font-weight:400;">{{ adminReplayState.runId }}</code></h3>
            <div class="panel-actions">
              <button @click="adminCloseReplay" title="关闭">✕</button>
            </div>
          </div>
          <div class="lab-form" style="padding: 0;">
            <div v-if="adminReplayState.loading" class="empty-state">加载中...</div>
            <div v-else-if="adminReplayState.error" class="empty-state" style="color:#c0392b;">{{ adminReplayState.error }}</div>
            <template v-else-if="adminReplayState.data">
              <div class="lab-detail-meta">
                <div><strong>Session:</strong> <code>{{ adminReplayState.data.sessionId }}</code></div>
                <div>
                  <strong>租户:</strong> {{ adminReplayState.data.tenantId || '-' }}
                  · <strong>应用:</strong> {{ adminReplayState.data.appId || '-' }}
                  · <strong>用户:</strong> {{ adminReplayState.data.userId || '-' }}
                </div>
                <div>
                  <strong>Agent:</strong> <code>{{ adminReplayState.data.agentId }}</code>
                  · <strong>LLM:</strong> {{ adminReplayState.data.llmConfigId || '-' }} ({{ adminReplayState.data.llmModel || '-' }})
                  · <strong>状态:</strong> {{ adminReplayState.data.status }}
                </div>
                <div v-if="adminReplayState.data.detail" style="white-space: pre-wrap;">
                  <strong>详情:</strong> {{ adminReplayState.data.detail }}
                </div>
                <div v-if="adminReplayState.data.requestMessage">
                  <strong>用户输入:</strong>
                  <pre style="white-space: pre-wrap; margin: 4px 0; background: #f7f9fc; padding: 8px; border-radius: 4px; max-height: 200px; overflow: auto;">{{ adminReplayState.data.requestMessage }}</pre>
                </div>
              </div>

              <h4 style="margin: 24px 0 12px;">
                运行时间线 (显示 {{ adminReplayEventsFiltered.length }} / 共 {{ adminReplayEvents.length }} 个事件)
              </h4>
              <!-- 分类过滤 chip:点一下切换该分类的可见性 -->
              <div v-if="adminReplayEvents.length" class="replay-filter-bar">
                <span class="replay-filter-label">按类型过滤:</span>
                <button class="replay-filter-chip" :class="{ active: adminReplayState.activeCategories.has('ai') }"
                        @click="adminToggleReplayCategory('ai')">
                  🤖 AI 调用 <span class="replay-filter-count">{{ adminReplayCategoryCounts.ai }}</span>
                </button>
                <button class="replay-filter-chip" :class="{ active: adminReplayState.activeCategories.has('db') }"
                        @click="adminToggleReplayCategory('db')">
                  🗄 数据库 <span class="replay-filter-count">{{ adminReplayCategoryCounts.db }}</span>
                </button>
                <button class="replay-filter-chip" :class="{ active: adminReplayState.activeCategories.has('file') }"
                        @click="adminToggleReplayCategory('file')">
                  📁 文件 <span class="replay-filter-count">{{ adminReplayCategoryCounts.file }}</span>
                </button>
                <button class="replay-filter-chip" :class="{ active: adminReplayState.activeCategories.has('artifact') }"
                        @click="adminToggleReplayCategory('artifact')">
                  📄 产物 <span class="replay-filter-count">{{ adminReplayCategoryCounts.artifact }}</span>
                </button>
                <button class="replay-filter-chip" :class="{ active: adminReplayState.activeCategories.has('plan') }"
                        @click="adminToggleReplayCategory('plan')">
                  📋 Plan <span class="replay-filter-count">{{ adminReplayCategoryCounts.plan }}</span>
                </button>
                <button class="replay-filter-chip" :class="{ active: adminReplayState.activeCategories.has('tool-other') }"
                        @click="adminToggleReplayCategory('tool-other')">
                  🔧 其它工具 <span class="replay-filter-count">{{ adminReplayCategoryCounts['tool-other'] }}</span>
                </button>
                <button class="replay-filter-chip" :class="{ active: adminReplayState.activeCategories.has('lifecycle') }"
                        @click="adminToggleReplayCategory('lifecycle')">
                  🟢 生命周期 <span class="replay-filter-count">{{ adminReplayCategoryCounts.lifecycle }}</span>
                </button>
                <span class="replay-filter-spacer"></span>
                <button class="replay-filter-quick" @click="adminReplaySetCategories(['ai','db','file','artifact','plan','tool-other','lifecycle'])">全选</button>
                <button class="replay-filter-quick" @click="adminReplaySetCategories(['ai'])">只看 AI</button>
                <button class="replay-filter-quick" @click="adminReplaySetCategories(['db','file','artifact','tool-other'])">只看工具</button>
              </div>
              <div v-if="!adminReplayEvents.length" class="empty-state">该 run 没有 event log(可能是迁移后旧数据,或运行未完成)</div>
              <div v-else-if="!adminReplayEventsFiltered.length" class="empty-state">当前过滤条件下没有事件 —— 点上面的类型 chip 重新打开</div>
              <div v-else class="replay-timeline">
                <div v-for="evt in adminReplayEventsFiltered" :key="evt.seq" class="replay-event"
                     :class="['replay-event-' + (evt.type || '').toLowerCase(), 'replay-cat-' + evt._category]">
                  <div class="replay-event-head">
                    <span class="replay-event-seq">#{{ evt.seq }}</span>
                    <span :class="['replay-cat-badge', 'replay-cat-' + evt._category]">{{ replayCategoryLabel(evt._category) }}</span>
                    <span class="replay-event-type">{{ evt.type }}</span>
                    <span v-if="evt._toolName || extractToolName(evt?.data?.content) || evt?.data?.toolName" class="replay-event-tool">
                      🔧 {{ evt._toolName || extractToolName(evt?.data?.content) || evt?.data?.toolName }}
                    </span>
                    <span class="replay-event-ts">{{ (evt.ts || '').replace('T', ' ').slice(0, 19) }}</span>
                  </div>
                  <div v-if="evt._summary" class="replay-event-summary">{{ evt._summary }}</div>
                  <details class="replay-event-data" v-if="evt.data && Object.keys(evt.data).length">
                    <summary>data ({{ Object.keys(evt.data).length }} 字段)</summary>
                    <pre>{{ JSON.stringify(evt.data, null, 2) }}</pre>
                  </details>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- 新建 lab 任务 modal(全屏化方便长表单 + 同时看错误反馈) -->
      <div v-if="labShowCreate" class="modal-backdrop" @click.self="labCloseCreateModal">
        <div class="modal-card catalog-modal lab-create-modal">
          <div class="panel-title">
            <h3>{{ labCreateModal.activeTaskId ? '调试中:' + (labCreateModal.detail?.task?.title || '') : '新建 Agent 实验室任务' }}</h3>
            <div class="panel-actions">
              <button @click="labCloseCreateModal" title="关闭(任务在后台继续运行)">✕</button>
            </div>
          </div>
          <div class="lab-form">
            <!-- 第 1 段:基础信息 -->
            <div class="lab-form-section">
              <label class="lab-field">
                <span class="lab-field-label">任务标题 <em>*</em></span>
                <input v-model="labForm.title" placeholder="例如:盘龙区覆盖分析报告生成" />
              </label>
              <label class="lab-field">
                <span class="lab-field-label">
                  目标描述 <em>*</em>
                  <button type="button" class="goal-refine-btn" @click="labOpenGoalChat">
                    💬 让 AI 帮我完善
                  </button>
                </span>
                <span class="lab-field-hint">
                  写清楚:输入是什么、期望输出什么、用到哪些工具/数据。
                  写不完整也没关系 —— 点上面的按钮跟 AI 临时对话,它会一边问你一边把描述补完整。
                </span>
                <textarea v-model="labForm.goalDescription" rows="6"
                  placeholder="例如:用户给一段文字,Agent 调用 db.query 查盘龙区覆盖率,然后调用 artifact.markdown 生成结构化报告..."></textarea>
              </label>
            </div>

            <!-- AI 帮我完善目标描述 — 临时对话面板 -->
            <div v-if="labGoalChat.visible" class="goal-chat-overlay" @click.self="labCloseGoalChat(false)">
              <div class="goal-chat-card">
                <div class="goal-chat-head">
                  <strong>💬 AI 帮我完善目标描述</strong>
                  <label class="goal-chat-llm-pick">
                    <span>用哪个 LLM:</span>
                    <select v-model="labGoalChat.llmKey">
                      <option value="|">— default —</option>
                      <option v-for="opt in labLlmOptions" :key="'goal-' + opt.key" :value="opt.key">
                        {{ opt.label }}
                      </option>
                    </select>
                  </label>
                  <span class="lab-field-hint" style="flex:1;">不影响表单其它字段;关闭时可选择是否采用</span>
                  <button type="button" class="goal-chat-close" @click="labCloseGoalChat(false)">✕</button>
                </div>
                <div class="goal-chat-body">
                  <!-- 左:对话气泡 -->
                  <div class="goal-chat-thread">
                    <div v-for="(turn, i) in labGoalChat.history" :key="i"
                         :class="['goal-chat-bubble', turn.role === 'user' ? 'role-user' : 'role-assistant']">
                      <div class="goal-chat-role">{{ turn.role === 'user' ? '你' : 'AI' }}</div>
                      <div class="goal-chat-content">{{ turn.content }}</div>
                    </div>
                    <div v-if="labGoalChat.loading" class="goal-chat-bubble role-assistant">
                      <div class="goal-chat-role">AI</div>
                      <div class="goal-chat-content"><em>思考中...</em></div>
                    </div>
                    <div v-if="labGoalChat.error" class="goal-chat-error">{{ labGoalChat.error }}</div>
                  </div>
                  <!-- 右:候选目标描述 -->
                  <div class="goal-chat-candidate">
                    <div class="goal-chat-candidate-head">
                      <strong>当前 AI 草稿</strong>
                      <span class="lab-field-hint">最新一轮的 refinedGoal,点 "采用此版本" 替换表单里的目标描述</span>
                    </div>
                    <textarea v-model="labGoalChat.candidate" rows="14" class="goal-chat-candidate-text"></textarea>
                  </div>
                </div>
                <div class="goal-chat-footer">
                  <input v-model="labGoalChat.inputBuffer" class="goal-chat-input"
                         placeholder="再具体一点 / 加上覆盖率指标计算口径 / 输出加 markdown 表格 ..."
                         @keydown.enter.exact.prevent="labSendGoalChat()" />
                  <button type="button" class="primary" :disabled="labGoalChat.loading || !labGoalChat.inputBuffer.trim()"
                          @click="labSendGoalChat">{{ labGoalChat.loading ? '调用中...' : '发送' }}</button>
                  <span style="flex:1;"></span>
                  <button type="button" @click="labResetGoalChat">清空对话</button>
                  <button type="button" class="primary" @click="labCloseGoalChat(true)"
                          :disabled="!labGoalChat.candidate || labGoalChat.candidate === labForm.goalDescription">
                    采用此版本
                  </button>
                  <button type="button" @click="labCloseGoalChat(false)">取消</button>
                </div>
              </div>
            </div>

            <!-- 第 2 段:目标 Skill -->
            <div class="lab-form-section">
              <div class="lab-section-head">
                <strong>目标 Skill</strong>
                <span class="lab-field-hint">每轮 meta-LLM 只改这一个 Skill 的 promptTemplate / configJson;Host Agent 不动。</span>
              </div>

              <label class="lab-field">
                <span class="lab-field-label">模式</span>
                <select v-model="labForm.mode">
                  <option value="NEW">NEW — 新建 Skill,meta-LLM 从零设计</option>
                  <option value="EXISTING">EXISTING — 调试已有 Skill(覆盖式修改)</option>
                  <option value="CLONE_FROM">CLONE_FROM — 从源 Skill 克隆配置作为起点(源不动)</option>
                </select>
              </label>

              <label class="lab-field">
                <span class="lab-field-label">Host Agent 来源 <em>*</em></span>
                <span class="lab-field-hint">Skill 必须挂在一个 Agent 上才能被触发。默认情况下这个 Agent 不会被 meta-LLM 修改。</span>
                <select v-model="labForm.hostAgentMode">
                  <option value="EXISTING">EXISTING — 选已有 Agent</option>
                  <option value="NEW">NEW — 新建 Agent(可写一句种子提示词)</option>
                  <option value="CLONE_FROM">CLONE_FROM — 从已有 Agent 克隆配置</option>
                </select>
              </label>

              <label v-if="labForm.hostAgentMode === 'EXISTING'" class="lab-field">
                <span class="lab-field-label">已有 Agent <em>*</em></span>
                <select v-model="labForm.hostAgentId">
                  <option value="">— 请选择 —</option>
                  <option v-for="a in agents" :key="a.agentId" :value="a.agentId">
                    {{ a.displayName || a.agentId }} ({{ a.agentId }})
                  </option>
                </select>
              </label>

              <label v-if="labForm.hostAgentMode === 'CLONE_FROM'" class="lab-field">
                <span class="lab-field-label">源 Agent(克隆起点)<em>*</em></span>
                <span class="lab-field-hint">复制源 Agent 的 systemPrompt 作为新 Agent 的起点,源不会被修改</span>
                <select v-model="labForm.cloneFromHostAgentId">
                  <option value="">— 请选择 —</option>
                  <option v-for="a in agents" :key="a.agentId" :value="a.agentId">
                    {{ a.displayName || a.agentId }} ({{ a.agentId }})
                  </option>
                </select>
              </label>

              <template v-if="labForm.hostAgentMode === 'NEW' || labForm.hostAgentMode === 'CLONE_FROM'">
                <label class="lab-field">
                  <span class="lab-field-label">新 Agent ID</span>
                  <span class="lab-field-hint">
                    可选:留空后端按"任务标题 slug + 6 位 uuid"自动生成
                    (形如 <code>lab-host-{slug}-a3f1c2</code>)。手填需 <code>[a-z0-9-]+</code>。
                  </span>
                  <input v-model="labForm.newHostAgentId" placeholder="留空自动生成" />
                </label>
                <label class="lab-field">
                  <span class="lab-field-label">Agent 显示名</span>
                  <span class="lab-field-hint">留空时用任务标题</span>
                  <input v-model="labForm.newHostAgentDisplayName" placeholder="留空用任务标题" />
                </label>
                <label v-if="labForm.hostAgentMode === 'NEW'" class="lab-field">
                  <span class="lab-field-label">种子提示词(可选)</span>
                  <span class="lab-field-hint">写一句话描述这个 Agent 的角色 / 背景 / 输出风格。留空 = 完全由 meta-LLM 自由设计(需开"允许同时优化 Host Agent")</span>
                  <textarea v-model="labForm.newHostAgentSeedPrompt" rows="3"
                    placeholder="例如:你是一个专门做电信网络覆盖分析的助手,擅长解读 RSRP / RSRQ 等无线指标..."></textarea>
                </label>
                <label class="lab-field">
                  <span class="lab-field-label">新 Agent 的 scope</span>
                  <select v-model="labForm.newHostAgentScopeType">
                    <option value="SYSTEM">SYSTEM — 系统全局可见</option>
                    <option value="TENANT">TENANT — 仅本租户可见</option>
                    <option value="USER">USER — 仅自己可见</option>
                  </select>
                </label>
                <label v-if="labForm.newHostAgentScopeType === 'TENANT'" class="lab-field">
                  <span class="lab-field-label">租户 id <em>*</em></span>
                  <input v-model="labForm.newHostAgentScopeTenantId" placeholder="例如 tenant-xmap" />
                </label>
              </template>

              <label class="lab-field lab-field-checkbox">
                <input type="checkbox" v-model="labForm.allowAgentEvolution" />
                <span class="lab-field-label" style="display:inline; font-weight:600;">允许在迭代过程中同时优化 Host Agent</span>
                <span class="lab-field-hint">
                  默认关:每轮 meta-LLM 只改 Skill,Host Agent 保持创建时的配置不动。
                  开启后:每轮 meta-LLM 同时输出 Agent 和 Skill 的修订,两者都被覆盖
                  (适用于"种子提示词需要扩写"或"想让 Agent prompt 跟 Skill 一起进化"的场景)。
                </span>
              </label>

              <label v-if="labForm.mode === 'EXISTING'" class="lab-field">
                <span class="lab-field-label">选择已有 Skill <em>*</em></span>
                <select v-model="labForm.targetSkillName">
                  <option value="">— 请选择 —</option>
                  <option v-for="s in labState.allSkills" :key="s.skillName + (s.id || '')" :value="s.skillName">
                    {{ s.skillName }}<span v-if="s.description"> — {{ (s.description || '').slice(0, 50) }}</span>
                  </option>
                </select>
              </label>

              <label v-if="labForm.mode === 'CLONE_FROM'" class="lab-field">
                <span class="lab-field-label">源 Skill(克隆起点)<em>*</em></span>
                <select v-model="labForm.cloneFromSkillName">
                  <option value="">— 请选择 —</option>
                  <option v-for="s in labState.allSkills" :key="s.skillName + (s.id || '')" :value="s.skillName">
                    {{ s.skillName }}<span v-if="s.description"> — {{ (s.description || '').slice(0, 50) }}</span>
                  </option>
                </select>
              </label>

              <label v-if="labForm.mode === 'NEW' || labForm.mode === 'CLONE_FROM'" class="lab-field">
                <span class="lab-field-label">新 Skill 名</span>
                <span class="lab-field-hint">
                  可选:留空后端按"任务标题 slug + 6 位 uuid"自动生成
                  (形如 <code>skill.lab.{slug}.a3f1c2</code>)。统一前缀 <code>skill.lab.</code> 便于在管理页一眼区分。
                </span>
                <input v-model="labForm.newSkillName" placeholder="留空自动生成" />
              </label>

              <label v-if="labForm.mode === 'NEW' || labForm.mode === 'CLONE_FROM'" class="lab-field">
                <span class="lab-field-label">新 Skill 的 scope</span>
                <select v-model="labForm.targetScopeType">
                  <option value="SYSTEM">SYSTEM — 系统全局可见</option>
                  <option value="TENANT">TENANT — 仅本租户可见</option>
                  <option value="USER">USER — 仅自己可见</option>
                </select>
              </label>

              <label v-if="(labForm.mode === 'NEW' || labForm.mode === 'CLONE_FROM') && labForm.targetScopeType === 'TENANT'"
                     class="lab-field">
                <span class="lab-field-label">租户 id <em>*</em></span>
                <input v-model="labForm.targetScopeTenantId" placeholder="例如 tenant-xmap" />
              </label>
            </div>

            <!-- 第 3 段:约束规则 + 参考文档 —— 用户不再手写测试用例。
                 写若干自然语言约束规则(必填),可选粘贴参考文档,
                 后端 meta-LLM 据此自动派生 ~3 条测试场景并迭代调试。 -->
            <div class="lab-form-section">
              <div class="lab-section-head">
                <strong>约束规则 <em>*</em></strong>
                <span class="lab-field-hint">
                  用<strong>自然语言</strong>写若干约束规则,一行一条或写成段落都行。
                  AI 会据规则 + 目标 + 参考文档自己派生测试场景,跑设计 + 调试 + 评估的完整循环。
                  你只描述"应该满足什么",不描述"应该怎么实现"。
                </span>
              </div>
              <label class="lab-field">
                <span class="lab-field-label">约束规则文本 <em>*</em></span>
                <textarea v-model="labForm.constraintRules" rows="8"
                          placeholder="例如(每行一条):&#10;- 输出必须是 markdown 格式的报告&#10;- 必须包含整体覆盖率 / 弱覆盖区域占比 / TOP3 弱覆盖小区&#10;- 涉及区县时必须做县级过滤,不能拿全省数据&#10;- 不要寒暄,直接给报告"></textarea>
              </label>
              <label class="lab-field">
                <span class="lab-field-label">参考文档(可选)</span>
                <span class="lab-field-hint">粘贴数据字典 / 业务规范 / 样例报告等;AI 派生测试场景时一并参考。</span>
                <textarea v-model="labForm.referenceDocuments" rows="6"
                          placeholder="可粘贴样例报告、字段说明、业务口径解释等;留空也可以。"></textarea>
              </label>
            </div>

            <!-- 第 4 段:LLM 配置 + 迭代上限 -->
            <div class="lab-form-section">
              <div class="lab-section-head">
                <strong>LLM 配置</strong>
                <span class="lab-field-hint">
                  Meta-LLM 当"设计师"改 Skill;Test-LLM 跑测试用例时驱动 Agent ·
                  <strong>当前可选 LLM:{{ labLlmOptions.length }} 个</strong>
                  <span v-if="labLlmOptions.length === 0" style="color:#c0392b;"> ← catalog 还没加载,点关闭再试</span>
                </span>
              </div>
              <div class="lab-form-row">
                <label class="lab-field">
                  <span class="lab-field-label">Meta-LLM(设计师)</span>
                  <span class="lab-field-hint">改 Skill prompt / configJson 用。留空走 default(is_default=true)</span>
                  <select v-model="labMetaLlmKey">
                    <option value="|">— default —</option>
                    <option v-for="opt in labLlmOptions" :key="'meta-' + opt.key" :value="opt.key">
                      {{ opt.label }}
                    </option>
                  </select>
                </label>
                <label class="lab-field">
                  <span class="lab-field-label">Test-LLM(跑测试)</span>
                  <span class="lab-field-hint">跑测试用例时 Agent 实际用的 LLM。留空 = Agent 自己绑的默认</span>
                  <select v-model="labTestLlmKey">
                    <option value="|">— Agent 默认 —</option>
                    <option v-for="opt in labLlmOptions" :key="'test-' + opt.key" :value="opt.key">
                      {{ opt.label }}
                    </option>
                  </select>
                </label>
              </div>
              <label class="lab-field" style="max-width: 220px;">
                <span class="lab-field-label">最大迭代次数</span>
                <input type="number" v-model.number="labForm.maxIterations" min="1" max="20" />
              </label>
            </div>

            <!-- 第 5 段:运行状态面板 —— 提交后实时刷,允许停 / 重启 / 改写约束规则后再调试 -->
            <div v-if="labCreateModal.activeTaskId" class="lab-form-section lab-status-panel">
              <div class="lab-section-head">
                <strong>📊 运行状态</strong>
                <span class="lab-field-hint">每 3 秒自动刷;关 modal 不影响后台继续跑。</span>
                <span class="lab-status-badge"
                      :class="'status-' + (labCreateModal.detail?.task?.status || 'pending').toLowerCase()">
                  {{ labCreateModal.detail?.task?.status || 'PENDING' }}
                </span>
              </div>
              <div v-if="labCreateModal.pollError" class="empty-state" style="color:#c0392b;">
                轮询出错:{{ labCreateModal.pollError }}
              </div>
              <div class="lab-status-meta">
                <div><strong>任务 ID:</strong> <code>{{ labCreateModal.activeTaskId }}</code></div>
                <div v-if="labCreateModal.detail">
                  <strong>当前轮:</strong> {{ labCreateModal.detail.task.currentIteration }} / {{ labCreateModal.detail.task.maxIterations }}
                  · <strong>已完成:</strong> {{ labCreateModal.detail.iterations?.length || 0 }} 轮
                </div>
                <div v-if="labCreateModal.detail?.task?.finalSummary">
                  <strong>结果:</strong> {{ labCreateModal.detail.task.finalSummary }}
                </div>
                <div v-if="labCreateModal.detail?.task?.errorDetail" style="color:#c0392b;">
                  <strong>错误:</strong> {{ labCreateModal.detail.task.errorDetail }}
                </div>
              </div>

              <!-- 迭代摘要列表(从新到旧) -->
              <div v-if="labCreateModal.detail?.iterations?.length" class="lab-status-iters">
                <div v-for="iter in [...labCreateModal.detail.iterations].reverse()" :key="iter.id"
                     class="lab-status-iter">
                  <div class="lab-status-iter-head">
                    <strong>第 {{ iter.iterationNo }} 轮</strong>
                    <span class="lab-iter-status" :class="'status-' + (iter.status || '').toLowerCase()">
                      {{ iter.status }}
                    </span>
                    <span v-if="iter.totalCount !== null" class="lab-iter-pass">
                      {{ iter.passedCount }} / {{ iter.totalCount }} 通过
                    </span>
                    <span class="lab-iter-time">{{ (iter.createdAt || '').replace('T', ' ').slice(0, 19) }}</span>
                  </div>
                  <div v-if="iter.status === 'IN_PROGRESS' && iter.progressStep" class="lab-status-iter-progress">
                    <span class="lab-iter-progress-spinner">⏳</span> {{ iter.progressStep }}
                  </div>
                  <div v-if="iter.evaluationSummary" class="lab-status-iter-summary">
                    {{ iter.evaluationSummary }}
                  </div>
                  <div v-if="iter.metaLlmError" style="color:#c0392b; font-size:12px; margin-top:4px;">
                    meta-LLM 错误:{{ iter.metaLlmError }}
                  </div>
                </div>
              </div>

              <!-- 状态面板自带操作按钮 -->
              <div class="lab-status-actions">
                <button v-if="['PENDING','RUNNING'].includes(labCreateModal.detail?.task?.status)"
                        @click="labCreateModalCancel">⏹ 停止</button>
                <button v-else @click="labCreateModalRestart">🔁 同规则重跑</button>
                <button :disabled="labCreateModal.refining" @click="labCreateModalRefineRules">
                  {{ labCreateModal.refining ? '提交中...' : '✏️ 改写规则后再调试' }}
                </button>
                <span class="lab-field-hint" style="flex:1;">
                  改了上方"约束规则"或"参考文档"后,点 ✏️ AI 会重新派生测试场景并从第 1 轮跑。
                </span>
              </div>
            </div>
          </div>
          <div class="modal-actions">
            <button @click="labCloseCreateModal">{{ labCreateModal.activeTaskId ? '关闭(后台继续跑)' : '取消' }}</button>
            <button v-if="!labCreateModal.activeTaskId" class="primary"
                    :disabled="labCreateModal.submitting" @click="labSubmitCreate">
              {{ labCreateModal.submitting ? '创建中...' : '创建并开始迭代' }}
            </button>
          </div>
        </div>
      </div>

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
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="agentsList.state.keyword" placeholder="搜索 agentId / 名称 / 描述" />
          <button :disabled="!agentsList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteAgents">
            批量删除 ({{ agentsList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="agentsList.allSelected.value" @change="agentsList.toggleAll($event.target.checked)" /></th>
              <th>Agent ID</th>
              <th>显示名</th>
              <th>描述</th>
              <th>状态</th>
              <th>提示词长度</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in agentsList.paged.value" :key="item.agentId" :class="{ 'row-selected': agentsList.selection.has(item.agentId) }">
              <td class="col-check"><input type="checkbox" :checked="agentsList.selection.has(item.agentId)" @change="agentsList.toggle(item.agentId, $event.target.checked)" /></td>
              <td><code>{{ item.agentId }}</code></td>
              <td>{{ item.displayName }}</td>
              <td class="col-ellipsis" :title="item.description">{{ item.description || "—" }}</td>
              <td>{{ item.enabled ? "enabled" : "disabled" }}</td>
              <td>sys {{ (item.systemPrompt || "").length }} · AGENT {{ (item.agentMarkdown || "").length }} · MEM {{ (item.memoryMarkdown || "").length }}</td>
              <td class="col-actions">
                <template v-if="canManageScoped(item)">
                  <button @click="openAgentEditor(item)">编辑</button>
                  <button @click="removeAgentDefinition(item)">删除</button>
                </template>
                <span v-else class="muted">—</span>
              </td>
            </tr>
            <tr v-if="!agentsList.filtered.value.length"><td colspan="7" class="empty-row">{{ state.catalog.agents.length ? "没有匹配的 agent。" : "还没有 agent 定义。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="agentsList.filtered.value.length > agentsList.state.size" class="list-pager">
          <button @click="agentsList.state.page = Math.max(0, agentsList.state.page - 1)" :disabled="agentsList.state.page === 0">上一页</button>
          <span>第 {{ agentsList.state.page + 1 }} / {{ agentsList.totalPages.value }} 页 · 共 {{ agentsList.filtered.value.length }} 条</span>
          <button @click="agentsList.state.page = Math.min(agentsList.totalPages.value - 1, agentsList.state.page + 1)" :disabled="agentsList.state.page >= agentsList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="agentsList.state.size" @change="agentsList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
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
          <ScopeEditor v-model="agentEditor.scope" />
          <div class="dock-actions">
            <button class="primary" @click="submitAgentEditor">保存</button>
            <button @click="closeAgentEditor">取消</button>
          </div>
        </div>
      </div>

      <section v-if="activeMenu === 'llms'" class="workspace-panel catalog-page">
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="llmsList.state.keyword" placeholder="搜索 id / 名称 / model / provider" />
          <button :disabled="!llmsList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteLlms">
            批量删除 ({{ llmsList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="llmsList.allSelected.value" @change="llmsList.toggleAll($event.target.checked)" /></th>
              <th>ID</th>
              <th>显示名</th>
              <th>Provider / Model</th>
              <th>Base URL</th>
              <th>属性</th>
              <th>更新时间</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in llmsList.paged.value" :key="item.id" :class="{ 'row-selected': llmsList.selection.has(item.id) }">
              <td class="col-check"><input type="checkbox" :checked="llmsList.selection.has(item.id)" @change="llmsList.toggle(item.id, $event.target.checked)" /></td>
              <td><code>{{ item.id }}</code></td>
              <td>{{ item.displayName }}</td>
              <td>{{ item.provider }} · {{ item.model }}</td>
              <td class="col-ellipsis" :title="item.baseUrl"><code>{{ item.baseUrl }}</code></td>
              <td>{{ item.stream ? "stream" : "non-stream" }} · {{ item.enabled ? "启用" : "停用" }}<span v-if="item.defaultConfig"> · default</span></td>
              <td>{{ item.updatedAt }}</td>
              <td class="col-actions">
                <template v-if="canManageScoped(item)">
                  <button @click="editLlm(item)">编辑</button>
                  <button @click="removeLlm(item.id)">删除</button>
                </template>
                <span v-else class="muted">—</span>
              </td>
            </tr>
            <tr v-if="!llmsList.filtered.value.length"><td colspan="8" class="empty-row">{{ state.catalog.llms.length ? "没有匹配的 LLM 配置。" : "当前没有 LLM 配置。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="llmsList.filtered.value.length > llmsList.state.size" class="list-pager">
          <button @click="llmsList.state.page = Math.max(0, llmsList.state.page - 1)" :disabled="llmsList.state.page === 0">上一页</button>
          <span>第 {{ llmsList.state.page + 1 }} / {{ llmsList.totalPages.value }} 页 · 共 {{ llmsList.filtered.value.length }} 条</span>
          <button @click="llmsList.state.page = Math.min(llmsList.totalPages.value - 1, llmsList.state.page + 1)" :disabled="llmsList.state.page >= llmsList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="llmsList.state.size" @change="llmsList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
        </div>
      </section>

      <section v-if="activeMenu === 'knowledge'" class="workspace-panel catalog-page">
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="knowledgeList.state.keyword" placeholder="搜索标题 / 内容 / 来源" />
          <button :disabled="!knowledgeList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteKnowledge">
            批量删除 ({{ knowledgeList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="knowledgeList.allSelected.value" @change="knowledgeList.toggleAll($event.target.checked)" /></th>
              <th>标题</th>
              <th>内容(前 100 字)</th>
              <th>来源</th>
              <th>更新时间</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in knowledgeList.paged.value" :key="item.id" :class="{ 'row-selected': knowledgeList.selection.has(item.id) }">
              <td class="col-check"><input type="checkbox" :checked="knowledgeList.selection.has(item.id)" @change="knowledgeList.toggle(item.id, $event.target.checked)" /></td>
              <td>{{ item.title }}</td>
              <td class="col-ellipsis" :title="item.content">{{ truncateText(item.content, 100) }}</td>
              <td>{{ item.source || "—" }}</td>
              <td>{{ item.updatedAt }}</td>
              <td class="col-actions">
                <template v-if="canManageScoped(item)">
                  <button @click="editKnowledge(item)">编辑</button>
                  <button @click="removeKnowledge(item.id)">删除</button>
                </template>
                <span v-else class="muted">—</span>
              </td>
            </tr>
            <tr v-if="!knowledgeList.filtered.value.length"><td colspan="6" class="empty-row">{{ state.catalog.knowledge.length ? "没有匹配的知识条目。" : "当前没有知识条目。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="knowledgeList.filtered.value.length > knowledgeList.state.size" class="list-pager">
          <button @click="knowledgeList.state.page = Math.max(0, knowledgeList.state.page - 1)" :disabled="knowledgeList.state.page === 0">上一页</button>
          <span>第 {{ knowledgeList.state.page + 1 }} / {{ knowledgeList.totalPages.value }} 页 · 共 {{ knowledgeList.filtered.value.length }} 条</span>
          <button @click="knowledgeList.state.page = Math.min(knowledgeList.totalPages.value - 1, knowledgeList.state.page + 1)" :disabled="knowledgeList.state.page >= knowledgeList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="knowledgeList.state.size" @change="knowledgeList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
        </div>
      </section>

      <section v-if="activeMenu === 'tools'" class="workspace-panel catalog-page">
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="toolsList.state.keyword" placeholder="搜索 toolName / 名称 / 描述" />
          <button :disabled="!toolsList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteTools">
            批量删除 ({{ toolsList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="toolsList.allSelected.value" @change="toolsList.toggleAll($event.target.checked)" /></th>
              <th>Tool Name</th>
              <th>显示名</th>
              <th>描述</th>
              <th>更新时间</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in toolsList.paged.value" :key="item.id" :class="{ 'row-selected': toolsList.selection.has(item.id) }">
              <td class="col-check"><input type="checkbox" :checked="toolsList.selection.has(item.id)" @change="toolsList.toggle(item.id, $event.target.checked)" /></td>
              <td><code>{{ item.toolName }}</code></td>
              <td>{{ item.displayName }}</td>
              <td class="col-ellipsis" :title="item.description">{{ item.description || "—" }}</td>
              <td>{{ item.updatedAt }}</td>
              <td class="col-actions">
                <template v-if="canManageScoped(item)">
                  <button @click="editTool(item)">编辑</button>
                  <button @click="removeTool(item.id)">删除</button>
                </template>
                <span v-else class="muted">—</span>
              </td>
            </tr>
            <tr v-if="!toolsList.filtered.value.length"><td colspan="6" class="empty-row">{{ state.catalog.tools.length ? "没有匹配的工具。" : "当前没有工具定义。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="toolsList.filtered.value.length > toolsList.state.size" class="list-pager">
          <button @click="toolsList.state.page = Math.max(0, toolsList.state.page - 1)" :disabled="toolsList.state.page === 0">上一页</button>
          <span>第 {{ toolsList.state.page + 1 }} / {{ toolsList.totalPages.value }} 页 · 共 {{ toolsList.filtered.value.length }} 条</span>
          <button @click="toolsList.state.page = Math.min(toolsList.totalPages.value - 1, toolsList.state.page + 1)" :disabled="toolsList.state.page >= toolsList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="toolsList.state.size" @change="toolsList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
        </div>
      </section>

      <section v-if="activeMenu === 'skills'" class="workspace-panel catalog-page">
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="skillsList.state.keyword" placeholder="搜索 skillName / 描述 / agentId" />
          <button :disabled="!skillsList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteSkills">
            批量删除 ({{ skillsList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="skillsList.allSelected.value" @change="skillsList.toggleAll($event.target.checked)" /></th>
              <th>Skill Name</th>
              <th>描述</th>
              <th>绑定 Agent</th>
              <th>触发词</th>
              <th>状态</th>
              <th>更新时间</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in skillsList.paged.value" :key="item.id" :class="{ 'row-selected': skillsList.selection.has(item.id) }">
              <td class="col-check"><input type="checkbox" :checked="skillsList.selection.has(item.id)" @change="skillsList.toggle(item.id, $event.target.checked)" /></td>
              <td><strong>{{ item.skillName }}</strong></td>
              <td class="col-ellipsis" :title="item.description">{{ item.description || "—" }}</td>
              <td>
                <span v-for="agentId in (item.agentIds && item.agentIds.length ? item.agentIds : [item.agentId]).filter(Boolean)" :key="agentId" class="catalog-card-tag">{{ agentId }}</span>
                <span v-if="!item.agentIds?.length && !item.agentId" class="muted">未绑定</span>
              </td>
              <td class="col-ellipsis" :title="item.triggerKeywords">
                <code v-if="item.triggerKeywords && item.triggerKeywords !== '[]'">{{ item.triggerKeywords }}</code>
                <span v-else class="muted">—</span>
              </td>
              <td>{{ item.enabled ? "enabled" : "disabled" }}</td>
              <td>{{ item.updatedAt }}</td>
              <td class="col-actions">
                <template v-if="canManageScoped(item)">
                  <button @click="editSkill(item)">编辑</button>
                  <button @click="removeSkill(item.id)">删除</button>
                </template>
                <span v-else class="muted">—</span>
              </td>
            </tr>
            <tr v-if="!skillsList.filtered.value.length"><td colspan="8" class="empty-row">{{ state.catalog.skills.length ? "没有匹配的 Skill。" : "当前没有 Skill 定义。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="skillsList.filtered.value.length > skillsList.state.size" class="list-pager">
          <button @click="skillsList.state.page = Math.max(0, skillsList.state.page - 1)" :disabled="skillsList.state.page === 0">上一页</button>
          <span>第 {{ skillsList.state.page + 1 }} / {{ skillsList.totalPages.value }} 页 · 共 {{ skillsList.filtered.value.length }} 条</span>
          <button @click="skillsList.state.page = Math.min(skillsList.totalPages.value - 1, skillsList.state.page + 1)" :disabled="skillsList.state.page >= skillsList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="skillsList.state.size" @change="skillsList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
        </div>
      </section>

      <section v-if="activeMenu === 'datasources'" class="workspace-panel catalog-page">
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="datasourcesList.state.keyword" placeholder="搜索 id / 名称 / jdbcUrl / dialect" />
          <button :disabled="!datasourcesList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteDatasources">
            批量删除 ({{ datasourcesList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="datasourcesList.allSelected.value" @change="datasourcesList.toggleAll($event.target.checked)" /></th>
              <th>ID</th>
              <th>显示名</th>
              <th>JDBC URL</th>
              <th>用户名</th>
              <th>方言</th>
              <th>状态</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in datasourcesList.paged.value" :key="item.id" :class="{ 'row-selected': datasourcesList.selection.has(item.id) }">
              <td class="col-check"><input type="checkbox" :checked="datasourcesList.selection.has(item.id)" @change="datasourcesList.toggle(item.id, $event.target.checked)" /></td>
              <td><code>{{ item.id }}</code></td>
              <td>{{ item.displayName || "—" }}</td>
              <td class="col-ellipsis" :title="item.jdbcUrl"><code>{{ item.jdbcUrl }}</code></td>
              <td>{{ item.username }} <span v-if="item.passwordSet" class="muted">/ pwd ✓</span><span v-else class="muted">/ pwd —</span></td>
              <td>{{ item.dialect || "—" }}</td>
              <td>{{ item.enabled ? "启用" : "停用" }}</td>
              <td class="col-actions">
                <template v-if="canManageScoped(item)">
                  <button @click="editDatasource(item)">编辑</button>
                  <button @click="removeDatasource(item.id)">删除</button>
                </template>
                <span v-else class="muted">—</span>
              </td>
            </tr>
            <tr v-if="!datasourcesList.filtered.value.length"><td colspan="8" class="empty-row">{{ state.catalog.datasources.length ? "没有匹配的数据源。" : "当前没有数据源。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="datasourcesList.filtered.value.length > datasourcesList.state.size" class="list-pager">
          <button @click="datasourcesList.state.page = Math.max(0, datasourcesList.state.page - 1)" :disabled="datasourcesList.state.page === 0">上一页</button>
          <span>第 {{ datasourcesList.state.page + 1 }} / {{ datasourcesList.totalPages.value }} 页 · 共 {{ datasourcesList.filtered.value.length }} 条</span>
          <button @click="datasourcesList.state.page = Math.min(datasourcesList.totalPages.value - 1, datasourcesList.state.page + 1)" :disabled="datasourcesList.state.page >= datasourcesList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="datasourcesList.state.size" @change="datasourcesList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
        </div>
      </section>

      <!-- ========== P4-B 用户管理 ========== -->
      <section v-if="activeMenu === 'users'" class="workspace-panel catalog-page">
        <div class="admin-toolbar">
          <button class="primary" @click="openCreateUserModal">+ 新建用户</button>
          <button @click="openImportUsersModal">批量导入</button>
        </div>
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="usersList.state.keyword" placeholder="搜索 id / 用户名 / 显示名 / email" />
          <button :disabled="!usersList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteUsers">
            批量删除 ({{ usersList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="usersList.allSelected.value" @change="usersList.toggleAll($event.target.checked)" /></th>
              <th>用户名</th>
              <th>显示名</th>
              <th>Email</th>
              <th>租户</th>
              <th>状态</th>
              <th>最近登录</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in usersList.paged.value" :key="item.id" :class="{ 'row-selected': usersList.selection.has(item.id) }">
              <td class="col-check"><input type="checkbox" :disabled="item.id === 'admin'" :checked="usersList.selection.has(item.id)" @change="usersList.toggle(item.id, $event.target.checked)" /></td>
              <td><strong>{{ item.username }}</strong> <span class="muted">({{ item.id }})</span></td>
              <td>{{ item.displayName || "—" }}</td>
              <td>{{ item.email || "—" }}</td>
              <td>{{ item.preferredTenantId || "—" }}</td>
              <td>{{ item.status }}<span v-if="item.passwordMustChange" title="待改密" class="muted"> ⚠</span></td>
              <td>{{ item.lastLoginAt || "—" }}</td>
              <td class="col-actions">
                <button @click="openEditUserModal(item)">编辑</button>
                <button @click="assignUserRolesHandler(item)">角色</button>
                <button @click="resetUserPasswordHandler(item.id)">重置密码</button>
                <button @click="deleteUserHandler(item.id)" :disabled="item.id === 'admin'">删除</button>
              </td>
            </tr>
            <tr v-if="!usersList.filtered.value.length"><td colspan="8" class="empty-row">{{ state.catalog.users.length ? "没有匹配的用户。" : "还没有用户。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="usersList.filtered.value.length > usersList.state.size" class="list-pager">
          <button @click="usersList.state.page = Math.max(0, usersList.state.page - 1)" :disabled="usersList.state.page === 0">上一页</button>
          <span>第 {{ usersList.state.page + 1 }} / {{ usersList.totalPages.value }} 页 · 共 {{ usersList.filtered.value.length }} 条</span>
          <button @click="usersList.state.page = Math.min(usersList.totalPages.value - 1, usersList.state.page + 1)" :disabled="usersList.state.page >= usersList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="usersList.state.size" @change="usersList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
        </div>
      </section>

      <!-- ========== P4-B 租户管理 ========== -->
      <section v-if="activeMenu === 'tenants'" class="workspace-panel catalog-page">
        <div class="admin-toolbar">
          <button class="primary" @click="openCreateTenantModal">+ 新建租户</button>
        </div>
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="tenantsList.state.keyword" placeholder="搜索 id / code / 名称 / 描述" />
          <button :disabled="!tenantsList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteTenants">
            批量删除 ({{ tenantsList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="tenantsList.allSelected.value" @change="tenantsList.toggleAll($event.target.checked)" /></th>
              <th>ID</th>
              <th>Code</th>
              <th>名称</th>
              <th>类型</th>
              <th>状态</th>
              <th>描述</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in tenantsList.paged.value" :key="item.id" :class="{ 'row-selected': tenantsList.selection.has(item.id) }">
              <td class="col-check"><input type="checkbox" :disabled="item.id === 'system'" :checked="tenantsList.selection.has(item.id)" @change="tenantsList.toggle(item.id, $event.target.checked)" /></td>
              <td><code>{{ item.id }}</code></td>
              <td>{{ item.code }}</td>
              <td><strong>{{ item.name }}</strong></td>
              <td>{{ item.kind }}</td>
              <td>{{ item.status }}</td>
              <td class="col-ellipsis" :title="item.description">{{ item.description || "—" }}</td>
              <td class="col-actions">
                <button @click="editTenantHandler(item)">编辑</button>
                <button @click="deleteTenantHandler(item.id)" :disabled="item.id === 'system'">删除</button>
              </td>
            </tr>
            <tr v-if="!tenantsList.filtered.value.length"><td colspan="8" class="empty-row">{{ state.catalog.tenants.length ? "没有匹配的租户。" : "还没有租户。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="tenantsList.filtered.value.length > tenantsList.state.size" class="list-pager">
          <button @click="tenantsList.state.page = Math.max(0, tenantsList.state.page - 1)" :disabled="tenantsList.state.page === 0">上一页</button>
          <span>第 {{ tenantsList.state.page + 1 }} / {{ tenantsList.totalPages.value }} 页 · 共 {{ tenantsList.filtered.value.length }} 条</span>
          <button @click="tenantsList.state.page = Math.min(tenantsList.totalPages.value - 1, tenantsList.state.page + 1)" :disabled="tenantsList.state.page >= tenantsList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="tenantsList.state.size" @change="tenantsList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
        </div>
      </section>

      <!-- ========== P4-B 角色管理 ========== -->
      <section v-if="activeMenu === 'roles'" class="workspace-panel catalog-page">
        <div class="admin-toolbar">
          <button class="primary" @click="openCreateRoleModal">+ 新建角色</button>
        </div>
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="rolesList.state.keyword" placeholder="搜索 id / code / 名称 / 租户" />
          <button :disabled="!rolesList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteRoles">
            批量删除 ({{ rolesList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="rolesList.allSelected.value" @change="rolesList.toggleAll($event.target.checked)" /></th>
              <th>ID</th>
              <th>Code</th>
              <th>名称</th>
              <th>租户</th>
              <th>描述</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rolesList.paged.value" :key="item.id" :class="{ 'row-selected': rolesList.selection.has(item.id) }">
              <td class="col-check"><input type="checkbox" :disabled="item.id === 'system-super-admin' || item.id === 'system-user'" :checked="rolesList.selection.has(item.id)" @change="rolesList.toggle(item.id, $event.target.checked)" /></td>
              <td><code>{{ item.id }}</code></td>
              <td>{{ item.code }}</td>
              <td><strong>{{ item.name }}</strong></td>
              <td>{{ item.tenantId }}</td>
              <td class="col-ellipsis" :title="item.description">{{ item.description || "—" }}</td>
              <td class="col-actions">
                <button @click="editRolePermissionsHandler(item)">权限</button>
                <button @click="editRoleHandler(item)">编辑</button>
                <button @click="deleteRoleHandler(item.id)" :disabled="item.id === 'system-super-admin' || item.id === 'system-user'">删除</button>
              </td>
            </tr>
            <tr v-if="!rolesList.filtered.value.length"><td colspan="7" class="empty-row">{{ state.catalog.roles.length ? "没有匹配的角色。" : "还没有角色。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="rolesList.filtered.value.length > rolesList.state.size" class="list-pager">
          <button @click="rolesList.state.page = Math.max(0, rolesList.state.page - 1)" :disabled="rolesList.state.page === 0">上一页</button>
          <span>第 {{ rolesList.state.page + 1 }} / {{ rolesList.totalPages.value }} 页 · 共 {{ rolesList.filtered.value.length }} 条</span>
          <button @click="rolesList.state.page = Math.min(rolesList.totalPages.value - 1, rolesList.state.page + 1)" :disabled="rolesList.state.page >= rolesList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="rolesList.state.size" @change="rolesList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
        </div>
        <details style="margin-top:16px;">
          <summary class="muted" style="cursor:pointer;">权限目录（只读）</summary>
          <table class="data-table" style="margin-top:8px;">
            <thead><tr><th>权限码</th><th>分类</th><th>描述</th></tr></thead>
            <tbody>
              <tr v-for="p in state.catalog.permissions" :key="p.code">
                <td><code>{{ p.code }}</code></td>
                <td>{{ p.category }}</td>
                <td>{{ p.description }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </section>

      <!-- ========== P4-B OAuth 客户端管理 ========== -->
      <section v-if="activeMenu === 'oauth-clients'" class="workspace-panel catalog-page">
        <div class="admin-toolbar">
          <button class="primary" @click="openCreateOauthClientModal">+ 新建外部应用</button>
        </div>
        <div class="list-toolbar">
          <input class="list-toolbar-search" v-model="oauthClientsList.state.keyword" placeholder="搜索 client_id / 显示名 / owner" />
          <button :disabled="!oauthClientsList.selection.size" class="list-toolbar-batch-delete" @click="batchDeleteOauthClients">
            批量删除 ({{ oauthClientsList.selection.size }})
          </button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="oauthClientsList.allSelected.value" @change="oauthClientsList.toggleAll($event.target.checked)" /></th>
              <th>Client ID</th>
              <th>显示名</th>
              <th>状态</th>
              <th>显示 AI 按钮</th>
              <th>Owner</th>
              <th>Redirect URIs</th>
              <th>Scopes</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in oauthClientsList.paged.value" :key="item.clientId" :class="{ 'row-selected': oauthClientsList.selection.has(item.clientId) }">
              <td class="col-check"><input type="checkbox" :checked="oauthClientsList.selection.has(item.clientId)" @change="oauthClientsList.toggle(item.clientId, $event.target.checked)" /></td>
              <td><code>{{ item.clientId }}</code></td>
              <td><strong>{{ item.displayName }}</strong></td>
              <td>{{ item.status }}</td>
              <td>{{ item.displayEnabled === false ? '✗ 隐藏' : '✓ 显示' }}</td>
              <td>{{ item.ownerUserId || "—" }}</td>
              <td class="col-ellipsis" :title="item.redirectUris"><code>{{ item.redirectUris }}</code></td>
              <td class="col-ellipsis" :title="item.scopes"><code>{{ item.scopes }}</code></td>
              <td class="col-actions">
                <button @click="editOauthClientHandler(item)">编辑</button>
                <button @click="rotateOauthSecretHandler(item.clientId)">轮换 secret</button>
                <button @click="deleteOauthClientHandler(item.clientId)">删除</button>
              </td>
            </tr>
            <tr v-if="!oauthClientsList.filtered.value.length"><td colspan="9" class="empty-row">{{ state.catalog.oauthClients.length ? "没有匹配的外部应用。" : "还没有外部应用。" }}</td></tr>
          </tbody>
        </table>
        <div v-if="oauthClientsList.filtered.value.length > oauthClientsList.state.size" class="list-pager">
          <button @click="oauthClientsList.state.page = Math.max(0, oauthClientsList.state.page - 1)" :disabled="oauthClientsList.state.page === 0">上一页</button>
          <span>第 {{ oauthClientsList.state.page + 1 }} / {{ oauthClientsList.totalPages.value }} 页 · 共 {{ oauthClientsList.filtered.value.length }} 条</span>
          <button @click="oauthClientsList.state.page = Math.min(oauthClientsList.totalPages.value - 1, oauthClientsList.state.page + 1)" :disabled="oauthClientsList.state.page >= oauthClientsList.totalPages.value - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="oauthClientsList.state.size" @change="oauthClientsList.state.page = 0">
            <option :value="12">12</option>
            <option :value="24">24</option>
            <option :value="48">48</option>
          </select>
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
              : adminModal.type === 'userEdit' ? '编辑用户'
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

        <!-- 用户:编辑(displayName / email / status / preferredTenantId) -->
        <template v-if="adminModal.type === 'userEdit'">
          <label>
            <span>用户名</span>
            <input :value="userEditForm.username" disabled />
          </label>
          <label>
            <span>用户 id</span>
            <input :value="userEditForm.id" disabled />
          </label>
          <label>
            <span>显示名</span>
            <input v-model="userEditForm.displayName" placeholder="如:张三" />
          </label>
          <label>
            <span>email</span>
            <input v-model="userEditForm.email" placeholder="可选" />
          </label>
          <label>
            <span>状态</span>
            <select v-model="userEditForm.status">
              <option value="ACTIVE">ACTIVE</option>
              <option value="DISABLED">DISABLED</option>
              <option value="LOCKED">LOCKED</option>
            </select>
          </label>
          <label>
            <span>默认租户 id（仅系统管理员可改）</span>
            <input
              v-model="userEditForm.preferredTenantId"
              placeholder="如 system / xmap"
              :disabled="!authUser.permissions.includes('session.read.all')"
            />
          </label>
          <p v-if="userEditForm.passwordMustChange" class="modal-copy">
            ⚠ 该用户标记为"首次登录强制改密"。
          </p>
          <div class="dock-actions">
            <button class="primary" @click="submitUserEdit">保存</button>
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
            <span>状态(功能开关)</span>
            <select v-model="oauthClientForm.status">
              <option value="ACTIVE">ACTIVE — 启用,OAuth 正常发 token,功能可用</option>
              <option value="DISABLED">DISABLED — 禁用,OAuth 拒发 token,功能不可用</option>
            </select>
          </label>
          <label style="flex-direction: row; gap: 8px; align-items: center;">
            <input type="checkbox" v-model="oauthClientForm.displayEnabled" />
            <span>
              <strong>显示 AI 按钮</strong> ·
              <span class="muted" style="font-size:12px;">
                关掉 → xmap 嵌入端 AI 按钮 v-if 隐藏(功能不受影响,console 调 <code>__forceShowAi(true)</code> 仍可强显)。
                跟"状态(功能开关)"双管:状态=DISABLED 时无论这里开关如何,按钮都藏 + 功能禁用。
              </span>
            </span>
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
        <div class="recent-drawer-search">
          <input v-model="recentSessionsFilter.keyword"
                 placeholder="搜索 标题 / sessionId / agentId" />
          <span class="recent-drawer-search-stat">共 {{ recentSessionsTotal }} 条</span>
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
          <div v-if="!recentSessions.length && !recentSessionsTotal" class="empty-state compact">
            {{ recentSessionsFilter.keyword ? '没有匹配的会话。' : '运行后会在这里保留最近会话。' }}
          </div>
        </div>
        <!-- 分页器:跟其他列表统一样式,贴抽屉底部 -->
        <div v-if="recentSessionsTotal > 0" class="list-pager recent-drawer-pager">
          <button @click="recentSessionsPage.page = Math.max(0, recentSessionsPage.page - 1)"
                  :disabled="recentSessionsPage.page === 0">上一页</button>
          <span>第 {{ recentSessionsPage.page + 1 }} / {{ recentSessionsTotalPages }} 页</span>
          <button @click="recentSessionsPage.page = Math.min(recentSessionsTotalPages - 1, recentSessionsPage.page + 1)"
                  :disabled="recentSessionsPage.page >= recentSessionsTotalPages - 1">下一页</button>
          <span style="margin-left:auto;">每页:</span>
          <select v-model.number="recentSessionsPage.size">
            <option :value="15">15</option>
            <option :value="30">30</option>
            <option :value="60">60</option>
          </select>
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
          <ScopeEditor v-model="llmForm.scope" />
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
          <ScopeEditor v-model="knowledgeForm.scope" />
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
          <ScopeEditor v-model="toolForm.scope" />
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
          <ScopeEditor v-model="skillForm.scope" />
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
          <ScopeEditor v-model="datasourceForm.scope" />
          <div class="dock-actions">
            <button class="primary" @click="submitDatasource">保存数据源</button>
            <button @click="closeCatalogModal">取消</button>
          </div>
        </template>
      </div>
    </div>

    <!-- embed 模式下走 xmap 真实地图绘制 —— 这是个轻量"挑形状"选择器,
         用户挑完直接调 host xmap.draw,xmap 那边激活地图绘制工具,等用户画完再回传 -->
    <div v-if="embedDrawPicker.visible" class="modal-backdrop" @click.self="closeEmbedDrawPicker">
      <div class="modal-card" style="width:380px;max-width:90vw;">
        <div class="modal-head">
          <h3>在地图上绘制区域</h3>
          <button @click="closeEmbedDrawPicker" :disabled="embedDrawPicker.busy">关闭</button>
        </div>
        <div style="padding:16px 20px;font-size:14px;color:#374151;line-height:1.6;">
          <p v-if="!embedDrawPicker.busy && !embedDrawPicker.error" style="margin:0 0 12px;">
            选择形状后,请在地图上画 ——
            完成后会自动作为附件附到当前消息。
          </p>
          <p v-if="embedDrawPicker.busy" style="margin:0 0 12px;color:#2563eb;">
            正在等待你在 xmap 地图上绘制 <strong>{{ embedDrawPicker.busyType }}</strong> ……
          </p>
          <p v-if="embedDrawPicker.error" style="margin:0 0 12px;color:#b91c1c;">
            出错:{{ embedDrawPicker.error }}
          </p>
          <div style="display:grid;grid-template-columns:repeat(2,1fr);gap:8px;">
            <button
              v-for="t in ['多边形', '矩形', '圆形', '点']"
              :key="t"
              class="primary"
              style="padding:10px 0;"
              :disabled="embedDrawPicker.busy"
              @click="embedDrawShape(t)"
            >{{ embedDrawPicker.busyType === t ? '等待绘制…' : t }}</button>
          </div>
        </div>
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
