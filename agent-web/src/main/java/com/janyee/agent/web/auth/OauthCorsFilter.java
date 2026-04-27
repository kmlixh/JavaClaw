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
 * 极简 CORS:专门给 /oauth/token 用,让第三方页面里的 SDK 能跨域 POST 拿 access_token。
 * 其它 API 仍走同源(嵌入页 iframe 在我们域下,fetch 自带 cookie/Bearer,无需 CORS)。
 *
 * <p>SDK 的 POST 是 application/x-www-form-urlencoded —— simple request,没有 preflight,
 * 只需要回包带上 Access-Control-Allow-Origin。这里也兼容 OPTIONS preflight 以防未来扩展。</p>
 *
 * <p>放行原则:回响请求来源(Access-Control-Allow-Origin: <origin>),允许带凭据用不上 ——
 * client_credentials 不需要 cookie。如果以后想严格白名单,改成读 oauth_client.redirect_uris
 * 解析出 origin 集合做匹配即可。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)  // 在 JwtAuthWebFilter(+10) 之前
public class OauthCorsFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path == null || !path.startsWith("/oauth/")) {
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
