package com.splitsmart.config;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthAttemptLimiterTest {
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test
    void deniedAttemptsDoNotExtendWindowsAndExpiredStateIsReclaimed() {
        var clock = new MutableClock();
        var limiter = new AuthAttemptLimiter(2, 60, clock);
        assertEquals(0, limiter.acquire("target:a", 1));
        clock.now = clock.now.plusSeconds(59);
        assertEquals(1, limiter.acquire("target:a", 1));
        clock.now = clock.now.plusSeconds(1);
        assertEquals(0, limiter.acquire("target:b", 1));
        assertEquals(1, limiter.size());
        assertEquals(0, limiter.acquire("target:a", 1));
    }

    @Test
    void capacityFailsClosedWithoutEvictingAnActiveLimit() {
        var limiter = new AuthAttemptLimiter(2, 60, new MutableClock());
        assertEquals(0, limiter.acquire("a", 1));
        assertEquals(0, limiter.acquire("b", 1));
        for (int i = 0; i < 100; i++) assertTrue(limiter.acquire("new" + i, 1) > 0);
        assertEquals(2, limiter.size());
        assertTrue(limiter.acquire("a", 1) > 0);
    }

    @Test
    void concurrentRequestsCannotOvershootTheThreshold() throws Exception {
        var limiter = new AuthAttemptLimiter(16, 60, new MutableClock());
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(12)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (limiter.acquire("target", 3) == 0) allowed.incrementAndGet();
            }));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(3, allowed.get());
    }
}
