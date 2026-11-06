package com.splitsmart.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryEmailConfigTest {
    @Test
    void disabledDeliveryHasNoUsableEmailChannelOrTokenShortcut() {
        var delivery = new PasswordRecoveryConfig().passwordResetDelivery(
                new RecoveryEmailProperties(false, false, "", "", ""));
        assertFalse(delivery.isConfigured());
        assertFalse(delivery.isAvailable());
        var expiry = java.time.Instant.now().plusSeconds(1800);
        assertThrows(IllegalStateException.class, () -> delivery.sendReset("test@example.invalid", "test-only", expiry));
        assertThrows(IllegalStateException.class, () -> delivery.sendVerification("test@example.invalid", "test-only", expiry));
        assertThrows(IllegalStateException.class, () -> delivery.sendInvitation("test@example.invalid", "Test group"));
        assertThrows(IllegalStateException.class, () -> delivery.sendPasswordChanged("test@example.invalid"));
    }
    @Test
    void requiresBothExplicitEnableAndVerifiedDeliveryBeforeAvailability() {
        var config = new PasswordRecoveryConfig();
        for (var settings : new RecoveryEmailProperties[]{
                new RecoveryEmailProperties(false, false, "", "", ""),
                new RecoveryEmailProperties(true, false, "", "", ""),
                new RecoveryEmailProperties(false, true, "", "", "")}) {
            assertFalse(config.passwordResetDelivery(settings).isAvailable());
        }
        assertThrows(IllegalStateException.class, () -> config.passwordResetDelivery(
                new RecoveryEmailProperties(true, true, "", "", "")));
        var valid = new RecoveryEmailProperties(true, true, "test-only-key", "sender@example.invalid", "https://splitsmart.example");
        assertTrue(config.passwordResetDelivery(valid).isAvailable());
        assertFalse(valid.toString().contains("test-only-key"));
    }
}
