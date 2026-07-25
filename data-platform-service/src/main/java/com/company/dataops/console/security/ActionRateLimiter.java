package com.company.dataops.console.security;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Redis-backed fixed-window request counter for endpoints that do
 * real, expensive work per call - a Flink SQL query against a live cluster,
 * a query against a live registered data source, a javac/java subprocess
 * for the online JAR compiler. Unlike LoginRateLimiter (which only counts
 * *failures*, since the point there is stopping credential brute-forcing),
 * every call counts here - the point is stopping one user from hammering
 * an expensive operation, successful or not.
 *
 * Same Redis INCR/EXPIRE rolling-window trick as LoginRateLimiter: EXPIRE
 * is set only on the call that creates the key (count goes 0 -> 1), so the
 * window resets itself for free when the key's TTL elapses.
 */
@Component
public class ActionRateLimiter {
    private final StringRedisTemplate redisTemplate;

    public ActionRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void assertWithinLimit(String key, int maxRequestsPerWindow, Duration window) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, window);
        }
        if (count != null && count > maxRequestsPerWindow) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
        }
    }
}
