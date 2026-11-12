package com.splitsmart.service;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;

/** Test-only, no-replay boundary. The caller must provide an owner-private directory. */
public final class ControlledRecoveryTransport implements TransactionalEmailClient {
    private final Path directory;
    private final BooleanSupplier syntheticIdentity;
    private final TransactionalEmailClient transport;

    public ControlledRecoveryTransport(Path directory, BooleanSupplier syntheticIdentity,
            TransactionalEmailClient transport) {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) throw stopped();
        this.directory = directory;
        this.syntheticIdentity = syntheticIdentity;
        this.transport = transport;
    }

    @Override public void send(String recipient, String subject, String text) {
        if (!"twinklesangani@gmail.com".equals(recipient) || !syntheticIdentity.getAsBoolean()) throw stopped();
        String kind = switch (subject) {
            case "Reset your SplitSmart password" -> "reset";
            case "Your SplitSmart password changed" -> "changed";
            default -> throw stopped();
        };
        try {
            // CREATE_NEW is atomic across threads/processes. Never remove this marker,
            // including after network errors: an unknown outcome consumes the budget.
            try (var file = FileChannel.open(directory.resolve(kind + ".attempt"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                file.write(ByteBuffer.wrap(new byte[] {1}));
                file.force(true);
            }
        } catch (Exception failure) { throw stopped(); }
        transport.send(recipient, subject, text);
        try {
            try (var file = FileChannel.open(directory.resolve(kind + ".accepted"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                file.write(ByteBuffer.wrap(new byte[] {1}));
                file.force(true);
            }
        } catch (Exception failure) { throw stopped(); }
    }

    private static IllegalStateException stopped() {
        return new IllegalStateException("Controlled email safety gate stopped the operation.");
    }
}
