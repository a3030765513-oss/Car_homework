package com.substation.common.auth;

import com.substation.common.auth.model.SessionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    private JedisPool pool;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        pool = new JedisPool("localhost", 6379);
        try (Jedis jedis = pool.getResource()) { jedis.flushDB(); }
        sessionManager = new SessionManager(pool);
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = pool.getResource()) { jedis.flushDB(); }
        pool.close();
    }

    @Test
    void createAndValidateSession() {
        String token = sessionManager.createSession("admin", "admin");
        assertNotNull(token);
        assertFalse(token.isBlank());

        Optional<SessionInfo> session = sessionManager.validate(token);
        assertTrue(session.isPresent());
        assertEquals("admin", session.get().username());
        assertEquals("admin", session.get().role());
    }

    @Test
    void validateReturnsEmptyOnNull() {
        assertTrue(sessionManager.validate(null).isEmpty());
    }

    @Test
    void validateReturnsEmptyOnBlank() {
        assertTrue(sessionManager.validate("").isEmpty());
        assertTrue(sessionManager.validate("   ").isEmpty());
    }

    @Test
    void validateReturnsEmptyOnInvalidToken() {
        assertTrue(sessionManager.validate("invalid-token-12345").isEmpty());
    }

    @Test
    void destroySessionInvalidatesToken() {
        String token = sessionManager.createSession("admin", "admin");
        assertTrue(sessionManager.validate(token).isPresent());

        sessionManager.destroySession(token);
        assertTrue(sessionManager.validate(token).isEmpty());
    }

    @Test
    void extractTokenFromHeader() {
        assertEquals("abc123", SessionManager.extractToken("Bearer abc123"));
        assertEquals("abc123", SessionManager.extractToken("Bearer  abc123"));
        assertNull(SessionManager.extractToken(null));
        assertNull(SessionManager.extractToken("Basic abc123"));
        assertNull(SessionManager.extractToken(""));
    }
}
