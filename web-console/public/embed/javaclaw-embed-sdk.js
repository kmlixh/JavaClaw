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
    root.style.right = "20px";
    root.style.bottom = "20px";
    root.style.width = "420px";
    root.style.height = "640px";
    root.style.zIndex = "2147482000";
    root.style.border = "1px solid #ced8e7";
    root.style.borderRadius = "12px";
    root.style.overflow = "hidden";
    root.style.boxShadow = "0 14px 40px rgba(16, 32, 58, 0.24)";
    root.style.background = "#fff";
    document.body.appendChild(root);
    return root;
  }

  function buildIframeSrc(baseUrl, options) {
    const normalizedBase = (baseUrl || window.location.origin).replace(/\/+$/, "");
    const params = new URLSearchParams();
    if (options.agentId) params.set("agentId", options.agentId);
    if (options.userId) params.set("userId", options.userId);
    const suffix = params.toString() ? `?${params.toString()}` : "";
    return `${normalizedBase}/embed/chat.html${suffix}`;
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
      this.iframe.style.border = "0";
      this.iframe.referrerPolicy = "no-referrer";
      this.iframe.src = buildIframeSrc(this.options.baseUrl, this.options);
      this.container.appendChild(this.iframe);

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

      this.boundMessageHandler = this.handleMessage.bind(this);
      window.addEventListener("message", this.boundMessageHandler);
      this.iframe.addEventListener("load", async () => {
        // iframe DOM 加载完了,但要等 token 就位再发 init —— iframe 收到 init 才会开始 loadAgents 等。
        let accessToken = "";
        try {
          await this.tokenReady;
          accessToken = this.tokenInfo?.accessToken || "";
        } catch (_) { /* error 已经经 onEvent 上报 */ }
        this.post("jc.embed.init", {
          agentId: this.options.agentId || "",
          userId: this.options.userId || "anonymous",
          hostOrigin: this.options.hostOrigin || "*",
          methods: Object.keys(this.methods),
          accessToken: accessToken,
          baseUrl: (this.options.baseUrl || window.location.origin).replace(/\/+$/, "")
        });
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
      if (!this.iframe?.contentWindow) return;
      this.iframe.contentWindow.postMessage({ type, payload }, "*");
    }

    handleMessage(event) {
      if (event.source !== this.iframe?.contentWindow) {
        return;
      }
      const data = event.data;
      if (!data || typeof data !== "object" || !data.type) {
        return;
      }
      if (data.type === "jc.embed.ready") {
        this.onReady(data);
        return;
      }
      if (data.type === "jc.embed.session_created") {
        this.onSessionCreated(data);
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
      if (data.type === "jc.embed.invoke") {
        const payload = data;
        const methodName = payload.method;
        const fn = this.methods[methodName];
        if (typeof fn !== "function") {
          this.post("jc.embed.invoke.result", {
            requestId: payload.requestId,
            success: false,
            result: {},
            error: `host method not found: ${methodName}`
          });
          return;
        }
        Promise.resolve()
          .then(() => fn(payload.payload || {}, payload))
          .then((result) => {
            this.post("jc.embed.invoke.result", {
              requestId: payload.requestId,
              success: true,
              result: result == null ? {} : result
            });
          })
          .catch((error) => {
            this.post("jc.embed.invoke.result", {
              requestId: payload.requestId,
              success: false,
              result: {},
              error: String(error?.message || error || "host method failed")
            });
          });
      }
    }

    sendMessage(message, options) {
      this.post("jc.embed.send", Object.assign({ message: message || "" }, options || {}));
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
