package com.splitsmart.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitsmart.dto.CreateExpenseRequest;
import com.splitsmart.dto.CreateExpenseSplitRequest;
import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseCreationOperationRepository;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.ExpenseRepository;
import com.splitsmart.repository.ExpenseSplitRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.SettlementRepository;
import com.splitsmart.repository.UserRepository;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ExpenseArchiveConcurrencyTest {
    @Test
    void currentLockedStateOverridesTheGroupSnapshotLoadedBeforeArchiveCommitted() {
        var users = mock(UserRepository.class);
        var groups = mock(ExpenseGroupRepository.class);
        var members = mock(GroupMemberRepository.class);
        var expenses = mock(ExpenseRepository.class);
        var splits = mock(ExpenseSplitRepository.class);
        var settlements = mock(SettlementRepository.class);
        var entityManager = mock(EntityManager.class);
        var operations = mock(ExpenseCreationOperationRepository.class);
        var user = new User("Owner", "owner@example.invalid", "test-only");
        ReflectionTestUtils.setField(user, "id", 1L);
        var cachedGroup = new ExpenseGroup("Archived concurrently", user);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(groups.findById(2L)).thenReturn(Optional.of(cachedGroup));
        when(groups.findByIdForUpdate(2L)).thenReturn(Optional.of(cachedGroup));
        when(members.existsByGroup_IdAndUser_Id(2L, 1L)).thenReturn(true);
        // MySQL's current locking read sees the archive committed while this request waited.
        doAnswer(invocation -> { cachedGroup.archive(); return null; })
                .when(entityManager).refresh(cachedGroup, LockModeType.PESSIMISTIC_WRITE);
        var service = new ExpenseService(users, groups, members, expenses, splits, settlements,
                entityManager, operations, new ObjectMapper());
        var request = new CreateExpenseRequest("Blocked write", new BigDecimal("10.00"), 1L, "INR",
                List.of(new CreateExpenseSplitRequest(1L, new BigDecimal("10.00"))));
        var failure = assertThrows(ResponseStatusException.class, () -> service.createExpense(
                2L, "1", request, "\"" + UUID.randomUUID() + "\""));
        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        verifyNoInteractions(expenses, splits, settlements, operations);
    }
}
