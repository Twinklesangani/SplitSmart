package com.splitsmart.dto;

import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(
		@NotBlank @Email @Size(min = 1, max = 255)
		@Schema(minLength = 1, maxLength = 255, pattern = "\\S")
		String email,
		@NotBlank @Schema(minLength = 1, pattern = "\\S")
		String password) {

	public LoginRequest {
		if (email != null) {
			email = email.trim().toLowerCase(Locale.ROOT);
		}
	}
}
