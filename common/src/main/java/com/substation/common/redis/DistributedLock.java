package com.substation.common.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.List;

public class DistributedLock {

    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private final JedisPool pool;
    private final String lockKey;
    private final String lockValue;

    public DistributedLock(JedisPool pool, String carId) {
        this.pool = pool;
        this.lockKey = LOCK_KEY_PREFIX + carId;
        this.lockValue = Thread.currentThread().getName() + "-" + System.currentTimeMillis();
    }

    public boolean tryLock() {
        return tryLock(DEFAULT_TIMEOUT_MS);
    }

    public boolean tryLock(int timeoutMs) {
        try (Jedis jedis = pool.getResource()) {
            SetParams params = SetParams.setParams().nx().px(timeoutMs);
            String result = jedis.set(lockKey, lockValue, params);
            return "OK".equals(result);
        }
    }

    public void unlock() {
        try (Jedis jedis = pool.getResource()) {
            String script =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "    return redis.call('del', KEYS[1]) " +
                "else " +
                "    return 0 " +
                "end";
            jedis.eval(script, Collections.singletonList(lockKey), Collections.singletonList(lockValue));
        }
    }
}
