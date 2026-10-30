package com.splitsmart.dto;

import java.math.BigDecimal;

public record SuggestedTransferResponse(
		Long fromUserId,
		String fromName,
		Long toUserId,
		String toName,
		BigDecimal amount) {
}
