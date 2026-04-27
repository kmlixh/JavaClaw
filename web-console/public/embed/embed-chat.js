(function () {
  const dom = {
    chatLog: document.getElementById("chatLog"),
    chatForm: document.getElementById("chatForm"),
    chatInput: document.getElementById("chatInput"),
    sendBtn: document.getElementById("sendBtn"),
    sessionLabel: document.getElementById("sessionLabel"),
    statusLabel: document.getElementById("statusLabel"),
    agentSelect: document.getElementById("agentSelect"),
    llmConfigSelect: document.getElementById("llmConfigSelect"),
    llmModelSelect: document.getElementById("llmModelSelect")
  };

  const state = {
    config: {
      agentId: "",
      llmConfigId: "",
      llmModel: "",
      userId: "anonymous",
      hostOrigin: "*"
    },
    sessionId: "",
    currentRunId: "",
    methods: [],
    agents: [],
    llmConfigs: [],
    chatSocket: null,
    bridgeSocket: null,
    liveAssistantText: "",
    sending: false,
    // OAuth client_credentials 模式:SDK 在 init 时把 token 塞进来,所有 fetch / WS 必须带它
    accessToken: "",
    bootstrapDone: false
  };

  // 给所有 /api 请求自动加 Authorization: Bearer。token 不在时(未初始化完)直接抛,调用方有兜底。
  function authedFetch(url, options) {
    if (!state.accessToken) {
      return Promise.reject(new Error("access token not yet available"));
    }
    const opts = Object.assign({ headers: {} }, options || {});
    opts.headers = Object.assign({}, opts.headers, {
      Authorization: `Bearer ${state.accessToken}`
    });
    return fetch(url, opts);
  }

  // WebSocket 不能设 header,token 走 query —— 后端 JwtAuthWebFilter 对 /ws/* 路径会读 ?access_token=
  function attachWsToken(params) {
    if (state.accessToken) params.set("access_token", state.accessToken);
    return params;
  }

  function parseQuery() {
    const params = new URLSearchParams(window.location.search || "");
    return {
      agentId: params.get("agentId") || "",
      userId: params.get("userId") || "anonymous"
    };
  }

  function postToHost(type, payload) {
    const message = Object.assign({ type }, payload || {});
    window.parent?.postMessage(message, state.config.hostOrigin || "*");
  }

  function appendBubble(role, content) {
    const item = document.createElement("div");
    item.className = `bubble ${role}`;
    item.textContent = content;
    dom.chatLog.appendChild(item);
    dom.chatLog.scrollTop = dom.chatLog.scrollHeight;
    return item;
  }

  function setSessionLabel() {
    dom.sessionLabel.textContent = `session: ${state.sessionId || "(new)"}`;
  }

  function setStatusLabel(text) {
    dom.statusLabel.textContent = text || "ready";
  }

  function setSending(flag) {
    state.sending = !!flag;
    dom.sendBtn.disabled = state.sending;
  }

  function normalizeMethods(rawMethods) {
    if (Array.isArray(rawMethods)) {
      return rawMethods.map((item) => String(item || "").trim()).filter(Boolean);
    }
    if (rawMethods && typeof rawMethods === "object") {
      return Object.keys(rawMethods).map((name) => name.trim()).filter(Boolean);
    }
    return [];
  }

  function syncAgentOptions() {
    dom.agentSelect.innerHTML = "";
    if (!state.agents.length) {
      const option = document.createElement("option");
      option.value = "";
      option.textContent = "无可用Agent";
      dom.agentSelect.appendChild(option);
      dom.agentSelect.disabled = true;
      state.config.agentId = "";
      return;
    }
    state.agents.forEach((item) => {
      const option = document.createElement("option");
      option.value = item.id;
      option.textContent = item.displayName || item.id;
      dom.agentSelect.appendChild(option);
    });
    dom.agentSelect.disabled = false;
    const targetId = state.agents.some((item) => item.id === state.config.agentId)
      ? state.config.agentId
      : state.agents[0].id;
    dom.agentSelect.value = targetId;
    state.config.agentId = targetId;
  }

  async function loadAgents() {
    try {
      const list = await authedFetch("/api/agents").then(async (response) => {
        if (!response.ok) {
          const text = await response.text();
          throw new Error(text || `HTTP ${response.status}`);
        }
        return response.json();
      });
      state.agents = Array.isArray(list) ? list : [];
      syncAgentOptions();
    } catch (error) {
      state.agents = [];
      syncAgentOptions();
      appendBubble("system", `加载Agent失败: ${error.message || error}`);
    }
  }

  function hostMethodsAttachment() {
    const payload = {
      channel: "host.invoke",
      usage: "Call host.invoke with method and payload JSON.",
      methods: state.methods
    };
    return {
      name: "host_methods.json",
      contentType: "application/json",
      content: JSON.stringify(payload)
    };
  }

  function parseModelMappings(modelMappingJson, fallbackModel) {
    const fallback = String(fallbackModel || "").trim();
    if (!modelMappingJson) {
      return fallback ? [{ displayName: fallback, apiModel: fallback }] : [];
    }
    try {
      const root = JSON.parse(modelMappingJson);
      const rows = Array.isArray(root?.models) ? root.models : (Array.isArray(root) ? root : []);
      const mapped = rows.map((item) => {
        const displayName = String(item?.displayName || item?.apiModel || "").trim();
        const apiModel = String(item?.apiModel || item?.displayName || "").trim();
        return { displayName, apiModel };
      }).filter((item) => item.apiModel);
      if (mapped.length) return mapped;
    } catch (error) {
      // ignore
    }
    return fallback ? [{ displayName: fallback, apiModel: fallback }] : [];
  }

  function currentLlmConfig() {
    return state.llmConfigs.find((item) => item.id === state.config.llmConfigId) || null;
  }

  function syncModelOptions() {
    const config = currentLlmConfig();
    const models = config ? parseModelMappings(config.modelMappingJson, config.model) : [];
    dom.llmModelSelect.innerHTML = "";
    if (!models.length) {
      const option = document.createElement("option");
      option.value = "";
      option.textContent = "无可用模型";
      dom.llmModelSelect.appendChild(option);
      dom.llmModelSelect.disabled = true;
      state.config.llmModel = "";
      return;
    }
    models.forEach((item) => {
      const option = document.createElement("option");
      option.value = item.apiModel;
      option.textContent = item.displayName || item.apiModel;
      dom.llmModelSelect.appendChild(option);
    });
    dom.llmModelSelect.disabled = false;
    const targetModel = models.some((item) => item.apiModel === state.config.llmModel)
      ? state.config.llmModel
      : models[0].apiModel;
    dom.llmModelSelect.value = targetModel;
    state.config.llmModel = targetModel;
  }

  function syncLlmConfigOptions() {
    dom.llmConfigSelect.innerHTML = "";
    if (!state.llmConfigs.length) {
      const option = document.createElement("option");
      option.value = "";
      option.textContent = "无可用LLM";
      dom.llmConfigSelect.appendChild(option);
      dom.llmConfigSelect.disabled = true;
      state.config.llmConfigId = "";
      syncModelOptions();
      return;
    }
    state.llmConfigs.forEach((item) => {
      const option = document.createElement("option");
      option.value = item.id;
      option.textContent = item.displayName || item.id;
      dom.llmConfigSelect.appendChild(option);
    });
    dom.llmConfigSelect.disabled = false;
    const targetId = state.llmConfigs.some((item) => item.id === state.config.llmConfigId)
      ? state.config.llmConfigId
      : (state.llmConfigs.find((item) => item.defaultConfig) || state.llmConfigs[0]).id;
    dom.llmConfigSelect.value = targetId;
    state.config.llmConfigId = targetId;
    syncModelOptions();
  }

  async function loadLlmConfigs() {
    try {
      const list = await authedFetch("/api/admin/llms").then(async (response) => {
        if (!response.ok) {
          const text = await response.text();
          throw new Error(text || `HTTP ${response.status}`);
        }
        return response.json();
      });
      state.llmConfigs = Array.isArray(list)
        ? list.filter((item) => item && item.enabled !== false)
        : [];
      syncLlmConfigOptions();
    } catch (error) {
      state.llmConfigs = [];
      syncLlmConfigOptions();
      appendBubble("system", `加载LLM配置失败: ${error.message || error}`);
    }
  }

  async function sendChat(message, options) {
    if (!message || !message.trim() || state.sending) {
      return;
    }
    const normalizedMessage = message.trim();
    const extra = options || {};
    appendBubble("user", normalizedMessage);
    setSending(true);
    try {
      const payload = {
        sessionId: state.sessionId || undefined,
        agentId: state.config.agentId || undefined,
        userId: state.config.userId || "anonymous",
        llmConfigId: state.config.llmConfigId || undefined,
        llmModel: state.config.llmModel || undefined,
        message: normalizedMessage,
        references: Array.isArray(extra.references) ? extra.references : [],
        attachments: [
          ...(Array.isArray(extra.attachments) ? extra.attachments : []),
          hostMethodsAttachment()
        ]
      };
      const accepted = await authedFetch("/api/chat/send", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      }).then(async (response) => {
        if (!response.ok) {
          const text = await response.text();
          throw new Error(text || `HTTP ${response.status}`);
        }
        return response.json();
      });

      const newSession = !state.sessionId;
      state.sessionId = accepted.sessionId || state.sessionId;
      state.currentRunId = accepted.runId || "";
      setSessionLabel();
      if (newSession) {
        openBridgeSocket();
        postToHost("jc.embed.session_created", {
          sessionId: state.sessionId,
          agentId: accepted.agentId || state.config.agentId || ""
        });
      }
      openChatSocket({
        sessionId: state.sessionId,
        runId: accepted.runId,
        message: normalizedMessage
      });
      setStatusLabel("running");
      postToHost("jc.embed.run_started", {
        sessionId: state.sessionId,
        runId: accepted.runId
      });
    } catch (error) {
      appendBubble("system", `发送失败: ${error.message || error}`);
      setStatusLabel("error");
      postToHost("jc.embed.error", { message: String(error?.message || error || "send failed") });
    } finally {
      setSending(false);
    }
  }

  function resetSessionForConfigChange(reason) {
    if (state.chatSocket) {
      try {
        state.chatSocket.close();
      } catch (error) {
        // ignore
      }
      state.chatSocket = null;
    }
    if (state.bridgeSocket) {
      try {
        state.bridgeSocket.close();
      } catch (error) {
        // ignore
      }
      state.bridgeSocket = null;
    }
    state.sessionId = "";
    state.currentRunId = "";
    setSessionLabel();
    setStatusLabel("ready");
    if (reason) {
      appendBubble("system", reason);
    }
  }

  function openChatSocket(context) {
    if (state.chatSocket) {
      try {
        state.chatSocket.close();
      } catch (error) {
        // ignore
      }
    }
    state.liveAssistantText = "";
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const params = new URLSearchParams({
      sessionId: context.sessionId,
      userId: state.config.userId || "anonymous",
      message: context.message || ""
    });
    if (state.config.agentId) params.set("agentId", state.config.agentId);
    if (state.config.llmConfigId) params.set("llmConfigId", state.config.llmConfigId);
    if (state.config.llmModel) params.set("llmModel", state.config.llmModel);
    if (context.runId) params.set("runId", context.runId);
    attachWsToken(params);
    const url = `${protocol}//${window.location.host}/ws/chat?${params.toString()}`;
    const socket = new WebSocket(url);
    state.chatSocket = socket;

    let liveBubble = null;

    socket.onmessage = (event) => {
      let payload = null;
      try {
        payload = JSON.parse(event.data);
      } catch (error) {
        return;
      }
      postToHost("jc.embed.event", payload);
      if (payload.type === "TOKEN_DELTA") {
        if (!liveBubble) {
          liveBubble = appendBubble("assistant", "");
        }
        state.liveAssistantText += payload.content || "";
        liveBubble.textContent = state.liveAssistantText;
      } else if (payload.type === "RUN_STATUS") {
        setStatusLabel("running");
        appendBubble("system", payload.content || "处理中");
      } else if (payload.type === "RUN_FAILED") {
        setStatusLabel("failed");
        if (payload.content) {
          appendBubble("system", `失败: ${payload.content}`);
        }
      } else if (payload.type === "RUN_COMPLETED") {
        setStatusLabel("completed");
        state.liveAssistantText = "";
      }
    };

    socket.onclose = () => {
      state.chatSocket = null;
    };
  }

  function openBridgeSocket() {
    if (!state.sessionId) {
      return;
    }
    if (state.bridgeSocket) {
      try {
        state.bridgeSocket.close();
      } catch (error) {
        // ignore
      }
    }
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const params = new URLSearchParams({ sessionId: state.sessionId });
    attachWsToken(params);
    const url = `${protocol}//${window.location.host}/ws/bridge?${params.toString()}`;
    const socket = new WebSocket(url);
    state.bridgeSocket = socket;
    socket.onmessage = (event) => {
      let payload = null;
      try {
        payload = JSON.parse(event.data);
      } catch (error) {
        return;
      }
      if (payload.type !== "invoke") {
        return;
      }
      postToHost("jc.embed.invoke", payload);
      if (!window.parent || window.parent === window) {
        socket.send(JSON.stringify({
          type: "invoke_result",
          requestId: payload.requestId,
          success: false,
          result: {},
          error: "host window is unavailable"
        }));
      }
    };
    socket.onclose = () => {
      state.bridgeSocket = null;
    };
  }

  function applyInitConfig(rawConfig) {
    const config = rawConfig || {};
    state.config.agentId = String(config.agentId || state.config.agentId || "");
    state.config.llmConfigId = String(config.llmConfigId || state.config.llmConfigId || "");
    state.config.llmModel = String(config.llmModel || state.config.llmModel || "");
    state.config.userId = String(config.userId || state.config.userId || "anonymous");
    state.config.hostOrigin = String(config.hostOrigin || state.config.hostOrigin || "*");
    state.methods = normalizeMethods(config.methods);
    // SDK 在 init 里塞了 token,iframe 用它去调 /api/* 和 /ws/*
    if (config.accessToken) {
      state.accessToken = String(config.accessToken);
    }
  }

  // 收到 token 后才去拉 agent / LLM —— 之前 onload 立刻拉会 401。只跑一次。
  async function bootstrapWithToken() {
    if (state.bootstrapDone) return;
    if (!state.accessToken) return;
    state.bootstrapDone = true;
    await loadAgents();
    await loadLlmConfigs();
  }

  function handleHostMessage(event) {
    const data = event.data;
    if (!data || typeof data !== "object" || !data.type) {
      return;
    }
    if (state.config.hostOrigin !== "*" && event.origin !== state.config.hostOrigin) {
      return;
    }
    if (data.type === "jc.embed.init") {
      applyInitConfig(data.payload || {});
      // token 就位后才能拉资源,失败也告诉宿主
      bootstrapWithToken().catch((error) => {
        postToHost("jc.embed.error", { stage: "bootstrap", message: String(error?.message || error) });
      });
      postToHost("jc.embed.ready", {
        initialized: true,
        sessionId: state.sessionId || ""
      });
      return;
    }
    if (data.type === "jc.embed.registerMethods") {
      state.methods = normalizeMethods(data.payload?.methods);
      return;
    }
    if (data.type === "jc.embed.send") {
      sendChat(data.payload?.message || "", {
        references: data.payload?.references,
        attachments: data.payload?.attachments
      });
      return;
    }
    if (data.type === "jc.embed.invoke.result") {
      if (!state.bridgeSocket || state.bridgeSocket.readyState !== WebSocket.OPEN) {
        return;
      }
      state.bridgeSocket.send(JSON.stringify({
        type: "invoke_result",
        requestId: data.payload?.requestId || "",
        success: !!data.payload?.success,
        result: data.payload?.result || {},
        error: data.payload?.error || ""
      }));
    }
  }

  dom.chatForm.addEventListener("submit", (event) => {
    event.preventDefault();
    sendChat(dom.chatInput.value || "");
    dom.chatInput.value = "";
  });

  dom.agentSelect.addEventListener("change", () => {
    const nextAgentId = dom.agentSelect.value || "";
    if (nextAgentId === state.config.agentId) {
      return;
    }
    state.config.agentId = nextAgentId;
    resetSessionForConfigChange("Agent 已切换，下一条消息将创建新会话。");
  });

  dom.llmConfigSelect.addEventListener("change", () => {
    state.config.llmConfigId = dom.llmConfigSelect.value || "";
    syncModelOptions();
  });

  dom.llmModelSelect.addEventListener("change", () => {
    state.config.llmModel = dom.llmModelSelect.value || "";
  });

  dom.chatInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      dom.chatForm.requestSubmit();
    }
  });

  window.addEventListener("message", handleHostMessage);

  applyInitConfig(parseQuery());
  setSessionLabel();
  setStatusLabel("waiting-auth");
  // loadAgents / loadLlmConfigs 不再在这里立刻调:必须等 SDK 经 jc.embed.init 把 accessToken 推过来。
  // 兜底:5s 内还没收到 token,提示宿主侧 SDK 没接好(也可能本地直开 chat.html 调试,那种场景看下面 fallback)。
  appendBubble("system", "嵌入页已就绪，等待 SDK 完成 OAuth 鉴权…");
  postToHost("jc.embed.ready", { initialized: false, sessionId: "" });

  // 调试场景:URL query 里允许直接传 access_token=,跳过 SDK 流程方便本地 ?access_token=xxx 验
  (function devFallback() {
    const params = new URLSearchParams(window.location.search || "");
    const direct = params.get("access_token");
    if (direct) {
      state.accessToken = direct;
      bootstrapWithToken().catch((error) => {
        appendBubble("system", `bootstrap 失败: ${error?.message || error}`);
      });
    }
  })();
})();
