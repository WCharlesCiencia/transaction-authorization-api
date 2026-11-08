package com.fincard.authorization.service;

import java.time.Duration;
import java.util.Collections;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisIdempotencyService {

    private final StringRedisTemplate redisTemplate;

    private static final String PREFIX = "transaction:lock:";

    private static final Duration LOCK_TTL =
            Duration.ofSeconds(30);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
			new DefaultRedisScript<>("if redis.call('GET', KEYS[1]) == ARGV[1] then "
					+ "return redis.call('DEL', KEYS[1]) " + "else return 0 end", Long.class);

    public RedisIdempotencyService(
            StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // Adquirir bloqueo
    public boolean acquireLock(String requestId, String token) {

        String key = PREFIX + requestId;

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(
                        key,
                        token,
                        LOCK_TTL
                );

        return Boolean.TRUE.equals(acquired);
    }

    // Liberar bloqueo de forma segura
    public void releaseLock(String requestId, String token) {

        String key = PREFIX + requestId;

        redisTemplate.execute(
                RELEASE_SCRIPT,
                Collections.singletonList(key),
                token
        );
    }
}

