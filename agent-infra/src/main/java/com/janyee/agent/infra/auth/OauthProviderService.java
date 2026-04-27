package com.janyee.agent.infra.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.janyee.agent.infra.persistence.entity.auth.AppUserEntity;
import com.janyee.agent.infra.persistence.entity.auth.OauthAuthorizationCodeEntity;
import com.janyee.agent.infra.persistence.entity.auth.OauthClientEntity;
import com.janyee.agent.infra.persistence.entity.auth.RoleEntity;
import com.janyee.agent.infra.persistence.entity.auth.UserTenantRoleEntity;
import com.janyee.agent.infra.persistence.repository.auth.AppUserRepository;
import com.janyee.agent.infra.persistence.repository.auth.OauthAuthorizationCodeRepository;
import com.janyee.agent.infra.persistence.repository.auth.OauthClientRepository;
import com.janyee.agent.infra.persistence.repository.auth.RoleRepository;
import com.janyee.agent.infra.persistence.repository.auth.UserTenantRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 简化版 OAuth2 authorization_code 流:
 *
 * <pre>
 *   [外部 App]                 [我们系统(/oauth/authorize)]       [用户浏览器]
 *      |  1. 重定向用户到我们的 authorize 端点                      |
 *      |------------------------------------------------------------>
 *      |                                                              |
 *      |                    2. 用户在我们这里已经登录(cookie)?       |
 *      |                       是 → 直接授权 + 生成一次性 code        |
 *      |                       否 → 302 跳 /login,登录后回来再来     |
 *      |                                                              |
 *      |          3. 302 回 client redirect_uri?code=XXX&state=YYY   |
 *      |<------------------------------------------------------------|
 *      |                                                              |
 *      |  4. POST /oauth/token {code, client_id, client_secret}      |
 *      |------------------------------------------------------------->|
 *      |                                                              |
 *      |  5. 200 { access_token:JWT, token_type:"Bearer", expires_in }|
 *      |<-------------------------------------------------------------|
 *      |                                                              |
 *      |  6. 后续调我们系统 API:Authorization: Bearer <access_token>|
 *      |------------------------------------------------------------->|
 * </pre>
 *
 * P2 简化:不实现 refresh_token(短 TTL + 客户端重做 authorize 即可),不实现 PKCE
 * (留了数据库字段,以后再实现);scope 只在 token 里带回,不强制校验权限集(那是 P3)。
 */
@Service
public class OauthProviderService {

    private static final Logger log = LoggerFactory.getLogger(OauthProviderService.class);
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RNG = new SecureRandom();

    private final OauthClientRepository clientRepository;
    private final OauthAuthorizationCodeRepository codeRepository;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserTenantRoleRepository userTenantRoleRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public OauthProviderService(
            OauthClientRepository clientRepository,
            OauthAuthorizationCodeRepository codeRepository,
            JwtService jwtService,
            ObjectMapper objectMapper,
            AppUserRepository userRepository,
            RoleRepository roleRepository,
            UserTenantRoleRepository userTenantRoleRepository
    ) {
        this.clientRepository = clientRepository;
        this.codeRepository = codeRepository;
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userTenantRoleRepository = userTenantRoleRepository;
    }

    /**
     * 用户在我们的 /oauth/authorize 页面同意后调用。 返回一次性 code,调用方把它拼进 client
     * 的 redirect_uri 让用户浏览器 302 跳回去。
     */
    @Transactional
    public String issueAuthorizationCode(
            String clientId, String redirectUri, String scope,
            String userId, String tenantId
    ) {
        OauthClientEntity client = requireActiveClient(clientId);
        if (!isAllowedRedirect(client, redirectUri)) {
            throw new OauthException("INVALID_REDIRECT_URI",
                    "redirect_uri not in the registered allow-list");
        }
        String code = urlSafeRandom(48);
        OauthAuthorizationCodeEntity entity = new OauthAuthorizationCodeEntity();
        entity.setCode(code);
        entity.setClientId(clientId);
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setRedirectUri(redirectUri);
        entity.setScopes(scope == null || scope.isBlank() ? "[]"
                : toJson(List.of(scope.trim().split("\\s+"))));
        entity.setExpiresAt(Instant.now().plus(CODE_TTL));
        codeRepository.save(entity);
        log.info("oauth.code.issued clientId={}, userId={}, tenantId={}, scope={}",
                clientId, userId, tenantId, scope);
        return code;
    }

    /**
     * POST /oauth/token 兑换:校验 code + client 凭据 + redirect_uri 一致 → mark consumed,
     * 颁发 JWT access token(typ='oauth')。过期 / 已用 / 配对不上全部拒。
     */
    @Transactional
    public TokenResponse exchangeCode(
            String code, String clientId, String clientSecret, String redirectUri
    ) {
        OauthClientEntity client = requireActiveClient(clientId);
        if (!encoder.matches(clientSecret == null ? "" : clientSecret, client.getClientSecretHash())) {
            throw new OauthException("INVALID_CLIENT", "client credentials rejected");
        }
        OauthAuthorizationCodeEntity row = codeRepository.findById(code)
                .orElseThrow(() -> new OauthException("INVALID_GRANT", "code not found"));
        if (row.isConsumed()) {
            // 同一 code 被兑换两次 —— 正规做法是 revoke 所有关联 token,这里简化只拒
            throw new OauthException("INVALID_GRANT", "code has already been consumed");
        }
        if (Instant.now().isAfter(row.getExpiresAt())) {
            throw new OauthException("INVALID_GRANT", "code expired");
        }
        if (!row.getClientId().equals(clientId)) {
            throw new OauthException("INVALID_GRANT", "code belongs to a different client");
        }
        if (!row.getRedirectUri().equals(redirectUri)) {
            throw new OauthException("INVALID_GRANT", "redirect_uri does not match original authorize request");
        }
        row.setConsumed(true);
        codeRepository.save(row);
        String accessToken = jwtService.issueOauthAccessToken(
                row.getUserId(), row.getTenantId(), "system-default");
        log.info("oauth.token.issued clientId={}, userId={}", clientId, row.getUserId());
        return new TokenResponse(
                accessToken,
                "Bearer",
                7200,  // 跟 JwtService 的 oauth TTL 对齐;P3 再从配置读
                row.getScopes()
        );
    }

    /**
     * client_credentials 模式:第三方拿 client_id + client_secret 换 access_token,无浏览器跳转。
     *
     * <p>支持自动开通:外部 SDK 把宿主侧的 userId / userName 一起传进来,我们按
     * (client.tenant_id, externalUserId) 找 app_user;找不到就建一个,绑到该租户的
     * code='USER' 角色(由 TenantBootstrapService 在租户创建时已经备好)。</p>
     *
     * <p>没传 externalUserId 的兼容路径:落到 client.owner_user_id;两者都缺时拒发。</p>
     */
    @Transactional
    public TokenResponse clientCredentialsToken(
            String clientId, String clientSecret,
            String externalUserId, String externalUserName
    ) {
        OauthClientEntity client = requireActiveClient(clientId);
        if (!encoder.matches(clientSecret == null ? "" : clientSecret, client.getClientSecretHash())) {
            throw new OauthException("INVALID_CLIENT", "client credentials rejected");
        }
        String tenantId = client.getTenantId();
        String resolvedUserId;
        if (externalUserId != null && !externalUserId.isBlank()) {
            resolvedUserId = ensureExternalUser(tenantId, externalUserId.trim(),
                    (externalUserName == null || externalUserName.isBlank())
                            ? externalUserId.trim() : externalUserName.trim());
        } else if (client.getOwnerUserId() != null && !client.getOwnerUserId().isBlank()) {
            resolvedUserId = client.getOwnerUserId();
        } else {
            throw new OauthException("MISSING_USER",
                    "client_credentials grant requires either user_id parameter or oauth_client.owner_user_id");
        }
        String accessToken = jwtService.issueOauthAccessToken(
                resolvedUserId, tenantId, "system-default");
        log.info("oauth.client_credentials.issued clientId={}, userId={}, tenant={}",
                clientId, resolvedUserId, tenantId);
        return new TokenResponse(accessToken, "Bearer", 7200, client.getScopes());
    }

    /**
     * 外部用户 → 我们系统 app_user 映射:id 用 "ext:{tenantId}:{externalUserId}" 这种确定性主键。
     * 找不到就建,自动绑到该租户 code='USER' 角色(没该角色就跳过 binding,用户能登但没权限,需要 TenantBootstrap 跑过)。
     */
    private String ensureExternalUser(String tenantId, String externalUserId, String externalUserName) {
        String userId = "ext:" + tenantId + ":" + externalUserId;
        AppUserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            user = new AppUserEntity();
            user.setId(userId);
            user.setUsername(userId);  // 跟 id 同步,findByUsername 也能命中
            user.setDisplayName(externalUserName);
            user.setStatus("ACTIVE");
            // 外部接入用户不通过密码登录,塞个不可用的占位 hash
            user.setPasswordHash("EXTERNAL_OAUTH_NO_PASSWORD");
            user.setPasswordMustChange(false);
            user.setPreferredTenantId(tenantId);
            userRepository.save(user);
            log.info("oauth.external_user.provisioned userId={}, tenant={}", userId, tenantId);
        } else {
            // 已存在:把宿主传过来的 displayName 同步进来(可能改了名)
            if (externalUserName != null && !externalUserName.equals(user.getDisplayName())) {
                user.setDisplayName(externalUserName);
                userRepository.save(user);
            }
        }
        // 角色绑定:已经有就不重复;没绑过就找该租户的 USER 角色绑上去
        boolean alreadyBound = !userTenantRoleRepository.findByUserIdAndTenantId(userId, tenantId).isEmpty();
        if (!alreadyBound) {
            RoleEntity userRole = roleRepository.findByTenantIdAndCode(tenantId, "USER").orElse(null);
            if (userRole != null) {
                UserTenantRoleEntity utr = new UserTenantRoleEntity();
                utr.setUserId(userId);
                utr.setTenantId(tenantId);
                utr.setRoleId(userRole.getId());
                userTenantRoleRepository.save(utr);
                log.info("oauth.external_user.role_bound userId={}, tenant={}, role={}",
                        userId, tenantId, userRole.getId());
            } else {
                log.warn("oauth.external_user.no_user_role tenant={} (run TenantBootstrap or create USER role manually)",
                        tenantId);
            }
        }
        return userId;
    }

    private OauthClientEntity requireActiveClient(String clientId) {
        OauthClientEntity client = clientRepository.findById(clientId)
                .orElseThrow(() -> new OauthException("INVALID_CLIENT", "client_id not recognized"));
        if (!"ACTIVE".equalsIgnoreCase(client.getStatus())) {
            throw new OauthException("INVALID_CLIENT", "client is " + client.getStatus());
        }
        return client;
    }

    private boolean isAllowedRedirect(OauthClientEntity client, String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        try {
            List<String> allowed = objectMapper.readValue(
                    client.getRedirectUris(), new TypeReference<List<String>>() {});
            return allowed.contains(candidate);
        } catch (Exception error) {
            log.warn("oauth.client.redirect_uris_invalid clientId={}, cause={}",
                    client.getClientId(), error.getMessage());
            return false;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return "[]";
        }
    }

    private static String urlSafeRandom(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    // --------------------------------------------------------------------------------

    public record TokenResponse(String accessToken, String tokenType, int expiresInSeconds, String scope) {}

    public static class OauthException extends RuntimeException {
        private final String code;
        public OauthException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String code() { return code; }
    }
}
