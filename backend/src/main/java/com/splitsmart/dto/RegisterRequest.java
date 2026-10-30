package com.splitsmart.dto;

import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record RegisterRequest(
		@NotBlank @Size(min = 1, max = 120)
		@Schema(minLength = 1, maxLength = 120, pattern = "\\S")
		String name,
		@NotBlank @Email @Size(min = 1, max = 255)
		@Schema(minLength = 1, maxLength = 255, pattern = "\\S")
		String email,
		@NotBlank @Size(min = 8, max = 72)
		@Schema(
				minLength = 8,
				maxLength = 72,
				pattern = "\\S",
				description = "Password must contain at least 8 characters and no more than 72 UTF-8 bytes. "
						+ "OpenAPI string lengths count characters, not UTF-8 bytes; the server enforces the byte limit.")
		String password) {

	public RegisterRequest {
		if (name != null) {
			name = name.trim();
		}
		if (email != null) {
			email = email.trim().toLowerCase(Locale.ROOT);
		}
	}
}
