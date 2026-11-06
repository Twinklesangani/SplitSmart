package com.splitsmart.controller;

import java.util.Map;
import com.splitsmart.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;
    public NotificationController(NotificationService notifications) { this.notifications=notifications; }
    @GetMapping
    public NotificationService.Page list(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) Long before,@RequestParam(defaultValue="20") int limit) {
        return notifications.list(jwt.getSubject(),before,limit);
    }
    @GetMapping("/unread-count")
    public Map<String,Long> unread(@AuthenticationPrincipal Jwt jwt) {return Map.of("count",notifications.unread(jwt.getSubject()));}
    public record ReadState(@jakarta.validation.constraints.NotNull Boolean read) {}
    @PutMapping("/{id}")
    public ResponseEntity<Void> mark(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@jakarta.validation.Valid @RequestBody ReadState state) {
        notifications.mark(jwt.getSubject(),id,state.read()); return ResponseEntity.noContent().build();
    }
}
