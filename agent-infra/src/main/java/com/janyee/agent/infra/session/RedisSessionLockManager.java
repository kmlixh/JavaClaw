package com.janyee.agent.infra.session;

import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.runtime.session.SessionLockManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "agent.session-lock", name = "type", havingValue = "redis")
public class RedisSessionLockManager implements SessionLockManager {

    private final StringRedisTemplate redisTemplate;
    private final AgentPlatformProperties properties;
    private final Map<String, String> owners = new ConcurrentHashMap<>();

    public RedisSessionLockManager(StringRedisTemplate redisTemplate, AgentPlatformProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean tryLock(String sessionId, Duration timeout) {
        String ownerToken = UUID.randomUUID().toString();
        Duration ttl = resolveTtl(timeout);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey(sessionId), ownerToken, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            owners.put(sessionId, ownerToken);
            return true;
        }
        return false;
    }

    @Override
    public void unlock(String sessionId) {
        String key = lockKey(sessionId);
        String ownerToken = owners.remove(sessionId);
        if (ownerToken == null) {
            return;
        }

        String currentOwner = redisTemplate.opsForValue().get(key);
        if (ownerToken.equals(currentOwner)) {
            redisTemplate.delete(key);
        }
    }

    private String lockKey(String sessionId) {
        String prefix = properties.sessionLock() != null && properties.sessionLock().keyPrefix() != null
                ? properties.sessionLock().keyPrefix()
                : "agent:session-lock:";
        return prefix + sessionId;
    }

    private Duration resolveTtl(Duration timeout) {
        if (properties.sessionLock() != null && properties.sessionLock().ttl() != null) {
            return properties.sessionLock().ttl();
        }
        return timeout != null ? timeout : Duration.ofSeconds(30);
    }
}
