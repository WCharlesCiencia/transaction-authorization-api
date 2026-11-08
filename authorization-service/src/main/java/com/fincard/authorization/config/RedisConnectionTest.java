package com.fincard.authorization.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConnectionTest {

    @Bean
    CommandLineRunner testRedis(StringRedisTemplate redisTemplate) {
        return args -> {
            redisTemplate.opsForValue()
                    .set("test:connection", "Redis funcionando");

            String result = redisTemplate.opsForValue()
                    .get("test:connection");

            System.out.println("Redis: " + result);
        };
    }
}
