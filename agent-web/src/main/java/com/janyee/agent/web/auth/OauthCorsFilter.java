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
 * 极简 CORS:给 /oauth/* 和 /api/bridge/* 用 —— 这两条路径会被第三方页面嵌入的 SDK
 * 跨域调用(/oauth/token 拿 access_token、/api/bridge/invoke-result 直接 HTTP 回写
 * host.invoke 的结果绕开 postMessage)。
 *
 * <p>/oauth/token 是 application/x-www-form-urlencoded simple request,无 preflight。
 * /api/bridge/invoke-result 是 application/json + Authorization header,触发 preflight,
 * 这里 OPTIONS 分支会回 204。</p>
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
        if (path == null || !(path.startsWith("/oauth/") || path.startsWith("/api/bridge/"))) {
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
