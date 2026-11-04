package com.splitsmart.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import com.splitsmart.entity.ExpenseCategory;

public record DashboardResponse(BigDecimal totalExpenses, BigDecimal owed, BigDecimal receivable,
        List<GroupResponse> groups, List<Activity> activity) {
    public record Activity(String type, Long id, Long groupId, String groupName, String description,
            BigDecimal amount, LocalDate expenseDate, ExpenseCategory category, LocalDateTime recordedAt) {}
}
