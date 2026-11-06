package com.splitsmart.config;

import com.splitsmart.entity.User;
import com.splitsmart.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserTokenVersionValidatorTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserTokenVersionValidator validator = new UserTokenVersionValidator(users);

    private Jwt token(Object version) {
        var builder = Jwt.withTokenValue("synthetic-test-token").header("alg", "HS256").subject("1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (version != null) builder.claim("ver", version);
        return builder.build();
    }

    @Test
    void legacyIsAcceptedOnlyAtVersionZeroAndMatchingVersionsAreRequired() {
        var user = new User("Test", "test@example.invalid", "synthetic-hash");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        assertFalse(validator.validate(token(null)).hasErrors());
        assertFalse(validator.validate(token(0L)).hasErrors());
        user.invalidateAuthenticationTokens();
        assertTrue(validator.validate(token(null)).hasErrors());
        assertTrue(validator.validate(token(0L)).hasErrors());
        assertFalse(validator.validate(token(1L)).hasErrors());
        assertTrue(validator.validate(token(2L)).hasErrors());
    }

    @Test
    void malformedVersionsMissingUsersAndDatabaseFailureFailClosed() {
        for (Object claim : new Object[]{-1L, "0", true, 0.5, java.util.List.of(0)}) {
            assertTrue(validator.validate(token(claim)).hasErrors());
        }
        when(users.findById(1L)).thenReturn(Optional.empty());
        assertTrue(validator.validate(token(0L)).hasErrors());
        when(users.findById(1L)).thenThrow(new IllegalStateException("Synthetic database outage"));
        assertTrue(validator.validate(token(0L)).hasErrors());
    }

    @Test
    void defaultDeliveryIsUnavailableAndProvidesNoTokenShortcut() {
        var delivery = new PasswordRecoveryConfig().unavailablePasswordResetDelivery();
        assertFalse(delivery.isAvailable());
        assertThrows(IllegalStateException.class, () -> delivery.sendReset("test@example.invalid", "synthetic", Instant.now()));
    }
}
