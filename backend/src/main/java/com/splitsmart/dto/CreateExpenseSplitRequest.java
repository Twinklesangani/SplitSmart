package com.splitsmart.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateExpenseSplitRequest(
		@NotNull @Positive
		@Schema(minimum = "1")
		Long userId,
		@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2)
		@Schema(minimum = "0.01", maximum = "99999999999999999.99", multipleOf = 0.01)
		BigDecimal amount) {
}
