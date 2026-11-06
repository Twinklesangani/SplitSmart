package com.splitsmart.service;

import java.time.Instant;

/** Server-side delivery only. Implementations must never log tokens or message bodies.
 * Future adapters must use a configured trusted HTTPS origin, never request Host headers,
 * disable link tracking, and impose finite transport timeouts. */
public interface PasswordResetDelivery {
    /** Public configuration state must not change as a result of an individual account's delivery. */
    default boolean isConfigured() { return isAvailable(); }
    boolean isAvailable();
    void sendReset(String recipient, String token, Instant expiresAt);
    default void sendVerification(String recipient, String token, Instant expiresAt) { throw new IllegalStateException("Email verification unavailable."); }
    default void sendInvitation(String recipient, String groupName) { throw new IllegalStateException("Email invitations unavailable."); }
    default void sendPasswordChanged(String recipient) { throw new IllegalStateException("Password-change email unavailable."); }
}
