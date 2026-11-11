package com.splitsmart.config;

import java.time.Clock;
import java.time.Instant;
import com.splitsmart.service.PasswordResetDelivery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties({
        RecoveryEmailProperties.class, PasswordRecoveryProperties.class})
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
    PasswordResetDelivery passwordResetDelivery(RecoveryEmailProperties settings) {
        if (!settings.enabled() || !settings.deliveryVerified()) return unavailablePasswordResetDelivery();
        if (settings.apiKey() == null || settings.apiKey().isBlank()
                || settings.apiKey().chars().anyMatch(Character::isWhitespace)
                || settings.senderEmail() == null || !settings.senderEmail().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalStateException("Verified recovery email configuration is incomplete.");
        }
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        var transport = new com.splitsmart.service.BrevoTransactionalEmailClient(
                org.springframework.web.client.RestClient.builder().requestFactory(factory).build(),
                settings.apiKey(), settings.senderEmail());
        return new com.splitsmart.service.EmailPasswordResetDelivery(transport, settings.frontendOrigin());
    }

    // Kept separate so tests can verify there is no debug-token fallback.
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
