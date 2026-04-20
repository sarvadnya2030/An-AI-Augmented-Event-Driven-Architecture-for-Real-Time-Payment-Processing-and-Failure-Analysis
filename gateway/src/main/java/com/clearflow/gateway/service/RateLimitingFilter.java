package com.clearflow.gateway.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitingFilter {

    private static final long CAPACITY = 5_000;
    private static final long REFILL_PER_SECOND = 5_000;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public Mono<RateLimitDecision> checkLimit(String clientId) {
        State state = states.computeIfAbsent(clientId, key -> new State(CAPACITY, System.nanoTime()));
        synchronized (state) {
            long now = System.nanoTime();
            long elapsedNanos = now - state.lastRefillNanos;
            long refill = (elapsedNanos * REFILL_PER_SECOND) / 1_000_000_000L;
            if (refill > 0) {
                state.tokens = Math.min(CAPACITY, state.tokens + refill);
                state.lastRefillNanos = now;
            }
            boolean allowed = state.tokens > 0;
            if (allowed) {
                state.tokens--;
            }
            return Mono.just(new RateLimitDecision(allowed, state.tokens));
        }
    }

    public record RateLimitDecision(boolean allowed, long remaining) {
    }

    private static final class State {
        long tokens;
        long lastRefillNanos;

        State(long tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }
}
