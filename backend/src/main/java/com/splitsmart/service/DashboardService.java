package com.splitsmart.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.splitsmart.dto.DashboardResponse;
import com.splitsmart.repository.ExpenseRepository;
import com.splitsmart.repository.SettlementRepository;

@Service
public class DashboardService {
    private final GroupService groups;
    private final ExpenseService expenses;
    private final ExpenseRepository expenseRepository;
    private final SettlementRepository settlements;

    public DashboardService(GroupService groups, ExpenseService expenses,
            ExpenseRepository expenseRepository, SettlementRepository settlements) {
        this.groups = groups;
        this.expenses = expenses;
        this.expenseRepository = expenseRepository;
        this.settlements = settlements;
    }

    @Transactional(readOnly = true)
    public DashboardResponse get(String subject) {
        var active = groups.listGroups(subject).stream().filter(group -> !group.archived()).toList();
        BigDecimal total = new BigDecimal("0.00");
        BigDecimal owed = new BigDecimal("0.00");
        BigDecimal receivable = new BigDecimal("0.00");
        var activity = new ArrayList<DashboardResponse.Activity>();
        for (var group : active) {
            var balances = expenses.getGroupBalances(group.id(), subject);
            total = total.add(balances.totalExpenseAmount());
            var balance = balances.balances().stream()
                    .filter(member -> member.userId().toString().equals(subject)).findFirst().orElseThrow();
            if (balance.netBalance().signum() < 0) owed = owed.subtract(balance.netBalance());
            else receivable = receivable.add(balance.netBalance());
            // Each group's latest ten contains every candidate for the global latest ten.
            var ids = expenseRepository.findHistoryIds(group.id(), null, PageRequest.of(0, 10));
            if (!ids.isEmpty()) {
                for (var expense : expenseRepository.findHistoryDetails(group.id(), ids)) {
                    activity.add(new DashboardResponse.Activity("EXPENSE", expense.getId(), group.id(), group.name(),
                            expense.getDescription(), expense.getAmount(), expense.getExpenseDate(),
                            expense.getCategory(), expense.getCreatedAt()));
                }
            }
            for (var settlement : settlements.findHistory(group.id(), null, null, PageRequest.of(0, 10))) {
                activity.add(new DashboardResponse.Activity("SETTLEMENT", settlement.getId(), group.id(), group.name(),
                        settlement.getFromUser().getName() + " repaid " + settlement.getToUser().getName(),
                        settlement.getAmount(), null, null, settlement.getCreatedAt()));
            }
        }
        var latest = activity.stream().sorted(Comparator.comparing(DashboardResponse.Activity::recordedAt)
                .reversed().thenComparing(DashboardResponse.Activity::type).thenComparing(DashboardResponse.Activity::id,
                        Comparator.reverseOrder())).limit(10).toList();
        return new DashboardResponse(total, owed, receivable, active, latest);
    }
}
