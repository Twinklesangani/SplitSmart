package com.splitsmart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@ConfigurationProperties("app.security")
@Validated
public record RequestProtectionProperties(
        @DefaultValue("60") @Min(1) @Max(3600) int limitWindowSeconds,
        @DefaultValue("60") @Min(1) int loginSourceLimit,
        @DefaultValue("10") @Min(1) int loginTargetLimit,
        @DefaultValue("10") @Min(1) int registrationSourceLimit,
        @DefaultValue("4096") @Min(2) @Max(100000) int maxLimiterEntries,
        @DefaultValue("4096") @Min(256) @Max(65536) int authBodyBytes,
        @DefaultValue("65536") @Min(256) @Max(1048576) int apiBodyBytes,
        @DefaultValue("2048") @Min(1) @Max(16384) int maxQueryChars,
        @DefaultValue("50") @Min(1) @Max(1000) int maxQueryParameters,
        @DefaultValue("") String trustedProxies) {
}
