package com.splitsmart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.recovery.email")
public record RecoveryEmailProperties(boolean enabled, boolean deliveryVerified, String apiKey,
        String senderEmail, String frontendOrigin) {
    @Override public String toString() { return "RecoveryEmailProperties[redacted]"; }
}
