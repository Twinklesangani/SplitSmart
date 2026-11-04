package com.splitsmart.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.splitsmart.service.GroupManagementService;

@RestController
public class GroupManagementController {
    private final GroupManagementService groups;
    public GroupManagementController(GroupManagementService groups) { this.groups = groups; }
    @PostMapping("/api/groups/{groupId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable Long groupId, @AuthenticationPrincipal Jwt jwt) {
        try {
            groups.remove(groupId, Long.valueOf(jwt.getSubject()), jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authenticated user.");
        }
    }
    @DeleteMapping("/api/groups/{groupId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long groupId, @PathVariable Long userId, @AuthenticationPrincipal Jwt jwt) {
        groups.remove(groupId, userId, jwt.getSubject());
    }
    @DeleteMapping("/api/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable Long groupId, @AuthenticationPrincipal Jwt jwt) {
        groups.archive(groupId, jwt.getSubject());
    }
}
