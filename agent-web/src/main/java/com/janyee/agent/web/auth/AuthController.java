package com.janyee.agent.web.auth;

import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.AuthService;
import com.janyee.agent.infra.auth.JwtService;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.util.Map;

/**
 * 登录 / 登出 / whoami / 切租户 / 改密。
 *
 * <p>Cookie 设计:
 * <ul>
 *   <li>Name:{@value #COOKIE_NAME}</li>
 *   <li>HttpOnly:true —— 防 XSS 读取</li>
 *   <li>SameSite:Lax —— 跨站 POST 不带,顶级导航带</li>
 *   <li>Secure:只有 HTTPS 才 set —— dev 下 http 时关掉,由 property 控制</li>
 *   <li>Path:/ —— 全站生效,WebSocket 握手的 /ws/* 也能看到</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    public static final String COOKIE_NAME = "AGENT_TOKEN";

    private final AuthService authService;
    private final JwtService jwtService;
    private final Duration sessionTtl;
    private final boolean cookieSecure;

    public AuthController(
            AuthService authService,
            JwtService jwtService,
            @Value("${agent.auth.session-ttl:PT8H}") Duration sessionTtl,
            @Value("${agent.auth.cookie-secure:false}") boolean cookieSecure
    ) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.sessionTtl = sessionTtl;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, ServerWebExchange exchange) {
        if (body == null || isBlank(body.username()) || isBlank(body.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username/password 必填");
        }
        try {
            AuthService.Authenticated auth = authService.authenticate(body.username().trim(), body.password());
            String token = jwtService.issueSessionToken(
                    auth.user().getId(), auth.activeTenantId(), "system-default");
            exchange.getResponse().addCookie(buildCookie(token, sessionTtl));
            AuthService.Whoami whoami = authService.buildWhoami(auth.user().getId(), auth.activeTenantId());
            return ResponseEntity.ok(whoami);
        } catch (AuthService.AuthException error) {
            log.info("auth.login.failed user={}, code={}", body.username(), error.code());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", error.code(), "message", error.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(ServerWebExchange exchange) {
        exchange.getResponse().addCookie(buildCookie("", Duration.ZERO));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/whoami")
    public ResponseEntity<?> whoami() {
        AuthPrincipal current = SecurityContextHolder.current();
        if (current.anonymous()) {
            // P2 过渡期:匿名 → 返回 anonymous whoami,让前端判断是否要跳登录。
            return ResponseEntity.status(HttpStatus.OK).body(Map.of(
                    "anonymous", true,
                    "userId", current.userId(),
                    "activeTenantId", current.tenantId()
            ));
        }
        AuthService.Whoami whoami = authService.buildWhoami(current.userId(), current.tenantId());
        return ResponseEntity.ok(whoami);
    }

    @PostMapping("/switch-tenant")
    public ResponseEntity<?> switchTenant(@RequestBody SwitchTenantRequest body, ServerWebExchange exchange) {
        if (body == null || isBlank(body.tenantId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId 必填");
        }
        AuthPrincipal current = SecurityContextHolder.current();
        if (current.anonymous()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "UNAUTHENTICATED", "message", "请先登录"));
        }
        try {
            AuthService.Authenticated updated = authService.switchTenant(current.userId(), body.tenantId().trim());
            String token = jwtService.issueSessionToken(
                    updated.user().getId(), updated.activeTenantId(), "system-default");
            exchange.getResponse().addCookie(buildCookie(token, sessionTtl));
            AuthService.Whoami whoami = authService.buildWhoami(updated.user().getId(), updated.activeTenantId());
            return ResponseEntity.ok(whoami);
        } catch (AuthService.AuthException error) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", error.code(), "message", error.getMessage()));
        }
    }

    /**
     * 用 OAuth access_token(typ=oauth)换一个 session cookie。专门给 iframe 浮动面板的
     * "跳转到主控台"按钮用 —— iframe 里身份是 OAuth Bearer,只在 JS 内存有效,刷新就丢。
     * 这条端点拿同一个 user/tenant/app 签一个 typ=session 的 JWT 写 AGENT_TOKEN cookie,
     * cookie 是 HttpOnly + Lax + 有 maxAge,刷新页面后浏览器自动带回,后端 JwtAuthWebFilter
     * 照常解析,不用每次都重新登录。
     *
     * <p>不需要请求体:JwtAuthWebFilter 已经从 Authorization: Bearer 把 OAuth token 解过,
     * principal 直接从 SecurityContext 取就是当前 OAuth 身份。匿名(没带 token / token 失效)
     * 直接 401。</p>
     */
    @PostMapping("/exchange-oauth")
    public ResponseEntity<?> exchangeOauthToSession(ServerWebExchange exchange) {
        AuthPrincipal current = SecurityContextHolder.current();
        if (current == null || current.anonymous()
                || current.userId() == null || current.userId().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "UNAUTHENTICATED",
                            "message", "需要带有效的 OAuth access_token (Authorization: Bearer ...)"));
        }
        String token = jwtService.issueSessionToken(
                current.userId(),
                current.tenantId(),
                current.appId() == null || current.appId().isBlank() ? "system-default" : current.appId());
        exchange.getResponse().addCookie(buildCookie(token, sessionTtl));
        log.info("auth.exchange_oauth.issued userId={}, tenantId={}, appId={}",
                current.userId(), current.tenantId(), current.appId());
        AuthService.Whoami whoami = authService.buildWhoami(current.userId(), current.tenantId());
        return ResponseEntity.ok(whoami);
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest body) {
        if (body == null || isBlank(body.oldPassword()) || isBlank(body.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "oldPassword / newPassword 必填");
        }
        AuthPrincipal current = SecurityContextHolder.current();
        if (current.anonymous()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "UNAUTHENTICATED", "message", "请先登录"));
        }
        try {
            authService.changePassword(current.userId(), body.oldPassword(), body.newPassword());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (AuthService.AuthException error) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", error.code(), "message", error.getMessage()));
        }
    }

    // --------------------------------------------------------------------------------
    // helpers
    // --------------------------------------------------------------------------------

    private ResponseCookie buildCookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // --------------------------------------------------------------------------------
    // request DTOs
    // --------------------------------------------------------------------------------

    public record LoginRequest(String username, String password) {}
    public record SwitchTenantRequest(String tenantId) {}
    public record ChangePasswordRequest(String oldPassword, String newPassword) {}

    /** JwtException parse 失败时上抛的简化异常,便于 web 层统一处理。 */
    public static class InvalidTokenException extends ResponseStatusException {
        public InvalidTokenException(JwtException cause) {
            super(HttpStatus.UNAUTHORIZED, "invalid or expired token", cause);
        }
    }
}
