package com.splitsmart.dto;

import java.math.BigDecimal;

public record MemberBalanceResponse(
		Long userId,
		String name,
		String email,
		BigDecimal totalPaid,
		BigDecimal totalShare,
		BigDecimal totalSentSettlements,
		BigDecimal totalReceivedSettlements,
		BigDecimal netBalance) {
}
