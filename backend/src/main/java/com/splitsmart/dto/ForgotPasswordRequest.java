package com.splitsmart.dto;

import java.util.Locale;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 255) String email) {
    public ForgotPasswordRequest {
        if (email != null) email = email.trim().toLowerCase(Locale.ROOT);
    }
    @Override public String toString() { return "ForgotPasswordRequest[redacted]"; }
}
