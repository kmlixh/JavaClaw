package com.janyee.agent.infra.tool;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionCredentialCacheTest {

    private static final String URL = "jdbc:postgresql://10.174.238.4:5432/gis";

    @Test
    void putAndGetReturnsSameCredential() {
        ConnectionCredentialCache cache = new ConnectionCredentialCache();
        cache.put(URL, "gisuser", "s3cret");

        Optional<ConnectionCredentialCache.Credential> credential = cache.get(URL);
        assertTrue(credential.isPresent());
        assertEquals("gisuser", credential.get().username());
        assertEquals("s3cret", credential.get().password());
    }

    @Test
    void getIsCaseAndWhitespaceInsensitiveForKey() {
        ConnectionCredentialCache cache = new ConnectionCredentialCache();
        cache.put(URL, "gisuser", "s3cret");

        Optional<ConnectionCredentialCache.Credential> credential =
                cache.get("  JDBC:POSTGRESQL://10.174.238.4:5432/GIS  ");
        assertTrue(credential.isPresent(), "lookup should normalize case and whitespace");
        assertEquals("gisuser", credential.get().username());
    }

    @Test
    void putIsIgnoredWhenJdbcUrlOrUsernameBlank() {
        ConnectionCredentialCache cache = new ConnectionCredentialCache();
        cache.put("", "gisuser", "s3cret");
        cache.put(URL, "", "s3cret");
        cache.put(null, "gisuser", "s3cret");
        cache.put(URL, null, "s3cret");
        assertEquals(0, cache.size(), "blank keys/users must not be cached");
    }

    @Test
    void laterPutOverwritesEarlierCredential() {
        ConnectionCredentialCache cache = new ConnectionCredentialCache();
        cache.put(URL, "gisuser", "old");
        cache.put(URL, "gisuser", "new");
        assertEquals("new", cache.get(URL).orElseThrow().password());
    }

    @Test
    void expiredEntryIsEvictedOnGet() throws Exception {
        ConnectionCredentialCache cache = new ConnectionCredentialCache(1); // 1ms TTL
        cache.put(URL, "gisuser", "s3cret");
        Thread.sleep(10);
        assertFalse(cache.get(URL).isPresent(), "expired entry must be gone");
        assertEquals(0, cache.size(), "expired entry must be evicted from the map");
    }

    @Test
    void invalidateRemovesEntry() {
        ConnectionCredentialCache cache = new ConnectionCredentialCache();
        cache.put(URL, "gisuser", "s3cret");
        cache.invalidate(URL);
        assertFalse(cache.get(URL).isPresent());
    }
}
