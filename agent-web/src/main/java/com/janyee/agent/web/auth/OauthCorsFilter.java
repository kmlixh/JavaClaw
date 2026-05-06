package com.janyee.agent.web.auth;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 极简 CORS:给 /oauth/* 和 /api/bridge/* 和 /api/apps/* 用 —— 这些路径会被第三方页面
 * 嵌入的 SDK 跨域调用:
 * <ul>
 *   <li>/oauth/token 拿 access_token</li>
 *   <li>/api/bridge/invoke-result 直接 HTTP 回写 host.invoke 的结果绕开 postMessage</li>
 *   <li>/api/apps/{clientId}/enabled 嵌入端在加载 SDK 之前查应用启用状态决定按钮显隐</li>
 * </ul>
 *
 * <p>/oauth/token 是 application/x-www-form-urlencoded simple request,无 preflight。
 * /api/bridge/invoke-result 是 application/json + Authorization header,触发 preflight,
 * 这里 OPTIONS 分支会回 204。/api/apps/{clientId}/enabled 是简单 GET,通常无 preflight。</p>
 *
 * <p>放行原则:回响请求来源(Access-Control-Allow-Origin: &lt;origin&gt;)。如果以后想严格
 * 白名单,改成读 oauth_client.redirect_uris 解析出 origin 集合做匹配即可。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)  // 在 JwtAuthWebFilter(+10) 之前
public class OauthCorsFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path == null || !(path.startsWith("/oauth/")
                || path.startsWith("/api/bridge/")
                || path.startsWith("/api/apps/"))) {
            return chain.filter(exchange);
        }
        String origin = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
            headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            headers.set("Access-Control-Max-Age", "600");
        }
        if (HttpMethod.OPTIONS.matches(exchange.getRequest().getMethod().name())) {
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.NO_CONTENT);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
