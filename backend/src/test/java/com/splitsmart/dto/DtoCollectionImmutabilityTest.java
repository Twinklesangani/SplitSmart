package com.splitsmart.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DtoCollectionImmutabilityTest {

	@Test
	void createExpenseRequestCopiesAndProtectsRequestCollections() {
		List<CreateExpenseSplitRequest> splits = new ArrayList<>();
		splits.add(null);
		List<Long> participantIds = new ArrayList<>(List.of(1L));
		CreateExpenseRequest request = new CreateExpenseRequest(
				"Dinner",
				new BigDecimal("10.00"),
				1L,
				null,
				splits,
				CreateExpenseRequest.SplitType.EQUAL,
				participantIds);

		assertDefensiveUnmodifiableCopy(splits, request.splits());
		assertDefensiveUnmodifiableCopy(participantIds, request.participantUserIds());
	}

	@Test
	void expenseResponseCopiesAndProtectsSplits() {
		List<ExpenseSplitResponse> splits = new ArrayList<>();
		splits.add(null);
		ExpenseResponse response = new ExpenseResponse(
				1L, 2L, "Dinner", new BigDecimal("10.00"), "INR", 3L, 3L, splits);

		assertDefensiveUnmodifiableCopy(splits, response.splits());
	}

	@Test
	void groupBalanceResponseCopiesAndProtectsBalances() {
		List<MemberBalanceResponse> balances = new ArrayList<>();
		balances.add(null);
		GroupBalanceResponse response = new GroupBalanceResponse(
				1L, "INR", new BigDecimal("10.00"), balances);

		assertDefensiveUnmodifiableCopy(balances, response.balances());
	}

	@Test
	void settlementSuggestionResponseCopiesAndProtectsSuggestions() {
		List<SuggestedTransferResponse> suggestions = new ArrayList<>();
		suggestions.add(null);
		SettlementSuggestionResponse response = new SettlementSuggestionResponse(
				1L, "INR", new BigDecimal("10.00"), suggestions);

		assertDefensiveUnmodifiableCopy(suggestions, response.suggestions());
	}

	private static <T> void assertDefensiveUnmodifiableCopy(List<T> source, List<T> exposed) {
		source.clear();
		assertEquals(1, exposed.size());
		assertThrows(UnsupportedOperationException.class, () -> exposed.add(null));
	}
}
