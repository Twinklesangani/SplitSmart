package com.splitsmart.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record GroupBalanceResponse(
		Long groupId,
		String currencyCode,
		BigDecimal totalExpenseAmount,
		List<MemberBalanceResponse> balances) {

	public GroupBalanceResponse {
		if (balances != null) {
			balances = Collections.unmodifiableList(new ArrayList<>(balances));
		}
	}
}
