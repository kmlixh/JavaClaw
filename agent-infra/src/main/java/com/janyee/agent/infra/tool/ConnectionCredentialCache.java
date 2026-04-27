package com.janyee.agent.infra.tool;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Caches username/password per jdbcUrl within the process so that subsequent db.* tool
 * calls against the same remote database can omit credentials after the first successful
 * connection. Aligns with the TTL strategy used by DatabaseSchemaInspectTool's schema cache.
 *
 * Thread safe. Not partitioned by run/session — intended for single-tenant dev deployments.
 */
final class ConnectionCredentialCache {

    static final long DEFAULT_TTL_MILLIS = 10 * 60 * 1000L;

    private final long ttlMillis;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    ConnectionCredentialCache() {
        this(DEFAULT_TTL_MILLIS);
    }

    ConnectionCredentialCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    void put(String jdbcUrl, String username, String password) {
        if (jdbcUrl == null || jdbcUrl.isBlank() || username == null || username.isBlank()) {
            return;
        }
        entries.put(normalize(jdbcUrl), new Entry(username, password == null ? "" : password, now()));
    }

    Optional<Credential> get(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return Optional.empty();
        }
        String key = normalize(jdbcUrl);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (now() - entry.createdAtMillis() > ttlMillis) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(new Credential(entry.username(), entry.password()));
    }

    void invalidate(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return;
        }
        entries.remove(normalize(jdbcUrl));
    }

    int size() {
        return entries.size();
    }

    private static String normalize(String jdbcUrl) {
        return jdbcUrl.trim().toLowerCase(Locale.ROOT);
    }

    private long now() {
        return System.currentTimeMillis();
    }

    record Credential(String username, String password) {}

    private record Entry(String username, String password, long createdAtMillis) {}
}
