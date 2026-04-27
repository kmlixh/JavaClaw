package com.janyee.agent.web.auth;

import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.OauthProviderService;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OAuth2 authorization_code flow(server side)。
 *
 * <p>两个端点:
 * <ul>
 *   <li>{@code GET /oauth/authorize} — 浏览器跳转入口。用户已登录就直接签 code → 302 回 client;
 *       未登录返回 401(前端 / 反代收到后自己跳到 /login?next=&lt;原 URL&gt;)。</li>
 *   <li>{@code POST /oauth/token} — 后端机器互调。code 换 access_token。</li>
 * </ul>
 *
 * <p>为了 P2 最小可用:authorize 暂时"已登录即同意",不做 consent 页;以后 P4 再给用户
 * 做明确的同意 + scope 展示页。</p>
 */
@RestController
@RequestMapping("/oauth")
public class OauthController {

    private final OauthProviderService oauth;

    public OauthController(OauthProviderService oauth) {
        this.oauth = oauth;
    }

    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "response_type", required = false, defaultValue = "code") String responseType
    ) {
        if (!"code".equalsIgnoreCase(responseType)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unsupported_response_type",
                    "error_description", "only authorization_code is supported"));
        }
        AuthPrincipal current = SecurityContextHolder.current();
        if (current.anonymous()) {
            // 前端看到 401 会自己跳 /login?next=<当前 URL>
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "login_required",
                    "error_description", "user must be logged in before granting OAuth authorization"));
        }
        try {
            String code = oauth.issueAuthorizationCode(
                    clientId, redirectUri, scope, current.userId(), current.tenantId());
            StringBuilder target = new StringBuilder(redirectUri);
            target.append(redirectUri.contains("?") ? '&' : '?');
            target.append("code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
            if (state != null && !state.isBlank()) {
                target.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
            }
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target.toString())).build();
        } catch (OauthProviderService.OauthException error) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", error.code().toLowerCase().replace('_', '_'),
                    "error_description", error.getMessage()));
        }
    }

    @PostMapping(value = "/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> token(
            @RequestParam(value = "grant_type", required = false) String grantType,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            // client_credentials 模式可选传宿主侧的最终用户身份 —— 不存在就自动建
            @RequestParam(value = "user_id", required = false) String userId,
            @RequestParam(value = "user_name", required = false) String userName
    ) {
        try {
            OauthProviderService.TokenResponse token;
            if ("authorization_code".equalsIgnoreCase(grantType)) {
                token = oauth.exchangeCode(code, clientId, clientSecret, redirectUri);
            } else if ("client_credentials".equalsIgnoreCase(grantType)) {
                // 第三方系统直接换 token,不走用户跳转。SDK 嵌入场景就走这条。
                token = oauth.clientCredentialsToken(clientId, clientSecret, userId, userName);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "unsupported_grant_type",
                        "error_description", "only authorization_code and client_credentials are supported"));
            }
            return ResponseEntity.ok(Map.of(
                    "access_token", token.accessToken(),
                    "token_type", token.tokenType(),
                    "expires_in", token.expiresInSeconds(),
                    "scope", token.scope()
            ));
        } catch (OauthProviderService.OauthException error) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", error.code().toLowerCase(),
                    "error_description", error.getMessage()));
        }
    }
}
