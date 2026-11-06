package com.splitsmart.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RecoveryEmailTest {
    @Test
    void compositionUsesOnlyTrustedOriginAndFragmentWithoutProviderCoupling() {
        var captured = new AtomicReference<String>();
        TransactionalEmailClient transport = (to, subject, text) -> captured.set(text);
        var delivery = new EmailPasswordResetDelivery(transport, "https://splitsmart.example/");
        delivery.sendReset("recipient@example.invalid", "A".repeat(43), Instant.parse("2026-11-06T12:00:00Z"));
        assertTrue(captured.get().contains("https://splitsmart.example/reset-password#token="));
        assertFalse(captured.get().contains("?token="));
        assertTrue(captured.get().contains("2026-11-06T12:00:00Z"));
        for (String origin : new String[]{"http://example.com", "https://example.com/path", "https://user@example.com", "https://example.com?next=evil", "https://example.com/#fragment"}) {
            assertThrows(IllegalArgumentException.class, () -> new EmailPasswordResetDelivery(transport, origin));
        }
        assertThrows(IllegalArgumentException.class, () -> delivery.sendReset("recipient@example.invalid", "bad\nvalue", Instant.now()));
    }

    @Test
    void brevoUsesFixedHttpsEndpointWithPlainTextAndNoResponseExposure() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("api-key", "test-only-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.to[0].email").value("recipient@example.invalid"))
                .andExpect(jsonPath("$.sender.email").value("sender@example.invalid"))
                .andExpect(jsonPath("$.textContent").value("test-only-content"))
                .andExpect(jsonPath("$.htmlContent").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED).body("{\"messageId\":\"discarded\"}"));
        var transport = new BrevoTransactionalEmailClient(builder.build(), "test-only-key", "sender@example.invalid");
        transport.send("recipient@example.invalid", "Password reset", "test-only-content");
        assertTrue(transport.isAvailable());
        server.verify();
    }

    @Test
    void providerErrorsAndNetworkFailuresAreSanitizedAndDisableFurtherSending() {
        for (var response : java.util.List.of(
                withStatus(HttpStatus.UNAUTHORIZED).body("private-provider-body"),
                withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private-provider-body"),
                withStatus(HttpStatus.FOUND).header("Location", "https://untrusted.example"),
                withException(new java.io.IOException("private-network-detail")))) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.brevo.com/v3/smtp/email")).andRespond(response);
            var transport = new BrevoTransactionalEmailClient(builder.build(), "test-only-key", "sender@example.invalid");
            var error = assertThrows(IllegalStateException.class, () -> transport.send("recipient@example.invalid", "subject", "synthetic-secret"));
            assertFalse(error.getMessage().contains("private"));
            assertNull(error.getCause());
            assertFalse(transport.isAvailable());
            // Private delivery health must not become a public account-existence oracle.
            var delivery = new EmailPasswordResetDelivery(transport, "https://splitsmart.example");
            assertTrue(delivery.isConfigured());
            assertFalse(delivery.isAvailable());
            assertThrows(IllegalStateException.class, () -> transport.send("recipient@example.invalid", "subject", "synthetic-secret"));
            server.verify();
        }
    }
}
