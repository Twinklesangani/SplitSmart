package com.splitsmart.controller;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.splitsmart.dto.AddGroupMemberRequest;
import com.splitsmart.dto.InvitationResponse;
import com.splitsmart.service.InvitationService;

@RestController
@RequestMapping("/api")
public class InvitationController {
    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) { this.invitations = invitations; }

    @PostMapping("/groups/{groupId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(@PathVariable Long groupId,
            @Valid @RequestBody AddGroupMemberRequest request, @AuthenticationPrincipal Jwt jwt) {
        return invitations.invite(groupId, jwt.getSubject(), request);
    }

    @GetMapping("/invitations")
    public List<InvitationResponse> inbox(@AuthenticationPrincipal Jwt jwt) {
        return invitations.inbox(jwt.getSubject());
    }
    @GetMapping("/groups/{groupId}/invitations")
    public List<java.util.Map<String,Object>> sent(@PathVariable Long groupId,@AuthenticationPrincipal Jwt jwt) {
        return invitations.sent(groupId,jwt.getSubject());
    }
    @org.springframework.web.bind.annotation.DeleteMapping("/groups/{groupId}/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long groupId,@PathVariable Long id,@AuthenticationPrincipal Jwt jwt) {
        invitations.revoke(groupId,id,jwt.getSubject());
    }

    @PostMapping("/invitations/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        invitations.respond(id, jwt.getSubject(), true);
    }

    @PostMapping("/invitations/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        invitations.respond(id, jwt.getSubject(), false);
    }
}
