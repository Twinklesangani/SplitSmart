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
    @org.springframework.beans.factory.annotation.Value("${app.database.vendor:mysql}")
    private String databaseVendor;
    private final GroupService groups;
    private final ExpenseService expenses;
    private final ExpenseRepository expenseRepository;
    private final SettlementRepository settlements;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DashboardService(GroupService groups, ExpenseService expenses,
            ExpenseRepository expenseRepository, SettlementRepository settlements, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.groups = groups;
        this.expenses = expenses;
        this.expenseRepository = expenseRepository;
        this.settlements = settlements;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DashboardResponse get(String subject) {
        var active = groups.listGroups(subject).stream().filter(group -> !group.archived()).toList();
        BigDecimal total = new BigDecimal("0.00");
        BigDecimal owed = new BigDecimal("0.00");
        BigDecimal receivable = new BigDecimal("0.00");
        var activity = new ArrayList<DashboardResponse.Activity>();
        var positions = new ArrayList<DashboardResponse.GroupPosition>();
        for (var group : active) {
            var balances = expenses.getGroupBalances(group.id(), subject);
            total = total.add(balances.totalExpenseAmount());
            var balance = balances.balances().stream()
                    .filter(member -> member.userId().toString().equals(subject)).findFirst().orElseThrow();
            if (balance.netBalance().signum() < 0) owed = owed.subtract(balance.netBalance());
            else receivable = receivable.add(balance.netBalance());
            positions.add(new DashboardResponse.GroupPosition(group.id(),balance.netBalance()));
            // Each group's latest ten contains every candidate for the global latest ten.
            var ids = expenseRepository.findHistoryIds(group.id(), null, PageRequest.of(0, 10));
            if (!ids.isEmpty()) {
                var instants=recordedInstants(false,group.id(),ids);
                for (var expense : expenseRepository.findHistoryDetails(group.id(), ids)) {
                    activity.add(new DashboardResponse.Activity("EXPENSE", expense.getId(), group.id(), group.name(),
                            expense.getDescription(), expense.getAmount(), expense.getExpenseDate(),
                            expense.getCategory(), instants.get(expense.getId())));
                }
            }
            var repayments=settlements.findHistory(group.id(), null, null, PageRequest.of(0, 10));
            var instants=recordedInstants(true,group.id(),repayments.stream().map(com.splitsmart.entity.Settlement::getId).toList());
            for (var settlement : repayments) {
                activity.add(new DashboardResponse.Activity("SETTLEMENT", settlement.getId(), group.id(), group.name(),
                        settlement.getFromUser().getName() + " repaid " + settlement.getToUser().getName(),
                        settlement.getAmount(), null, null, instants.get(settlement.getId())));
            }
        }
        var latest = activity.stream().sorted(Comparator.comparing(DashboardResponse.Activity::recordedAt)
                .reversed().thenComparing(DashboardResponse.Activity::type).thenComparing(DashboardResponse.Activity::id,
                        Comparator.reverseOrder())).limit(10).toList();
        return new DashboardResponse(total, owed, receivable, active, latest, receivable.subtract(owed), java.util.List.copyOf(positions));
    }
    private java.util.Map<Long,java.time.Instant> recordedInstants(boolean repayments,Long groupId,java.util.List<Long> ids) {
        if(ids.isEmpty()) return java.util.Map.of();
        // PostgreSQL stores these legacy LocalDateTime values explicitly as UTC.
        // MySQL TIMESTAMP epochs remain server-derived, preserving existing behavior.
        // Only the existing ten candidate IDs per type are read, using the group/id indexes.
        String table=repayments?"settlements":"expenses";
        String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        var arguments=new java.util.ArrayList<Object>();arguments.add(groupId);arguments.addAll(ids);
        String epochSql="postgresql".equals(databaseVendor)
                ? "EXTRACT(EPOCH FROM created_at AT TIME ZONE 'UTC')" : "UNIX_TIMESTAMP(created_at)";
        var rows=jdbc.query("SELECT id,"+epochSql+" AS epoch FROM "+table+" WHERE group_id=? AND id IN ("+placeholders+")",
                (row,index)->{
                    BigDecimal epoch=row.getBigDecimal("epoch");
                    return java.util.Map.entry(row.getLong("id"),java.time.Instant.ofEpochSecond(epoch.longValue(),epoch.remainder(BigDecimal.ONE).movePointRight(9).longValueExact()));
                },arguments.toArray());
        return rows.stream().collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,java.util.Map.Entry::getValue));
    }
}
