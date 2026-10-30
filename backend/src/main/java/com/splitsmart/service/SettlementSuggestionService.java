package com.splitsmart.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.splitsmart.dto.GroupBalanceResponse;
import com.splitsmart.dto.MemberBalanceResponse;
import com.splitsmart.dto.SettlementSuggestionResponse;
import com.splitsmart.dto.SuggestedTransferResponse;

@Service
public class SettlementSuggestionService {

	private static final BigDecimal ZERO = new BigDecimal("0.00");
	private static final Comparator<RemainingBalance> LARGEST_FIRST =
			Comparator.comparing(RemainingBalance::amount, Comparator.reverseOrder())
					.thenComparing(RemainingBalance::userId);

	private final ExpenseService expenseService;

	public SettlementSuggestionService(ExpenseService expenseService) {
		this.expenseService = expenseService;
	}

	@Transactional(readOnly = true)
	public SettlementSuggestionResponse getSuggestions(Long groupId, String subject) {
		GroupBalanceResponse groupBalances = expenseService.getGroupBalances(groupId, subject);
		List<RemainingBalance> debtors = new ArrayList<>();
		List<RemainingBalance> creditors = new ArrayList<>();
		Map<Long, BigDecimal> simulatedBalances = new HashMap<>();
		BigDecimal totalBalance = ZERO;
		BigDecimal totalDebt = ZERO;
		BigDecimal totalReceivable = ZERO;

		for (MemberBalanceResponse member : groupBalances.balances()) {
			BigDecimal balance = normalize(member.netBalance());
			if (simulatedBalances.putIfAbsent(member.userId(), balance) != null) {
				throw new IllegalStateException("Group balance response contains a duplicate member.");
			}
			totalBalance = totalBalance.add(balance);
			if (balance.signum() < 0) {
				BigDecimal debt = balance.negate();
				debtors.add(new RemainingBalance(member.userId(), member.name(), debt));
				totalDebt = totalDebt.add(debt);
			} else if (balance.signum() > 0) {
				creditors.add(new RemainingBalance(member.userId(), member.name(), balance));
				totalReceivable = totalReceivable.add(balance);
			}
		}

		if (totalBalance.compareTo(ZERO) != 0 || totalDebt.compareTo(totalReceivable) != 0) {
			throw new IllegalStateException("Group balances do not reconcile to zero.");
		}

		List<SuggestedTransferResponse> suggestions = new ArrayList<>();
		BigDecimal totalSuggested = ZERO;
		while (!debtors.isEmpty() && !creditors.isEmpty()) {
			debtors.sort(LARGEST_FIRST);
			creditors.sort(LARGEST_FIRST);
			RemainingBalance debtor = debtors.get(0);
			RemainingBalance creditor = creditors.get(0);
			BigDecimal transfer = debtor.amount().min(creditor.amount())
					.setScale(2, RoundingMode.UNNECESSARY);
			if (transfer.signum() <= 0 || debtor.userId().equals(creditor.userId())) {
				throw new IllegalStateException("Invalid settlement suggestion was calculated.");
			}

			suggestions.add(new SuggestedTransferResponse(
					debtor.userId(),
					debtor.name(),
					creditor.userId(),
					creditor.name(),
					transfer));
			totalSuggested = totalSuggested.add(transfer);
			BigDecimal remainingDebt = debtor.amount().subtract(transfer);
			BigDecimal remainingReceivable = creditor.amount().subtract(transfer);
			debtor.setAmount(remainingDebt);
			creditor.setAmount(remainingReceivable);
			simulatedBalances.put(debtor.userId(), simulatedBalances.get(debtor.userId()).add(transfer));
			simulatedBalances.put(creditor.userId(), simulatedBalances.get(creditor.userId()).subtract(transfer));
			if (remainingDebt.signum() == 0) {
				debtors.remove(debtor);
			}
			if (remainingReceivable.signum() == 0) {
				creditors.remove(creditor);
			}
		}

		if (!debtors.isEmpty() || !creditors.isEmpty()
				|| totalSuggested.compareTo(totalDebt) != 0
				|| totalSuggested.compareTo(totalReceivable) != 0
				|| simulatedBalances.values().stream().anyMatch(balance -> balance.compareTo(ZERO) != 0)) {
			throw new IllegalStateException("Settlement suggestions do not fully reconcile group balances.");
		}

		return new SettlementSuggestionResponse(
				groupBalances.groupId(),
				groupBalances.currencyCode(),
				totalSuggested.setScale(2, RoundingMode.UNNECESSARY),
				List.copyOf(suggestions));
	}

	private static BigDecimal normalize(BigDecimal amount) {
		if (amount == null) {
			throw new IllegalStateException("Group balance response contains a missing balance.");
		}
		return amount.setScale(2, RoundingMode.UNNECESSARY);
	}

	private static final class RemainingBalance {

		private final Long userId;
		private final String name;
		private BigDecimal amount;

		private RemainingBalance(Long userId, String name, BigDecimal amount) {
			this.userId = userId;
			this.name = name;
			this.amount = amount;
		}

		private Long userId() {
			return userId;
		}

		private String name() {
			return name;
		}

		private BigDecimal amount() {
			return amount;
		}

		private void setAmount(BigDecimal amount) {
			this.amount = amount;
		}
	}
}
