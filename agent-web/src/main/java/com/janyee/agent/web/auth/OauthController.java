package com.janyee.agent.web.auth;

import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.OauthProviderService;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

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

    /**
     * WebFlux 下 @RequestParam 对 form-urlencoded body 的绑定不像 MVC 那样自动,
     * 实际接收的是 query string 部分;form body 不会进 RequestParam。这里显式
     * 用 ServerWebExchange.getFormData() 拿,query 也兜一份(优先 form body)。
     */
    @PostMapping(value = "/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> token(
            org.springframework.web.server.ServerWebExchange exchange
    ) {
        return exchange.getFormData().map(formData -> {
            MultiValueMap<String, String> queryParams = exchange.getRequest().getQueryParams();
            String grantType   = pick(formData, queryParams, "grant_type");
            String code        = pick(formData, queryParams, "code");
            String clientId    = pick(formData, queryParams, "client_id");
            String clientSecret= pick(formData, queryParams, "client_secret");
            String redirectUri = pick(formData, queryParams, "redirect_uri");
            // client_credentials 模式可选传宿主侧的最终用户身份 —— 不存在就自动建
            String userId      = pick(formData, queryParams, "user_id");
            String userName    = pick(formData, queryParams, "user_name");
            try {
                OauthProviderService.TokenResponse token;
                if ("authorization_code".equalsIgnoreCase(grantType)) {
                    token = oauth.exchangeCode(code, clientId, clientSecret, redirectUri);
                } else if ("client_credentials".equalsIgnoreCase(grantType)) {
                    token = oauth.clientCredentialsToken(clientId, clientSecret, userId, userName);
                } else {
                    return ResponseEntity.badRequest().<Object>body(Map.of(
                            "error", "unsupported_grant_type",
                            "error_description", "only authorization_code and client_credentials are supported"));
                }
                return ResponseEntity.<Object>ok(Map.of(
                        "access_token", token.accessToken(),
                        "token_type", token.tokenType(),
                        "expires_in", token.expiresInSeconds(),
                        "scope", token.scope()
                ));
            } catch (OauthProviderService.OauthException error) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).<Object>body(Map.of(
                        "error", error.code().toLowerCase(),
                        "error_description", error.getMessage()));
            }
        });
    }

    private static String pick(MultiValueMap<String, String> formData,
                                MultiValueMap<String, String> queryParams,
                                String key) {
        String v = formData != null ? formData.getFirst(key) : null;
        if (v != null && !v.isBlank()) return v;
        return queryParams != null ? queryParams.getFirst(key) : null;
    }
}
