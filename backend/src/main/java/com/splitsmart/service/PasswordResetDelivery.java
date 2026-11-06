package com.splitsmart.service;

import java.time.Instant;

/** Server-side delivery only. Implementations must never log tokens or message bodies.
 * Future adapters must use a configured trusted HTTPS origin, never request Host headers,
 * disable link tracking, and impose finite transport timeouts. */
public interface PasswordResetDelivery {
    boolean isAvailable();
    void sendReset(String recipient, String token, Instant expiresAt);
}
