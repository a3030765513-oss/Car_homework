package com.substation.common.auth;

import com.alibaba.fastjson2.JSON;
import com.substation.common.auth.model.SessionInfo;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 基于 Redis 的会话管理。
 * Token 用 SecureRandom 生成，TTL 30分钟，每次校验自动续期。
 */
public class SessionManager {

    private static final String SESSION_KEY_PREFIX = "auth:session:";
    private static final int SESSION_TTL_SECONDS = 1800;
    private static final int TOKEN_BYTE_LENGTH = 32;

    private final JedisPool pool;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionManager(JedisPool pool) {
        this.pool = pool;
    }

    /** 创建会话，返回 token */
    public String createSession(String username, String role) {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        long now = System.currentTimeMillis() / 1000;
        SessionInfo session = new SessionInfo(username, role, now, now);

        try (Jedis jedis = pool.getResource()) {
            jedis.setex(SESSION_KEY_PREFIX + token, SESSION_TTL_SECONDS,
                    JSON.toJSONString(session));
        }
        return token;
    }

    /** 校验 token，返回会话信息并续期；无效返回空 */
    public Optional<SessionInfo> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try (Jedis jedis = pool.getResource()) {
            String key = SESSION_KEY_PREFIX + token;
            String json = jedis.get(key);
            if (json == null) return Optional.empty();
            SessionInfo session = JSON.parseObject(json, SessionInfo.class);
            // 续期
            SessionInfo renewed = new SessionInfo(session.username(), session.role(),
                    session.loginAt(), System.currentTimeMillis() / 1000);
            jedis.setex(key, SESSION_TTL_SECONDS, JSON.toJSONString(renewed));
            return Optional.of(renewed);
        }
    }

    /** 销毁会话 */
    public void destroySession(String token) {
        if (token == null || token.isBlank()) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.del(SESSION_KEY_PREFIX + token);
        }
    }

    /** 从 HTTP Authorization header 提取 Bearer token */
    public static String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return authHeader.substring(7).trim();
    }
}
