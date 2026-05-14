package com.janyee.agent.infra.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * 签发/校验 HS256 JWT。
 *
 * <p>claims:
 * <ul>
 *   <li>{@code sub} = userId</li>
 *   <li>{@code tid} = activeTenantId</li>
 *   <li>{@code aid} = activeAppId (optional, 目前默认 system-default)</li>
 *   <li>{@code typ} = "session"(网页 cookie 流) | "oauth"(外部 OAuth access token)</li>
 *   <li>{@code iat}, {@code exp}, {@code iss}</li>
 * </ul>
 *
 * <p>密钥来源优先级:
 * <ol>
 *   <li>{@code agent.auth.jwt-secret} property / {@code AGENT_JWT_SECRET} env(base64 编码,
 *       至少 32 字节解码后)—— 生产必配</li>
 *   <li>workspace/JWT_SECRET.txt —— 首次启动自动生成并落盘,下次启动读回来,跨重启
 *       session 有效。适合单机 / 本地开发;生产还是建议用 1)</li>
 * </ol>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "javaClaw";
    /** 网页 cookie 走这个类型 */
    public static final String TYP_SESSION = "session";
    /** 外部应用 OAuth access token 走这个类型 */
    public static final String TYP_OAUTH = "oauth";

    private final SecretKey key;
    private final Duration sessionTtl;
    private final Duration oauthTtl;

    /**
     * Redis key 存 JWT HMAC 签名密钥(Base64 编码)。
     * 选择放 Redis 而不是 DB:Redis 已经为 SessionLockManager 接入,延迟低、SET NX 原子,且
     * 没有 JPA 事务包袱。一份密钥跨重启 / 多实例共享,保证旧 cookie 在配置不变时永远有效。
     */
    private static final String REDIS_KEY_JWT_SECRET = "agent:auth:jwt:secret";

    public JwtService(
            @Value("${agent.auth.jwt-secret:}") String secretBase64,
            @Value("${agent.auth.session-ttl:PT8H}") Duration sessionTtl,
            @Value("${agent.auth.oauth-access-ttl:PT2H}") Duration oauthTtl,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisProvider
    ) {
        this.sessionTtl = sessionTtl;
        this.oauthTtl = oauthTtl;
        // 优先级:env > property > Redis > workspace/JWT_SECRET.txt。
        // env/property 这两层留给"运维想强制设置密钥"的场景(覆盖任何持久化的旧值);
        // Redis 才是默认推荐的持久化路径(跨重启 / 多实例);
        // 文件保留作为 Redis 不可用时的本地兜底。
        String envSecret = System.getenv("AGENT_JWT_SECRET");
        String effective = envSecret != null && !envSecret.isBlank() ? envSecret : secretBase64;
        if (effective != null && !effective.isBlank()) {
            byte[] raw = Decoders.BASE64.decode(effective.trim());
            if (raw.length < 32) {
                throw new IllegalStateException(
                        "agent.auth.jwt-secret must decode to >=32 bytes (HS256); got " + raw.length);
            }
            this.key = Keys.hmacShaKeyFor(raw);
            log.info("auth.jwt.key_loaded source={}, bytes={}",
                    envSecret != null && !envSecret.isBlank() ? "env" : "property", raw.length);
            return;
        }
        org.springframework.data.redis.core.StringRedisTemplate redis =
                redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis != null) {
            byte[] fromRedis = loadOrInitSecretFromRedis(redis);
            if (fromRedis != null) {
                this.key = Keys.hmacShaKeyFor(fromRedis);
                return;
            }
            log.warn("auth.jwt.redis_unavailable_fallback_to_file");
        }
        // Redis 不可用时降级到文件:JWT_SECRET.txt 本地持久化,本地开发跨重启保持登录态。
        Path secretFile = Path.of(System.getProperty("user.dir"), "JWT_SECRET.txt");
        byte[] raw = loadOrGenerateSecret(secretFile);
        this.key = Keys.hmacShaKeyFor(raw);
    }

    /**
     * 先 GET 一次 agent:auth:jwt:secret。在 → 解码返回。不在 → 生成 48 字节随机,Base64
     * 编码后用 SET NX 写入(防止并发实例同时启动各自生成不同的 key 互踩),再 GET 一次拿
     * "实际胜出的那份"。任何 Redis 异常返回 null,调用方走文件降级。
     */
    private byte[] loadOrInitSecretFromRedis(org.springframework.data.redis.core.StringRedisTemplate redis) {
        try {
            String existing = redis.opsForValue().get(REDIS_KEY_JWT_SECRET);
            if (existing != null && !existing.isBlank()) {
                byte[] decoded = Decoders.BASE64.decode(existing.trim());
                if (decoded.length >= 32) {
                    log.info("auth.jwt.key_loaded source=redis bytes={}", decoded.length);
                    return decoded;
                }
                log.warn("auth.jwt.redis_secret_too_short bytes={}; regenerating", decoded.length);
            }
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            String encoded = Base64.getEncoder().encodeToString(random);
            // setIfAbsent 是 SET NX,只有 key 不存在时才写。并发场景下"胜出"的那份会落到 redis,
            // 失败方再 GET 一次拿赢家的值。
            Boolean stored = redis.opsForValue().setIfAbsent(REDIS_KEY_JWT_SECRET, encoded);
            if (Boolean.TRUE.equals(stored)) {
                log.info("auth.jwt.key_generated source=redis bytes={}", random.length);
                return random;
            }
            // 没抢到 SET NX:同时启动的别人写入了。重新 GET 拿赢家的值。
            String winner = redis.opsForValue().get(REDIS_KEY_JWT_SECRET);
            if (winner != null && !winner.isBlank()) {
                byte[] decoded = Decoders.BASE64.decode(winner.trim());
                if (decoded.length >= 32) {
                    log.info("auth.jwt.key_loaded_after_race source=redis bytes={}", decoded.length);
                    return decoded;
                }
            }
            log.warn("auth.jwt.redis_secret_write_race_failed");
            return null;
        } catch (Exception error) {
            // 连接失败 / 鉴权失败 / 网络抖动:全部当 Redis 不可用,降级到文件。
            log.warn("auth.jwt.redis_load_failed cause={}", error.getMessage());
            return null;
        }
    }

    /**
     * 优先读文件;文件不存在 / 损坏 / 太短 → 生成新的 48 字节随机密钥,Base64 编码写盘。
     * 写盘失败仅 warn,保持进程内密钥可用(但重启失效,跟老行为一致)。
     */
    private byte[] loadOrGenerateSecret(Path file) {
        if (Files.exists(file)) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8).trim();
                // 支持两种格式:纯 base64 或 key=value 行
                String base64 = content;
                for (String line : content.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("secret=")) base64 = trimmed.substring("secret=".length()).trim();
                }
                byte[] decoded = Decoders.BASE64.decode(base64);
                if (decoded.length >= 32) {
                    log.info("auth.jwt.key_loaded source=file path={}, bytes={}",
                            file.toAbsolutePath(), decoded.length);
                    return decoded;
                }
                log.warn("auth.jwt.key_file_too_short path={}, bytes={}; regenerating",
                        file.toAbsolutePath(), decoded.length);
            } catch (Exception error) {
                log.warn("auth.jwt.key_file_read_failed path={}, cause={}; regenerating",
                        file.toAbsolutePath(), error.getMessage());
            }
        }
        byte[] random = new byte[48];
        new SecureRandom().nextBytes(random);
        String encoded = Base64.getEncoder().encodeToString(random);
        try {
            Files.writeString(file,
                    "# javaClaw auto-generated JWT signing secret (Base64, 48 bytes).\n"
                            + "# Delete to force regeneration (existing sessions invalidate).\n"
                            + "# Override via env AGENT_JWT_SECRET or property agent.auth.jwt-secret.\n"
                            + "secret=" + encoded + "\n",
                    StandardCharsets.UTF_8);
            log.info("auth.jwt.key_generated path={}, bytes={}",
                    file.toAbsolutePath(), random.length);
        } catch (Exception error) {
            log.warn("auth.jwt.key_persist_failed path={}, cause={}; key is in-memory only, "
                    + "sessions will not survive restart", file.toAbsolutePath(), error.getMessage());
        }
        return random;
    }

    /** 签发"网页 session" JWT。默认 8 小时有效期。 */
    public String issueSessionToken(String userId, String tenantId, String appId) {
        return issue(TYP_SESSION, userId, tenantId, appId, sessionTtl);
    }

    /** 签发"外部 OAuth" access token。默认 2 小时有效期(短于 session,外部应用需要周期刷新)。 */
    public String issueOauthAccessToken(String userId, String tenantId, String appId) {
        return issue(TYP_OAUTH, userId, tenantId, appId, oauthTtl);
    }

    private String issue(String typ, String userId, String tenantId, String appId, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId)
                .claim("tid", tenantId)
                .claim("aid", appId)
                .claim("typ", typ)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析 + 验证 token;无效(过期 / 签名错 / 格式错)抛 JwtException。
     */
    public ParsedToken parse(String token) throws JwtException {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new ParsedToken(
                claims.getSubject(),
                claims.get("tid", String.class),
                claims.get("aid", String.class),
                claims.get("typ", String.class),
                claims.getIssuedAt() != null ? claims.getIssuedAt().toInstant() : null,
                claims.getExpiration() != null ? claims.getExpiration().toInstant() : null
        );
    }

    public record ParsedToken(
            String userId,
            String tenantId,
            String appId,
            String typ,
            Instant issuedAt,
            Instant expiresAt
    ) {}
}
