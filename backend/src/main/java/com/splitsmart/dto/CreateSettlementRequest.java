package com.splitsmart.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateSettlementRequest(
		@NotNull @Positive
		@Schema(minimum = "1")
		Long fromUserId,
		@NotNull @Positive
		@Schema(minimum = "1")
		Long toUserId,
		@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2)
		@Schema(minimum = "0.01", maximum = "99999999999999999.99", multipleOf = 0.01)
		BigDecimal amount,
		@NotBlank @Pattern(regexp = "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
		@Schema(
				minLength = 36,
				maxLength = 36,
				pattern = "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
		String requestId,
		@Size(max = 200)
		@Schema(maxLength = 200, nullable = true)
		String note) {

	public CreateSettlementRequest {
		if (requestId != null) {
			requestId = requestId.trim();
		}
		if (note != null) {
			note = note.trim();
		}
	}
}
