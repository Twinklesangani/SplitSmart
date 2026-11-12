package com.splitsmart.service;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ControlledRecoveryTransportTest {
    @TempDir Path directory;
    private static final String RECIPIENT = "twinklesangani@gmail.com";
    private static final String RESET = "Reset your SplitSmart password";
    @Test void recipientTypeAndDatabaseFailClosedBeforeTransport() {
        AtomicInteger calls = new AtomicInteger();
        TransactionalEmailClient fake = (to, subject, body) -> calls.incrementAndGet();
        var allowed = new ControlledRecoveryTransport(directory, () -> true, fake);
        assertThrows(IllegalStateException.class, () -> allowed.send("other@example.invalid", RESET, "synthetic"));
        assertThrows(IllegalStateException.class, () -> allowed.send(RECIPIENT, "unknown", "synthetic"));
        assertThrows(IllegalStateException.class, () -> new ControlledRecoveryTransport(directory, () -> false, fake).send(RECIPIENT, RESET, "synthetic"));
        assertEquals(0, calls.get());
    }
    @Test void exactlyOneOfEachTypeSurvivesRestart() {
        AtomicInteger calls = new AtomicInteger();
        TransactionalEmailClient fake = (to, subject, body) -> calls.incrementAndGet();
        new ControlledRecoveryTransport(directory, () -> true, fake).send(RECIPIENT, RESET, "synthetic");
        var restarted = new ControlledRecoveryTransport(directory, () -> true, fake);
        assertThrows(IllegalStateException.class, () -> restarted.send(RECIPIENT, RESET, "synthetic"));
        restarted.send(RECIPIENT, "Your SplitSmart password changed", "synthetic");
        assertEquals(2, calls.get());
    }
    @Test void ambiguousFailurePermanentlyConsumesAttempt() {
        AtomicInteger calls = new AtomicInteger();
        var gate = new ControlledRecoveryTransport(directory, () -> true, (to, subject, body) -> {
            calls.incrementAndGet(); throw new IllegalStateException("synthetic failure");
        });
        assertThrows(IllegalStateException.class, () -> gate.send(RECIPIENT, RESET, "synthetic"));
        assertThrows(IllegalStateException.class, () -> gate.send(RECIPIENT, RESET, "synthetic"));
        assertEquals(1, calls.get());
    }
    @Test void concurrentAttemptsHaveExactlyOneWinner() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(executor.submit(() -> {
                try { new ControlledRecoveryTransport(directory, () -> true, (to,s,b) -> calls.incrementAndGet()).send(RECIPIENT, RESET, "synthetic"); }
                catch (IllegalStateException expected) { }
            }));
            for (var task : tasks) task.get();
        }
        assertEquals(1, calls.get());
    }
}
