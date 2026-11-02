package com.splitsmart.config;

import org.springframework.lang.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AuthRateLimitException extends ResponseStatusException {
    private final @NonNull HttpHeaders headers;

    public AuthRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please wait and try again.");
        var values = new HttpHeaders();
        values.set("Retry-After", Long.toString(retryAfterSeconds));
        headers = HttpHeaders.readOnlyHttpHeaders(values);
    }

    @Override
    public @NonNull HttpHeaders getHeaders() {
        return headers;
    }
}
