# 部署到 http://10.173.108.120:8888/

## 拓扑

```
浏览器 → 10.173.108.120:8888 (nginx)
            ├─ /            → 静态文件 (web-console SPA dist)
            ├─ /api/*       → spring boot 8080
            ├─ /api/chat/stream  ← SSE 长连接,nginx 关 buffer
            ├─ /oauth/*     → spring boot 8080
            ├─ /ws/*        → spring boot 8080 (WebSocket upgrade)
            └─ /embed/*     → spring boot 8080 (SDK 脚本 + iframe HTML)
```

后端 spring 跑在同机 8080;**前端不需要任何代码改动**(所有请求都用相对路径或 `window.location.host`,跟着同源走)。

---

## 1. 后端

### 1.1 配置已就绪

**`application.yml`** (jar 内嵌,通用):

```yaml
server:
  port: 8080
  address: 127.0.0.1                  # 仅本机回环 -- 8080 不暴露公网,外部必须经 nginx
  forward-headers-strategy: native    # 信任 nginx 转发的 X-Forwarded-*
  shutdown: graceful

agent:
  auth:
    anonymous-enabled: false
    cookie-secure: false              # http 部署必须 false;上 https 改 true
```

**`application-prod.yml`** (jar 内嵌,prod profile 激活时加载):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://10.173.108.120:5433/java_claw
    username: postgres
    password: <硬编码>                # 部署 zip 内置;无环境变量依赖
  jpa:
    hibernate.ddl-auto: validate
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 9

agent:
  session-lock:
    type: in-memory
# 不写 agent.llm.* —— LLM 完全从 DB(llm_provider_config 表)读,admin UI 维护。
```

**安全模型**:8080 只接受 127.0.0.1 的请求 → `curl http://10.173.108.120:8080` 从外部连不上,ss/netstat 看到 `127.0.0.1:8080` 而不是 `0.0.0.0:8080`。所有外部流量被强制经 nginx 8888,nginx 这一层可以加 IP 白名单 / 限流 / WAF / TLS 终结。
> ⚠ **跨机部署**:nginx 跟 spring 不在同一台机时,把 `server.address` 改回 `0.0.0.0` 并配防火墙(ufw / iptables)只放行 nginx 所在 ip 访问 8080。

> ⚠ **DB 密码硬编码**:application-prod.yml 直接写 password,jar 内可见。这是为了部署不带任何 secret 文件的简化模型。如果以后要把密码从 jar 抠出来,改回 `${DB_PASSWORD}` + `--spring.config.additional-location=file:/data/javaClaw/` 用同名外部 yaml 覆盖即可,不用改启动脚本。

### 1.2 打包

```bash
./gradlew :agent-app:bootJar -x test
ls agent-app/build/libs/agent-app.jar
```

### 1.3 运行

```bash
java -jar agent-app/build/libs/agent-app.jar \
  --spring.profiles.active=prod \
  --agent.workspace-root=/data/javaClaw/workspaces
```

正式部署用 systemd unit(本包已带 `javaclaw-backend.service`)。

### 1.4 数据库 + workspace + LLM

- DB: `postgres@10.173.108.120:5433/java_claw`,凭据写死在 `application-prod.yml`
- 运行时产物: `/data/javaClaw/workspaces/{agentId}/artifacts/{runId}/...`(systemd unit `--agent.workspace-root` 已注入)
- LLM 配置: 全部通过 admin UI `/llms` 菜单维护,落表 `llm_provider_config`

---

## 2. web-console(前端 SPA)

### 2.1 构建

```bash
cd web-console
npm install
npm run build
# 产物在 web-console/dist/
ls dist/                        # index.html, assets/, embed/ ...
```

### 2.2 部署到 nginx 根目录

```bash
# nginx root = /data/javaClaw/web-console (跟后端 jar 同盘,大容量独立磁盘)
sudo rm -rf /data/javaClaw/web-console
sudo cp -r web-console/dist /data/javaClaw/web-console
sudo chown -R javaclaw:javaclaw /data/javaClaw/web-console
sudo chmod -R u+rwX,g+rX,o+rX /data/javaClaw/web-console
```

> ⚠ `web-console/dist/embed/` 同时也是后端 `EmbedStaticConfig` 通过 `agent.embed.static-dir` 读取的目录。systemd unit 已经传 `--agent.embed.static-dir=/data/javaClaw/web-console/embed/`,所以 jar + dist 放一起即可。

### 2.3 没改 vite.config.js

dev proxy(`localhost:5173` → `localhost:8080`)只在开发模式生效,生产 build 跟它无关。production build 走 nginx 代理,**vite.config.js 不需要改**。

---

## 3. nginx

`deploy/nginx-javaclaw.conf` 是完整 server block。把它丢到 `/etc/nginx/conf.d/` 或者 include 进 `/etc/nginx/sites-enabled/`:

```bash
sudo cp deploy/nginx-javaclaw.conf /etc/nginx/conf.d/javaclaw.conf
sudo nginx -t                # 配置语法校验
sudo systemctl reload nginx
```

确认监听:
```bash
ss -lntp | grep 8888         # 应看到 nginx 监听 *:8888
ss -lntp | grep 8080         # 应看到 java 监听 127.0.0.1:8080 (不是 *:8080)

# 反例校验:从外网/局域网另一台机访问 8080 应该失败,只能访问 8888
curl --max-time 3 http://10.173.108.120:8080/api/apps/xmap/enabled
# 期望: Connection refused / timeout
curl http://10.173.108.120:8888/api/apps/xmap/enabled
# 期望: 200 + JSON
```

---

## 4. 端到端验证

### 4.1 静态页面
```bash
curl -I http://10.173.108.120:8888/
# 期望 HTTP/1.1 200 + Content-Type: text/html
```

### 4.2 后端 API(应用启用接口,无需 token)
```bash
curl http://10.173.108.120:8888/api/apps/xmap/enabled
# 期望 {"appId":"xmap","enabled":true|false}
```

### 4.3 OAuth(模拟 xmap-ol-front 拿 token)
```bash
curl -X POST http://10.173.108.120:8888/oauth/token \
  -d 'grant_type=client_credentials&client_id=xmap&client_secret=...'
# 期望 200 + access_token
```

### 4.4 SSE
浏览器开 devtools,登录后发起一次 chat 看 `/api/chat/stream` 是否持续推送 `data: ...`。

### 4.5 WebSocket
浏览器 console: `new WebSocket('ws://10.173.108.120:8888/ws/chat?...')` 应看到握手成功。

---

## 5. 常见问题

### 5.1 登录后立即 401 / cookie 没有
检查 `agent.auth.cookie-secure=false`(http 部署不能开 Secure cookie)。

### 5.2 SSE 总是几秒后断开
nginx 默认 `proxy_buffering on` 会让 SSE 卡几秒一批,且 60s 默认超时切线程。本配置已 `proxy_buffering off; proxy_read_timeout 3600s`,应不会出现。如果仍卡,看是不是有更外层的反向代理(LB / WAF)。

### 5.3 WebSocket 握手失败 502
检查 `proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";` 这两行没漏(本配置已写)。

### 5.4 OAuth redirect_uri 错位
spring 看到的 host/port 和实际外部不一致 → `forward-headers-strategy: native` 必须开。本配置已开。

### 5.5 CORS 拦截
后端 `OauthCorsFilter` 已经放行 `/oauth/* /api/bridge/* /api/apps/*` 给跨源 SDK 用。若 xmap-ol-front 部署在不同源(比如 80 端口),浏览器会在调 `/oauth/token` 前发 OPTIONS preflight,本 filter 已处理。

### 5.6 前端深链接(`/chat/abc`)刷新 404
nginx `try_files $uri $uri/ /index.html` 兜底已经处理。

---

## 6. 升级/回滚

```bash
# 升级:
./gradlew :agent-app:bootJar -x test
cd web-console && npm run build && cd -
sudo systemctl stop  javaclaw-backend
sudo install -m 0644 -o javaclaw -g javaclaw \
    agent-app/build/libs/agent-app.jar /data/javaClaw/agent-app.jar
sudo rm -rf /data/javaClaw/web-console
sudo cp -r web-console/dist /data/javaClaw/web-console
sudo chown -R javaclaw:javaclaw /data/javaClaw/web-console
sudo systemctl start javaclaw-backend
sudo systemctl reload nginx

# 回滚: 留前一份 jar/dist 的备份目录,切软链即可。
# 例如:
#   sudo cp /data/javaClaw/agent-app.jar /data/javaClaw/agent-app.jar.bak.$(date +%s)
#   再升级。回滚就 mv 备份回去 + restart。
```
