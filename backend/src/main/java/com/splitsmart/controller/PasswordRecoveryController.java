package com.splitsmart.controller;

import java.util.Map;
import jakarta.validation.Valid;
import com.splitsmart.dto.ForgotPasswordRequest;
import com.splitsmart.dto.ResetPasswordRequest;
import com.splitsmart.service.PasswordRecoveryService;
import com.splitsmart.service.PasswordResetDelivery;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class PasswordRecoveryController {
    private final PasswordRecoveryService recovery;
    private final PasswordResetDelivery delivery;
    public PasswordRecoveryController(PasswordRecoveryService recovery, PasswordResetDelivery delivery) {
        this.recovery = recovery;
        this.delivery = delivery;
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        // Global configuration only; identical for registered and unregistered email addresses.
        if (!delivery.isConfigured()) {
            return ResponseEntity.status(503).header("Cache-Control", "no-store")
                    .body(Map.of("message", "Email recovery is currently unavailable. Please try again later."));
        }
        recovery.requestReset(request.email());
        return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .body(Map.of("message", "Request received. If eligible, you may receive a reset email. Delivery is not confirmed."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        recovery.reset(request);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
