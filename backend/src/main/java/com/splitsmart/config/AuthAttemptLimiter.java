package com.splitsmart.config;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Single-instance, bounded fixed windows. Denials never extend a window. */
public final class AuthAttemptLimiter {
    private record Window(long expiresAt, int count) {}
    private final Map<String, Window> windows = new LinkedHashMap<>();
    private final int capacity;
    private final long windowMillis;
    private final Clock clock;

    public AuthAttemptLimiter(int capacity, int windowSeconds, Clock clock) {
        this.capacity = capacity;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1000L);
        this.clock = clock;
    }

    public synchronized long acquire(String key, int limit) {
        long now = clock.millis();
        var iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt() > now) break;
            iterator.remove();
        }
        Window window = windows.get(key);
        if (window != null && window.count() >= limit) {
            return Math.max(1, (window.expiresAt() - now + 999) / 1000);
        }
        if (window == null) {
            // Fail closed instead of evicting active counters that an attacker could reset.
            if (windows.size() >= capacity) return Math.max(1, (windowMillis + 999) / 1000);
            windows.put(key, new Window(now + windowMillis, 1));
        } else {
            windows.put(key, new Window(window.expiresAt(), window.count() + 1));
        }
        return 0;
    }

    synchronized int size() { return windows.size(); }
}
