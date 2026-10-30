package com.splitsmart.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SettlementResponse(
		Long id,
		Long groupId,
		Long fromUserId,
		Long toUserId,
		BigDecimal amount,
		Long recordedByUserId,
		String requestId,
		String note,
		LocalDateTime createdAt) {
}
