package com.splitsmart.controller;

import java.util.Map;
import jakarta.validation.Valid;
import com.splitsmart.dto.ForgotPasswordRequest;
import com.splitsmart.dto.ResetPasswordRequest;
import com.splitsmart.service.PasswordRecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class PasswordRecoveryController {
    private final PasswordRecoveryService recovery;
    public PasswordRecoveryController(PasswordRecoveryService recovery) { this.recovery = recovery; }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        recovery.requestReset(request.email());
        return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .body(Map.of("message", "If this account can receive recovery email, a reset link will be sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        recovery.reset(request);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
