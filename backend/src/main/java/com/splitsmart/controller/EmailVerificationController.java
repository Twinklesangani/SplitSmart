package com.splitsmart.controller;

import jakarta.validation.Valid;
import com.splitsmart.service.EmailVerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class EmailVerificationController {
    private final EmailVerificationService verification;
    public EmailVerificationController(EmailVerificationService verification) { this.verification=verification; }
    @PostMapping("/email-verification")
    public ResponseEntity<Void> request(@AuthenticationPrincipal Jwt jwt) {
        verification.request(jwt.getSubject()); return ResponseEntity.accepted().header("Cache-Control","no-store").build();
    }
    @PostMapping("/verify-email")
    public ResponseEntity<Void> confirm(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody InviteLinkController.Token request) {
        verification.confirm(jwt.getSubject(),request.token()); return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
