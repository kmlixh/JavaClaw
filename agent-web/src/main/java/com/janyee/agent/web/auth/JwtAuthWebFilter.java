package com.janyee.agent.web.auth;

import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.JwtService;
import com.janyee.agent.infra.auth.PermissionResolver;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 每个请求进来都过一遍:
 * <ol>
 *   <li>优先 Authorization: Bearer (外部应用 OAuth 用),其次 Cookie AGENT_TOKEN</li>
 *   <li>有 token 就 JwtService.parse,失败当 anonymous(agent.auth.anonymous-enabled=true 时)
 *       或 401(false 时)</li>
 *   <li>验过的 principal 放 ThreadLocal + exchange attribute,P2 后续代码从任一处读到它</li>
 * </ol>
 *
 * <p>P2 过渡期 <code>agent.auth.anonymous-enabled=true</code>:没 token 也能进,SecurityContext
 * 用默认 admin@system principal,现有 API 无缝跑。P3 加固时置 false,没 token 的请求直接 401。</p>
 *
 * <p>WebFlux 的链路会跨线程,ThreadLocal 不够稳。我们两手都要:
 * <ul>
 *   <li>ThreadLocal —— 同线程内(Controller 方法体)用得上</li>
 *   <li>exchange attribute —— 下游 Reactive 代码切线程后仍然能从 exchange 取</li>
 * </ul>
 * 真正严格的 per-subscription 传播是 Reactor context,P3 再升级。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)  // 要在其它业务 filter 之前,但别挤掉 CORS
public class JwtAuthWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthWebFilter.class);
    public static final String PRINCIPAL_ATTR = "agent.authPrincipal";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final PermissionResolver permissionResolver;
    private final boolean anonymousEnabled;

    public JwtAuthWebFilter(
            JwtService jwtService,
            PermissionResolver permissionResolver,
            @Value("${agent.auth.anonymous-enabled:true}") boolean anonymousEnabled
    ) {
        this.jwtService = jwtService;
        this.permissionResolver = permissionResolver;
        this.anonymousEnabled = anonymousEnabled;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String token = extractToken(exchange);
        AuthPrincipal principal = resolvePrincipal(token);
        if (principal == null) {
            // anonymous=true 走老兜底; anonymous=false + 公开路径(login / whoami / oauth/token /
            // 前端静态)注入匿名 principal,让 controller 自己决定怎么回应; 其他路径 401。
            if (anonymousEnabled || isPublicPath(path)) {
                principal = AuthPrincipal.anonymousSystemAdmin();
            } else {
                exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        }
        exchange.getAttributes().put(PRINCIPAL_ATTR, principal);
        SecurityContextHolder.setCurrent(principal);
        final AuthPrincipal captured = principal;
        return chain.filter(exchange)
                .doFinally(sig -> {
                    // 清 ThreadLocal 防 thread reuse 污染
                    SecurityContextHolder.clear();
                });
    }

    /**
     * anonymous=false 时仍需放行的路径:
     *   - 登录端点本身(/api/auth/login)
     *   - whoami(让前端能拿到 {anonymous:true} 用以决定跳转)
     *   - OAuth token 端点(走 client_id+client_secret,不靠用户 JWT)
     *   - 任何不是 /api/* /oauth/* /ws/* 的路径 → 视为前端 SPA 静态资源,直接放行
     */
    private boolean isPublicPath(String path) {
        if (path == null || path.isEmpty()) return true;
        if (!path.startsWith("/api/") && !path.startsWith("/oauth/") && !path.startsWith("/ws/")) {
            return true;
        }
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/whoami")
                || path.equals("/oauth/token");
    }

    private String extractToken(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        // 1) Authorization: Bearer (任何路径优先级最高)
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            return auth.substring(BEARER_PREFIX.length()).trim();
        }
        // 2) ?access_token= 必须放在 cookie 之前 ——
        //    iframe 同源场景下,浏览器会自动给 WS 上行带 cookie AGENT_TOKEN(可能是宿主页里
        //    内部用户的 cookie),如果先读 cookie 就会冒充成"内部用户",让外部 OAuth token 失效。
        if (path != null && path.startsWith("/ws/")) {
            String queryToken = exchange.getRequest().getQueryParams().getFirst("access_token");
            if (queryToken != null && !queryToken.isBlank()) {
                return queryToken.trim();
            }
        }
        // 3) Cookie AGENT_TOKEN (主控台 session 路径)
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(AuthController.COOKIE_NAME);
        if (cookie != null && cookie.getValue() != null && !cookie.getValue().isBlank()) {
            return cookie.getValue();
        }
        return null;
    }

    private AuthPrincipal resolvePrincipal(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            JwtService.ParsedToken parsed = jwtService.parse(token);
            Set<String> perms = permissionResolver.resolve(parsed.userId(), parsed.tenantId());
            return new AuthPrincipal(
                    parsed.userId(),
                    parsed.tenantId(),
                    parsed.appId() != null ? parsed.appId() : "system-default",
                    perms,
                    false
            );
        } catch (JwtException error) {
            log.debug("auth.jwt.invalid cause={}", error.getMessage());
            return null;
        }
    }
}
