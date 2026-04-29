(function (global) {
  function normalizeContainer(target) {
    if (target instanceof HTMLElement) {
      return target;
    }
    if (typeof target === "string" && target.trim()) {
      const resolved = document.querySelector(target);
      if (resolved instanceof HTMLElement) {
        return resolved;
      }
    }
    return null;
  }

  function createFloatingContainer() {
    const root = document.createElement("div");
    root.style.position = "fixed";
    root.style.top = "0";
    root.style.right = "0";
    root.style.bottom = "0";
    root.style.width = "33.3333vw";
    root.style.minWidth = "320px";
    root.style.height = "100vh";
    root.style.zIndex = "2147482000";
    root.style.borderLeft = "1px solid #ced8e7";
    root.style.background = "#fff";
    root.style.boxShadow = "-8px 0 24px rgba(16, 32, 58, 0.18)";
    document.body.appendChild(root);
    return root;
  }

  // (旧的 createHideBar 已删除——隐藏按钮搬到了 iframe 内左上角,由 App.vue 渲染并经 postMessage 通知父 SDK)

  // 把宿主方法返回值压成 postMessage 能 structured-clone 的纯 JSON 数据。
  // 宿主常见返回:Vue reactive Proxy / OL Feature / 带 getter 的对象 / 偶尔的循环引用。
  // 直接 postMessage 这类对象会 DataCloneError,而 SDK 旧实现把这条错吃掉,
  // 表现是"invoke 看似执行成功,但 iframe 永远收不到 result"。这里做一次安全转换。
  function sanitizeForPostMessage(value) {
    if (value == null) return {};
    try {
      return JSON.parse(JSON.stringify(value));
    } catch (e) {
      // 循环引用走到这里。把能拿到的字段拍平,实在不行返回错误占位。
      try {
        const seen = new WeakSet();
        const cleaned = JSON.stringify(value, (k, v) => {
          if (typeof v === "function") return undefined;
          if (typeof v === "symbol") return undefined;
          if (v && typeof v === "object") {
            if (seen.has(v)) return "[Circular]";
            seen.add(v);
          }
          return v;
        });
        return JSON.parse(cleaned);
      } catch (e2) {
        return { _serializeError: String(e2?.message || e2) };
      }
    }
  }

  function buildIframeSrc(baseUrl, options) {
    const normalizedBase = (baseUrl || window.location.origin).replace(/\/+$/, "");
    // iframe 直接装载主控台,?embed=1 触发精简布局,token 由 postMessage 注入
    const params = new URLSearchParams({ embed: "1" });
    if (options.agentId) params.set("agentId", options.agentId);
    if (options.userId) params.set("userId", options.userId);
    return `${normalizedBase}/chat?${params.toString()}`;
  }

  // client_credentials 模式:SDK 拿 clientId+clientSecret 直接调 /oauth/token 换 access_token。
  // 第三方页面 → 我们域 是跨域,但 form-urlencoded POST 是 simple request,后端 OauthCorsFilter 已放行。
  // 同时把宿主侧的 userId / userName 一起传过去 —— 后端找不到就自动开通该 (tenant, externalUser)。
  async function fetchAccessToken(baseUrl, clientId, clientSecret, userId, userName) {
    const body = new URLSearchParams({
      grant_type: "client_credentials",
      client_id: clientId,
      client_secret: clientSecret
    });
    if (userId) body.set("user_id", userId);
    if (userName) body.set("user_name", userName);
    const res = await fetch(`${baseUrl}/oauth/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString()
    });
    const text = await res.text();
    if (!res.ok) {
      let detail = text;
      try { detail = JSON.parse(text).error_description || text; } catch (_) { /* ignore */ }
      throw new Error(`oauth token failed (${res.status}): ${detail}`);
    }
    let parsed;
    try { parsed = JSON.parse(text); } catch (_) {
      throw new Error("oauth token response is not JSON: " + text);
    }
    if (!parsed.access_token) throw new Error("oauth token response missing access_token");
    return {
      accessToken: parsed.access_token,
      tokenType: parsed.token_type || "Bearer",
      expiresIn: parsed.expires_in || 7200
    };
  }

  class JavaClawChatWindow {
    constructor(options) {
      this.options = Object.assign({
        baseUrl: window.location.origin,
        methods: {},
        hostOrigin: "*"
      }, options || {});
      this.methods = Object.assign({}, this.options.methods || {});
      this.onEvent = typeof this.options.onEvent === "function" ? this.options.onEvent : function () {};
      this.onReady = typeof this.options.onReady === "function" ? this.options.onReady : function () {};
      this.onSessionCreated = typeof this.options.onSessionCreated === "function" ? this.options.onSessionCreated : function () {};

      const container = normalizeContainer(this.options.container) || createFloatingContainer();
      this.container = container;
      this.iframe = document.createElement("iframe");
      this.iframe.style.width = "100%";
      this.iframe.style.height = "100%";
      this.iframe.style.flex = "1 1 auto";
      this.iframe.style.minHeight = "0";
      this.iframe.style.border = "0";
      this.iframe.referrerPolicy = "no-referrer";
      this.iframe.src = buildIframeSrc(this.options.baseUrl, this.options);
      this.container.appendChild(this.iframe);
      // 隐藏按钮放在 iframe 内部(由 App.vue 的 embed 模式渲染),按钮点了向父发
      // jc.embed.hide,我们在这里把容器藏掉。不再在外层挂底部 bar 占地方。

      // 拿 token 的两条路径(优先级:外部直接传入 → SDK 自己换):
      // 1) accessToken 直接给 —— 第三方后端拿到 token 后塞进 SDK init,secret 不暴露在浏览器
      // 2) clientId + clientSecret 给 SDK,SDK 在 init 阶段调 /oauth/token 拿 token
      this.tokenInfo = null;
      this.tokenReady = this.resolveAccessToken().catch((error) => {
        this.onEvent({
          type: "jc.embed.error",
          payload: { stage: "oauth.token", message: String(error?.message || error) }
        });
        throw error;
      });

      // 跨 iframe 双向通信架构:
      //   父→子 init/registerMethods/send 等控制消息: window.postMessage(已验证可达)
      //   子→父 invoke 转发:                          window.parent.postMessage(已验证可达)
      //   父→后端 invoke_result 回写:                 HTTP POST /api/bridge/invoke-result
      //
      // 之前 invoke_result 走父→子 postMessage → 子→后端 ws,这条父→子的 postMessage
      // 会被一些浏览器扩展(Vue Devtools 等)劫持后丢消息,导致 LLM 端 15 秒超时。
      // 现在 SDK 自己手上有 access_token 和 sessionId(从 jc.embed.ready 拿),直接绕开
      // iframe 走 HTTP —— SDK 跟 iframe 是同一个 backend,token 同样能用。
      this.sessionId = "";
      this.boundMessageHandler = this.handleMessage.bind(this);
      window.addEventListener("message", this.boundMessageHandler);
      this.iframe.addEventListener("load", async () => {
        console.log("[javaClawSDK] iframe.load fired, awaiting tokenReady…");
        let accessToken = "";
        try {
          await this.tokenReady;
          accessToken = this.tokenInfo?.accessToken || "";
          console.log("[javaClawSDK] token resolved, length=", accessToken.length);
        } catch (error) {
          console.error("[javaClawSDK] token failed:", error);
        }
        const initPayload = {
          agentId: this.options.agentId || "",
          userId: this.options.userId || "anonymous",
          hostOrigin: this.options.hostOrigin || "*",
          methods: Object.keys(this.methods),
          accessToken: accessToken,
          baseUrl: (this.options.baseUrl || window.location.origin).replace(/\/+$/, "")
        };
        console.log("[javaClawSDK] posting jc.embed.init to iframe", {
          hasContentWindow: !!this.iframe?.contentWindow,
          tokenLen: accessToken.length,
          userId: initPayload.userId
        });
        this.post("jc.embed.init", initPayload);
      });
    }

    async resolveAccessToken() {
      if (this.options.accessToken) {
        this.tokenInfo = {
          accessToken: this.options.accessToken,
          tokenType: "Bearer",
          expiresIn: this.options.expiresIn || 7200
        };
        return this.tokenInfo;
      }
      if (this.options.clientId && this.options.clientSecret) {
        const baseUrl = (this.options.baseUrl || window.location.origin).replace(/\/+$/, "");
        this.tokenInfo = await fetchAccessToken(
          baseUrl,
          this.options.clientId,
          this.options.clientSecret,
          this.options.userId || "",
          this.options.userName || ""
        );
        return this.tokenInfo;
      }
      throw new Error("missing accessToken or clientId/clientSecret in init options");
    }

    post(type, payload) {
      const cw = this.iframe?.contentWindow;
      if (!cw) {
        console.error("[javaClawSDK.post] iframe.contentWindow is null, dropping", type,
          "iframe in DOM=", !!(this.iframe && this.iframe.isConnected));
        return;
      }
      try {
        cw.postMessage({ type, payload }, "*");
      } catch (err) {
        console.error("[javaClawSDK.post] postMessage threw, dropping", type, err);
      }
    }

    // 把 invoke_result 通过 HTTP 直接回传给后端 —— 这条路径完全不经 iframe,
    // 不依赖 window.postMessage,浏览器扩展无从干预。
    // 需要的两样东西都在 SDK 自己手上:
    //   - sessionId: jc.embed.ready 时 iframe 把它捎过来
    //   - access_token: SDK 自己用 client_credentials 换的
    sendInvokeResult(requestId, success, result, error) {
      if (!requestId) {
        console.error("[javaClawSDK.invoke_result] missing requestId, drop");
        return;
      }
      const baseUrl = (this.options.baseUrl || window.location.origin).replace(/\/+$/, "");
      const token = this.tokenInfo?.accessToken || "";
      const sessionId = this.sessionId || "";
      if (!token || !sessionId) {
        console.error("[javaClawSDK.invoke_result] no token or sessionId, drop",
          { hasToken: !!token, sessionId });
        return;
      }
      const body = JSON.stringify({
        sessionId,
        requestId,
        success: !!success,
        result: result == null ? {} : result,
        error: error || ""
      });
      // fetch 用 keepalive 避免 SDK 在 invoke 完成后立刻 unload 时把请求带走
      fetch(`${baseUrl}/api/bridge/invoke-result`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${token}`
        },
        body,
        credentials: "omit",
        keepalive: true
      }).then((res) => {
        if (!res.ok) {
          return res.text().then((t) => {
            console.error("[javaClawSDK.invoke_result] HTTP", res.status, t);
          });
        }
        console.log("[javaClawSDK.invoke_result] HTTP ok", { requestId, success });
      }).catch((err) => {
        console.error("[javaClawSDK.invoke_result] fetch failed", err);
      });
    }

    handleMessage(event) {
      const fromOurIframe = event.source === this.iframe?.contentWindow;
      const data = event.data;
      if (!data || typeof data !== "object" || !data.type) {
        return;
      }
      if (!fromOurIframe) {
        return;
      }
      if (data.type === "jc.embed.ready") {
        // iframe 准备好了,会带上 sessionId。SDK 把它存下来,后面 invoke_result 回写要用。
        if (data.sessionId) {
          this.sessionId = data.sessionId;
          console.log("[javaClawSDK] sessionId captured:", this.sessionId);
        }
        this.onReady(data);
        return;
      }
      if (data.type === "jc.embed.session_created") {
        // 会话切换/新建时也更新 sessionId
        if (data.sessionId) {
          this.sessionId = data.sessionId;
        }
        this.onSessionCreated(data);
        return;
      }
      if (data.type === "jc.embed.hide") {
        // iframe 内部的隐藏按钮 / 绘制流程开始时发来,直接藏整个浮动容器
        if (this.container) this.container.style.display = "none";
        return;
      }
      if (data.type === "jc.embed.show") {
        // iframe 主动请求显示 —— 绘制流程结束后用,跟 hide 配对
        if (this.container) this.container.style.display = "";
        // 通知 iframe "我现在被显示了",让它趁机刷一次 LLM/agents 等列表 ——
        // 宿主页可能在 iframe 隐藏期间改过后台配置(新建 LLM、禁用某 agent 等),
        // 用户这次打开看到的应当是最新视图。
        this.post("jc.embed.parent_visible", {});
        return;
      }
      if (data.type === "jc.embed.event") {
        this.onEvent(data);
        return;
      }
      if (data.type === "jc.embed.error") {
        this.onEvent(data);
        return;
      }
      if (data.type === "jc.embed.host_invoke") {
        // iframe 内部代码主动调宿主方法的通道(跟 LLM 走的 jc.embed.invoke 是两条独立路径)。
        // LLM 那条 result 走 HTTP /api/bridge/invoke-result;这条 iframe 直接发起的请求
        // 没有 requestId 对账后端,直接通过 postMessage 把 result 回到 iframe 即可。
        const requestId = data.requestId;
        const methodName = data.method;
        const ai = (typeof window !== "undefined") ? window.AI : null;
        const replyError = (msg) => {
          const cw = this.iframe?.contentWindow;
          if (!cw) return;
          try {
            cw.postMessage({
              type: "jc.embed.host_invoke.result",
              requestId,
              success: false,
              result: {},
              error: msg
            }, "*");
          } catch (err) {
            console.error("[javaClawSDK.host_invoke] reply postMessage threw", err);
          }
        };
        if (!ai || typeof ai.invoke !== "function") {
          replyError("window.AI.invoke is not available on host");
          return;
        }
        if (typeof ai.hasMethod === "function" && !ai.hasMethod(methodName)) {
          replyError(`host method not found: ${methodName}`);
          return;
        }
        Promise.resolve(ai.invoke(methodName, data.payload || {}))
          .then((result) => {
            const safe = sanitizeForPostMessage(result);
            const cw = this.iframe?.contentWindow;
            if (!cw) return;
            cw.postMessage({
              type: "jc.embed.host_invoke.result",
              requestId,
              success: true,
              result: safe
            }, "*");
          })
          .catch((error) => {
            replyError(String(error?.message || error || "host method failed"));
          });
        return;
      }
      if (data.type === "jc.embed.invoke") {
        const payload = data;
        const methodName = payload.method;
        const requestId = payload.requestId;
        // iframe 在转发 invoke 时把当前 sessionId 也捎过来了,这条更新鲜可靠,
        // 比 ready 时拿到的快照要权威 —— 用户在 iframe 里切了会话时也能对得上。
        if (payload.sessionId) {
          this.sessionId = payload.sessionId;
        }
        // 直接走 window.AI.invoke —— xmap 已经把所有方法注册到 window.AI/runtimeRegistry,
        // SDK 跟它同 window,没必要再维护一份 this.methods 二次转发。
        const ai = (typeof window !== "undefined") ? window.AI : null;
        if (!ai || typeof ai.invoke !== "function") {
          console.error("[javaClawSDK.invoke] window.AI.invoke 不可用,宿主未注册 AI 入口");
          this.sendInvokeResult(requestId, false, null, "window.AI.invoke is not available on host");
          return;
        }
        if (typeof ai.hasMethod === "function" && !ai.hasMethod(methodName)) {
          console.warn("[javaClawSDK.invoke] method not found", methodName,
            "available:", typeof ai.listMethods === "function" ? ai.listMethods() : "(unknown)");
          this.sendInvokeResult(requestId, false, null, `host method not found: ${methodName}`);
          return;
        }
        // LLM 触发 draw 时,xmap.draw 会进入"等用户在地图上画"的阻塞流程,通常持续几秒到几十秒。
        // 这期间聊天浮动容器挡住地图操作区,先收起;无论成功/失败/异常都得恢复显示,否则
        // 用户会卡在没有聊天面板的状态里。和 App.vue 里 triggerEmbedDraw() 的 hide/show 行为一致,
        // 区别只是这条路径是 LLM 主动发起,不是用户从聊天面板的 draw picker 点的。
        const isDrawMethod = methodName === "draw";
        const restoreContainer = () => {
          if (isDrawMethod && this.container) {
            this.container.style.display = "";
          }
        };
        if (isDrawMethod && this.container) {
          this.container.style.display = "none";
        }
        Promise.resolve(ai.invoke(methodName, payload.payload || {}))
          .then((result) => {
            const safeResult = sanitizeForPostMessage(result);
            console.log("[javaClawSDK.invoke] success", methodName, safeResult);
            this.sendInvokeResult(requestId, true, safeResult, null);
          })
          .catch((error) => {
            console.error("[javaClawSDK.invoke] error", methodName, error);
            this.sendInvokeResult(requestId, false, null,
              String(error?.message || error || "host method failed"));
          })
          .finally(restoreContainer);
      }
    }

    sendMessage(message, options) {
      this.post("jc.embed.send", Object.assign({ message: message || "" }, options || {}));
    }

    /** 宿主页主动展开 AI 面板。除了改显示状态,还会通知 iframe 刷新 LLM/agents 列表。 */
    show() {
      if (this.container) this.container.style.display = "";
      this.post("jc.embed.parent_visible", {});
    }

    /** 宿主页主动收起 AI 面板。 */
    hide() {
      if (this.container) this.container.style.display = "none";
    }

    registerMethod(name, fn) {
      if (!name || typeof fn !== "function") {
        return;
      }
      this.methods[name] = fn;
      this.post("jc.embed.registerMethods", { methods: Object.keys(this.methods) });
    }

    registerMethods(methodMap) {
      if (!methodMap || typeof methodMap !== "object") {
        return;
      }
      Object.keys(methodMap).forEach((name) => {
        if (typeof methodMap[name] === "function") {
          this.methods[name] = methodMap[name];
        }
      });
      this.post("jc.embed.registerMethods", { methods: Object.keys(this.methods) });
    }

    destroy() {
      window.removeEventListener("message", this.boundMessageHandler);
      if (this.iframe && this.iframe.parentNode) {
        this.iframe.parentNode.removeChild(this.iframe);
      }
      if (this.container && this.options.container == null && this.container.parentNode) {
        this.container.parentNode.removeChild(this.container);
      }
    }
  }

  global.JavaClawEmbed = {
    createChatWindow(options) {
      return new JavaClawChatWindow(options || {});
    },
    initRightPanel(options) {
      return new JavaClawChatWindow(Object.assign({
        container: null
      }, options || {}));
    },
    initIframeChat(options) {
      return new JavaClawChatWindow(options || {});
    }
  };
})(window);
