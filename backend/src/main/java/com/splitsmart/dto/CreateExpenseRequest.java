package com.splitsmart.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;

public record CreateExpenseRequest(
		@NotBlank @Size(min = 1, max = 255)
		@Schema(minLength = 1, maxLength = 255, pattern = "\\S")
		String description,
		@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2)
		@Schema(minimum = "0.01", maximum = "99999999999999999.99", multipleOf = 0.01)
		BigDecimal amount,
		@NotNull @Positive
		@Schema(minimum = "1")
		Long paidByUserId,
		@Pattern(regexp = "INR")
		@Schema(
				nullable = true,
				minLength = 3,
				maxLength = 3,
				description = "Only INR is supported; omitted or null defaults to INR.",
				example = "INR")
		String currencyCode,
		@Schema(nullable = true)
		@ArraySchema(
				minItems = 1,
				arraySchema = @Schema(
						description = "Required with EXACT; null or omitted with EQUAL."))
		@Size(min = 1, max = 128) List<@Valid CreateExpenseSplitRequest> splits,
		@Schema(description = "Defaults to EXACT; EXACT requires splits, EQUAL requires participantUserIds.")
		SplitType splitType,
		@Schema(nullable = true)
		@ArraySchema(
				minItems = 1,
				arraySchema = @Schema(
						description = "Required with EQUAL; null or omitted with EXACT."),
				schema = @Schema(minimum = "1"))
		@Size(min = 1, max = 128) List<@NotNull @Positive Long> participantUserIds) {

	public enum SplitType {
		EXACT,
		EQUAL
	}

	public CreateExpenseRequest(
			String description,
			BigDecimal amount,
			Long paidByUserId,
			String currencyCode,
			List<CreateExpenseSplitRequest> splits) {
		this(description, amount, paidByUserId, currencyCode, splits, null, null);
	}

	public CreateExpenseRequest {
		if (description != null) {
			description = description.trim();
		}
		if (splits != null) {
			splits = Collections.unmodifiableList(new ArrayList<>(splits));
		}
		if (participantUserIds != null) {
			participantUserIds = Collections.unmodifiableList(new ArrayList<>(participantUserIds));
		}
	}
}
