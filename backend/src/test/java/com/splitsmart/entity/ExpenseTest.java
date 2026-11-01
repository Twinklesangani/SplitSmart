package com.splitsmart.entity;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpenseTest {
    @Test
    void readingSplitsCannotChangeTheExpense() {
        var expense = new Expense();
        var split = new ExpenseSplit(expense, new User(), BigDecimal.ONE);
        expense.replaceSplits(List.of(split));

        var snapshot = expense.getSplits();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        expense.replaceSplits(snapshot);
        assertEquals(List.of(split), expense.getSplits());
    }
}
