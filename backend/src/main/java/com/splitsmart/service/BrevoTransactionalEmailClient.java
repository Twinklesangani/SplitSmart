package com.splitsmart.service;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public final class BrevoTransactionalEmailClient implements TransactionalEmailClient {
    private final RestClient client;
    private final String apiKey;
    private final String sender;
    private volatile boolean available = true;

    public BrevoTransactionalEmailClient(RestClient client, String apiKey, String sender) {
        this.client = client;
        this.apiKey = apiKey;
        this.sender = sender;
    }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public void send(String recipient, String subject, String text) {
        if (!available) throw unavailable();
        try {
            client.post().uri("https://api.brevo.com/v3/smtp/email")
                    .header("api-key", apiKey).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("sender", Map.of("name", "SplitSmart", "email", sender),
                            "to", List.of(Map.of("email", recipient)), "subject", subject, "textContent", text))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 201) throw unavailable();
                        // Discard the provider response, including error bodies and message identifiers.
                        return null;
                    });
        } catch (RuntimeException failure) {
            // Fail closed until reviewed/restarted; never retry credentials/quota failures automatically.
            available = false;
            // Never propagate a response body, request body, credentials or transport exception cause.
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Recovery email could not be accepted by the provider.");
    }
}
