package com.splitsmart.service;

import java.net.URI;
import java.time.Instant;

/** Composes recovery messages independently of the chosen HTTPS transport. */
public final class EmailPasswordResetDelivery implements PasswordResetDelivery {
    private final TransactionalEmailClient emails;
    private final String origin;

    public EmailPasswordResetDelivery(TransactionalEmailClient emails, String origin) {
        URI uri;
        try { uri = URI.create(origin); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("A trusted HTTPS frontend origin is required."); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("A trusted HTTPS frontend origin is required.");
        }
        this.origin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        this.emails = emails;
    }

    @Override public boolean isAvailable() { return emails.isAvailable(); }
    @Override public boolean isConfigured() { return true; }

    @Override public void sendVerification(String recipient, String token, Instant expiresAt) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid verification token.");
        emails.send(recipient, "Verify your SplitSmart email", "Confirm ownership of your SplitSmart email:\n"
                + origin + "/verify-email#token=" + token + "\nExpires at " + expiresAt
                + ". Sign in to the account that requested this email, then confirm. Ignore this if you did not request it.");
    }
    @Override public void sendInvitation(String recipient, String groupName) {
        emails.send(recipient, "SplitSmart group invitation", "You have been invited to " + groupName
                + ". Sign in or register with this email, verify mailbox ownership, then accept in My groups:\n"
                + origin + "/groups\nAn invitation expires after seven days. Joining requires your acceptance.");
    }

    @Override
    public void sendReset(String recipient, String token, Instant expiresAt) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid recovery token format.");
        }
        // Fragment keeps the token out of initial HTTP requests/access logs and Referer headers.
        String link = origin + "/reset-password#token=" + token;
        emails.send(recipient, "Reset your SplitSmart password",
                "A password reset was requested for your SplitSmart account.\n\n"
                + "Open this link to choose a new password:\n" + link + "\n\n"
                + "This link expires at " + expiresAt + " and can be used once.\n"
                + "If you did not request this, ignore this message. Your password has not changed.\n"
                + "Never share this link or your password.");
    }
}
