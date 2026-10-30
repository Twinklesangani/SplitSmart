package com.splitsmart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateGroupRequest(
		@NotBlank @Size(min = 1, max = 120)
		@Schema(minLength = 1, maxLength = 120, pattern = "\\S")
		String name) {

	public CreateGroupRequest {
		if (name != null) {
			name = name.trim();
		}
	}
}
