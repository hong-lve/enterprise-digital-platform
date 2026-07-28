package com.company.dataops.console.security;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Redis-backed login throttle - shared across every instance of this
 * service (behind a load balancer), unlike a plain in-process
 * ConcurrentHashMap which would let each instance track its own,
 * inconsistent attempt counts (an attacker spread across instances would
 * never trip any single instance's limit). Two independent limits:
 * per-username lockout stops brute-forcing one known account (e.g. admin),
 * per-IP lockout stops sweeping many usernames from one source to find out
 * which ones exist.
 *
 * Redis's own key TTL does the "rolling window" bookkeeping a hand-rolled
 * Instant/Duration Window class used to: INCR on the first failure creates
 * the key, EXPIRE(WINDOW) is set only that once, and every subsequent INCR
 * within the window just bumps the count without touching the TTL - the
 * window resets itself for free when the key expires, no separate cleanup
 * pass needed.
 */
@Component
public class LoginRateLimiter {
    private static final int MAX_ATTEMPTS_PER_USERNAME = 5;
    private static final int MAX_ATTEMPTS_PER_IP = 20;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String USERNAME_KEY_PREFIX = "ratelimit:username:";
    private static final String IP_KEY_PREFIX = "ratelimit:ip:";

    private final StringRedisTemplate redisTemplate;

    public LoginRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void assertNotLocked(String username, String ip) {
        check(USERNAME_KEY_PREFIX + username, MAX_ATTEMPTS_PER_USERNAME);
        check(IP_KEY_PREFIX + ip, MAX_ATTEMPTS_PER_IP);
    }

    public void recordFailure(String username, String ip) {
        increment(USERNAME_KEY_PREFIX + username);
        increment(IP_KEY_PREFIX + ip);
    }

    public void recordSuccess(String username, String ip) {
        redisTemplate.delete(USERNAME_KEY_PREFIX + username);
        redisTemplate.delete(IP_KEY_PREFIX + ip);
    }

    private void check(String key, int max) {
        String value = redisTemplate.opsForValue().get(key);
        if (value != null && Integer.parseInt(value) >= max) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后再试");
        }
    }

    private void increment(String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
    }
}
