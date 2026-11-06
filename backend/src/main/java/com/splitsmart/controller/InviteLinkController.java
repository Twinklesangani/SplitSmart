package com.splitsmart.controller;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import com.splitsmart.service.InviteLinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class InviteLinkController {
    private final InviteLinkService links;
    public InviteLinkController(InviteLinkService links) { this.links = links; }
    public record Token(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "Token[redacted]"; }
    }
    @PostMapping("/groups/{groupId}/invite-links")
    public ResponseEntity<InviteLinkService.Created> create(@PathVariable Long groupId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(links.create(groupId, jwt.getSubject()));
    }
    @GetMapping("/groups/{groupId}/invite-links")
    public List<Map<String, Object>> list(@PathVariable Long groupId, @AuthenticationPrincipal Jwt jwt) { return links.list(groupId, jwt.getSubject()); }
    @DeleteMapping("/groups/{groupId}/invite-links/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long groupId, @PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        links.revoke(groupId, id, jwt.getSubject()); return ResponseEntity.noContent().build();
    }
    @PostMapping("/invitations/link-accept")
    public Map<String, Long> accept(@Valid @RequestBody Token request, @AuthenticationPrincipal Jwt jwt) {
        return Map.of("groupId", links.accept(request.token(), jwt.getSubject()));
    }
}
