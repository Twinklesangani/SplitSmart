package com.splitsmart.service;

/** Provider-independent server-side transport. Never log message bodies or recipients. */
public interface TransactionalEmailClient {
    default boolean isAvailable() { return true; }
    void send(String recipient, String subject, String text);
}
