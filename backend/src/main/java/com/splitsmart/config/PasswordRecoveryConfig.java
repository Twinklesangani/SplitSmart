package com.splitsmart.config;

import java.time.Clock;
import java.time.Instant;
import com.splitsmart.service.PasswordResetDelivery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class PasswordRecoveryConfig {
    @Bean
    Clock passwordRecoveryClock() { return Clock.systemUTC(); }

    @Bean
    ThreadPoolTaskExecutor passwordRecoveryExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("password-recovery-");
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean(PasswordResetDelivery.class)
    PasswordResetDelivery unavailablePasswordResetDelivery() {
        // No provider, credentials, network calls, or development token shortcuts.
        return new PasswordResetDelivery() {
            public boolean isAvailable() { return false; }
            public void sendReset(String recipient, String token, Instant expiresAt) {
                throw new IllegalStateException("Password recovery delivery is not configured.");
            }
        };
    }
}
