package com.splitsmart.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.Executor;
import com.splitsmart.dto.ResetPasswordRequest;
import com.splitsmart.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordRecoveryService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordResetDelivery delivery;
    private final Executor executor;
    private final Clock clock;

    public PasswordRecoveryService(UserRepository users, PasswordEncoder encoder, JdbcTemplate jdbc,
            PlatformTransactionManager manager, PasswordResetDelivery delivery,
            @Qualifier("passwordRecoveryExecutor") Executor executor, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.delivery = delivery;
        this.executor = executor;
        this.clock = clock;
    }

    public void requestReset(String email) {
        // Account lookup and delivery occur only on the bounded worker, never on the response path.
        try {
            executor.execute(() -> {
                try { issueAndDeliver(email); }
                catch (RuntimeException failure) {
                    // Do not attach provider/SQL exceptions: they may contain secrets or email bodies.
                    org.slf4j.LoggerFactory.getLogger(PasswordRecoveryService.class)
                            .warn("Password recovery processing failed.");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException overloaded) {
            // Same public response for every account when the queue is full; never run on caller.
        }
    }

    private void issueAndDeliver(String email) {
        if (!delivery.isAvailable()) return;
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String recipient = transactions.execute(status -> {
            var candidate = users.findByEmail(email);
            if (candidate.isEmpty()) return null;
            var user = users.findByIdForUpdate(candidate.get().getId()).orElseThrow();
            LocalDateTime now = now();
            // Persistent cooldown and hourly budget share the user lock with consumption.
            var last = jdbc.queryForObject("SELECT password_reset_requested_at FROM users WHERE id = ? FOR UPDATE",
                    LocalDateTime.class, user.getId());
            if (last != null && last.plusSeconds(60).isAfter(now)) return null;
            // Locking reads see current rows even under MySQL REPEATABLE READ snapshots.
            var recent = jdbc.queryForList("SELECT token_hash FROM password_reset_tokens WHERE user_id = ? AND created_at > ? FOR UPDATE",
                    String.class, user.getId(), now.minusHours(1));
            if (recent.size() >= 3) return null;
            jdbc.update("INSERT INTO password_reset_tokens(token_hash,user_id,created_at,expires_at) VALUES (?,?,?,?)",
                    hash(token), user.getId(), now, now.plusMinutes(30));
            jdbc.update("UPDATE users SET password_reset_requested_at = ? WHERE id = ?", now, user.getId());
            // Send only to the stored account address, including database-equivalent email aliases.
            return user.getEmail();
        });
        // Commit before delivery: a delivered link must already be usable.
        if (recipient != null) delivery.sendReset(recipient, token, clock.instant().plusSeconds(1800));
    }

    public void reset(ResetPasswordRequest request) {
        if (request.token() == null || !request.token().matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
        if (request.password() == null || request.password().isBlank() || request.password().length() < 8
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72
                || !request.password().equals(request.confirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the password and confirmation.");
        }
        String digest = hash(request.token());
        transactions.executeWithoutResult(status -> {
            var ids = jdbc.queryForList("SELECT user_id FROM password_reset_tokens WHERE token_hash = ?", Long.class, digest);
            if (ids.isEmpty()) throw invalidToken();
            // All reset operations for an account take this lock first, including different tokens.
            var user = users.findByIdForUpdate(ids.getFirst()).orElseThrow(PasswordRecoveryService::invalidToken);
            LocalDateTime now = now();
            int consumed = jdbc.update("UPDATE password_reset_tokens SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?",
                    now, digest, now);
            if (consumed != 1) throw invalidToken();
            user.setPasswordHash(encoder.encode(request.password()));
            user.invalidateAuthenticationTokens();
            users.saveAndFlush(user);
            jdbc.update("UPDATE password_reset_tokens SET consumed_at = ? WHERE user_id = ? AND consumed_at IS NULL",
                    now, user.getId());
            String recipient=user.getEmail();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() {
                            try { executor.execute(() -> {
                                if(!delivery.isAvailable()) return;
                                try { delivery.sendPasswordChanged(recipient); }
                                catch(RuntimeException ignored) { org.slf4j.LoggerFactory.getLogger(PasswordRecoveryService.class).warn("Password-change email processing failed."); }
                            }); } catch(java.util.concurrent.RejectedExecutionException ignored) { /* Password reset is committed; never undo it for delivery failure. */ }
                        }
                    });
        });
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "This reset link is invalid or has expired.");
    }
}
