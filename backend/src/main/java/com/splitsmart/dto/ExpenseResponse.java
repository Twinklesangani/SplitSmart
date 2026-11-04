package com.splitsmart.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ExpenseResponse(
		Long id,
		Long groupId,
		String description,
		BigDecimal amount,
		String currencyCode,
		Long paidByUserId,
		Long createdByUserId,
		List<ExpenseSplitResponse> splits,
        java.time.LocalDate expenseDate,
        com.splitsmart.entity.ExpenseCategory category) {

    public ExpenseResponse(Long id, Long groupId, String description, BigDecimal amount,
            String currencyCode, Long paidByUserId, Long createdByUserId, List<ExpenseSplitResponse> splits) {
        this(id, groupId, description, amount, currencyCode, paidByUserId, createdByUserId, splits, null, null);
    }

	public ExpenseResponse {
		if (splits != null) {
			splits = Collections.unmodifiableList(new ArrayList<>(splits));
		}
	}
}
