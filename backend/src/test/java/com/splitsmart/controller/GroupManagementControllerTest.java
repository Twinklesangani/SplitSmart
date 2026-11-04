package com.splitsmart.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import com.splitsmart.service.GroupManagementService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GroupManagementControllerTest {
    @Test
    void malformedAuthenticatedSubjectCannotLeaveAGroup() {
        var service = mock(GroupManagementService.class);
        var controller = new GroupManagementController(service);
        var jwt = Jwt.withTokenValue("test-only").header("alg", "HS256").subject("invalid-user").build();
        var failure = assertThrows(ResponseStatusException.class, () -> controller.leave(1L, jwt));
        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatusCode());
        verifyNoInteractions(service);
    }
}
