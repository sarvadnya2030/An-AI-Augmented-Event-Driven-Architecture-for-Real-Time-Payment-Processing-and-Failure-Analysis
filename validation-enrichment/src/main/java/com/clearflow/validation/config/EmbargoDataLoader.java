package com.clearflow.validation.config;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EmbargoDataLoader {

    private final StringRedisTemplate redisTemplate;

    public EmbargoDataLoader(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void load() {
        List<String> embargoed = List.of("IR", "KP", "SY", "CU", "SD", "MM", "RU");
        redisTemplate.opsForSet().add("embargoed:countries", embargoed.toArray(String[]::new));
    }
}
