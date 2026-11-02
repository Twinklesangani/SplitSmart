package com.splitsmart.dto;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** Internal page metadata. HTTP bodies remain arrays with an optional next-cursor header. */
public record HistoryPage<T>(List<T> items, Long nextBefore) {
    public HistoryPage { items = List.copyOf(items); }

    public ResponseEntity<List<T>> response() {
        var builder = ResponseEntity.ok();
        if (nextBefore != null) builder.header("X-Next-Before", nextBefore.toString());
        return builder.body(items);
    }

    public static void validate(Long before, int limit) {
        if (limit < 1 || limit > 100 || before != null && before <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and 100, and before must be a positive record ID.");
        }
    }

    public static void validateParameters(org.springframework.util.MultiValueMap<String, String> parameters, Set<String> supported) {
        if (!supported.containsAll(parameters.keySet()) || parameters.entrySet().stream().anyMatch(entry ->
                entry.getValue().size() != 1 || entry.getValue().get(0).isBlank()
                || !entry.getKey().equals("requestId") && !entry.getValue().get(0).matches("[1-9][0-9]*"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or malformed history query parameter.");
        }
    }
}
