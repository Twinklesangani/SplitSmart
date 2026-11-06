package com.splitsmart.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryEmailConfigTest {
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
