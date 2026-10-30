package com.splitsmart.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SettlementSuggestionResponse(
		Long groupId,
		String currencyCode,
		BigDecimal totalSuggestedAmount,
		List<SuggestedTransferResponse> suggestions) {

	public SettlementSuggestionResponse {
		if (suggestions != null) {
			suggestions = Collections.unmodifiableList(new ArrayList<>(suggestions));
		}
	}
}
