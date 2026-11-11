package com.splitsmart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Password reset policy that is safe to tune without changing the token schema. */
@ConfigurationProperties("app.recovery")
public record PasswordRecoveryProperties(@DefaultValue("30") int tokenLifetimeMinutes) {
    public PasswordRecoveryProperties {
        if (tokenLifetimeMinutes < 15 || tokenLifetimeMinutes > 30) {
            throw new IllegalArgumentException("Password recovery token lifetime must be between 15 and 30 minutes.");
        }
    }
}
