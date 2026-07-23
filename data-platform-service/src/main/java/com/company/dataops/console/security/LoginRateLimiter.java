package com.company.dataops.console.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * In-memory login throttle - this app runs as a single instance with no
 * shared cache, so a ConcurrentHashMap keyed by username/IP is enough; a
 * clustered deployment would need a shared store (e.g. Redis) instead.
 * Two independent limits: per-username lockout stops brute-forcing one
 * known account (e.g. admin), per-IP lockout stops sweeping many usernames
 * from one source to find out which ones exist.
 */
@Component
public class LoginRateLimiter {
    private static final int MAX_ATTEMPTS_PER_USERNAME = 5;
    private static final int MAX_ATTEMPTS_PER_IP = 20;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private static final class Window {
        final AtomicInteger count = new AtomicInteger();
        volatile Instant start = Instant.now();
    }

    private final ConcurrentHashMap<String, Window> byUsername = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> byIp = new ConcurrentHashMap<>();

    public void assertNotLocked(String username, String ip) {
        check(byUsername, username, MAX_ATTEMPTS_PER_USERNAME);
        check(byIp, ip, MAX_ATTEMPTS_PER_IP);
    }

    public void recordFailure(String username, String ip) {
        record(byUsername, username);
        record(byIp, ip);
    }

    public void recordSuccess(String username, String ip) {
        byUsername.remove(username);
        byIp.remove(ip);
    }

    private void check(ConcurrentHashMap<String, Window> store, String key, int max) {
        Window window = store.get(key);
        if (window == null) {
            return;
        }
        if (Duration.between(window.start, Instant.now()).compareTo(WINDOW) > 0) {
            store.remove(key);
            return;
        }
        if (window.count.get() >= max) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后再试");
        }
    }

    private void record(ConcurrentHashMap<String, Window> store, String key) {
        Window window = store.computeIfAbsent(key, ignored -> new Window());
        if (Duration.between(window.start, Instant.now()).compareTo(WINDOW) > 0) {
            window.start = Instant.now();
            window.count.set(0);
        }
        window.count.incrementAndGet();
    }
}
