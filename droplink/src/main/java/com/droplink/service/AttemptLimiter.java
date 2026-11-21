package com.droplink.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most 5 wrong passwords per client and link in 15 minutes. It lives in memory of one app instance: with several
 * instances behind a load balancer each one counts for itself, which is a weaker but still useful brake.
 */
class AttemptLimiter {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_KEYS = 10_000;

    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    boolean blocked(String key, Instant now) {
        Deque<Instant> recent = failures.get(key);
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            prune(recent, now);
            return recent.size() >= MAX_FAILURES;
        }
    }

    void failed(String key, Instant now) {
        if (failures.size() > MAX_KEYS) {
            failures.clear();
        }
        Deque<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent, now);
            recent.addLast(now);
        }
    }

    void reset(String key) {
        failures.remove(key);
    }

    private static void prune(Deque<Instant> recent, Instant now) {
        while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
            recent.removeFirst();
        }
    }
}
