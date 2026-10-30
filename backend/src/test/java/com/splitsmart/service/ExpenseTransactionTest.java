package com.splitsmart.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.splitsmart.dto.CreateExpenseRequest;
import com.splitsmart.dto.CreateExpenseSplitRequest;
import com.splitsmart.dto.ExpenseResponse;
import com.splitsmart.entity.Expense;
import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.ExpenseSplit;
import com.splitsmart.entity.GroupMember;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.ExpenseRepository;
import com.splitsmart.repository.ExpenseSplitRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class ExpenseTransactionTest {

	@Autowired
	private ExpenseService expenseService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ExpenseGroupRepository expenseGroupRepository;

	@Autowired
	private GroupMemberRepository groupMemberRepository;

	@Autowired
	private ExpenseRepository expenseRepository;

	@Autowired
	private ExpenseSplitRepository expenseSplitRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoSpyBean
	private ExpenseSplitRepository splitRepositorySpy;

	@MockitoSpyBean
	private ExpenseGroupRepository groupRepositorySpy;

	@Test
	void missingAuthenticatedSubjectIsRejectedBeforeExpenseLookup() {
		ResponseStatusException exception = assertThrows(
				ResponseStatusException.class,
				() -> expenseService.createExpense(1L, null, null));

		assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
	}

	@Test
	void expenseMutationsAcquireTheSharedGroupLock() {
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);
		String suffix = UUID.randomUUID().toString();
		User creator = Objects.requireNonNull(transactions.execute(status -> userRepository.saveAndFlush(
				new User("Lock Creator", "lock.creator." + suffix + "@example.com", "{bcrypt}test"))));
		ExpenseGroup group = Objects.requireNonNull(transactions.execute(status -> {
			ExpenseGroup saved = expenseGroupRepository.saveAndFlush(new ExpenseGroup("Lock " + suffix, creator));
			groupMemberRepository.saveAndFlush(new GroupMember(saved, creator));
			return saved;
		}));
		CreateExpenseRequest request = new CreateExpenseRequest(
				"Lock verification",
				new BigDecimal("1.00"),
				creator.getId(),
				null,
				List.of(new CreateExpenseSplitRequest(creator.getId(), new BigDecimal("1.00"))));

		try {
			ExpenseResponse created = expenseService.createExpense(group.getId(), creator.getId().toString(), request);
			verify(groupRepositorySpy).findByIdForUpdate(group.getId());
			clearInvocations(groupRepositorySpy);

			expenseService.updateExpense(group.getId(), created.id(), creator.getId().toString(), request);
			verify(groupRepositorySpy).findByIdForUpdate(group.getId());
			clearInvocations(groupRepositorySpy);

			expenseService.deleteExpense(group.getId(), created.id(), creator.getId().toString());
			verify(groupRepositorySpy).findByIdForUpdate(group.getId());
		} finally {
			transactions.executeWithoutResult(status -> {
				List<Expense> expenses = expenseRepository.findAllByGroup_Id(group.getId());
				for (Expense expense : expenses) {
					expenseSplitRepository.deleteAll(expenseSplitRepository.findAllByExpense_Id(expense.getId()));
				}
				expenseRepository.deleteAll(expenses);
				groupMemberRepository.deleteAll(groupMemberRepository.findAll().stream()
						.filter(member -> member.getGroup().getId().equals(group.getId()))
						.toList());
				expenseGroupRepository.delete(group);
				userRepository.delete(creator);
			});
		}
	}

	@Test
	void splitPersistenceFailureRollsBackExpenseAndEverySplit() {
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);
		User creator = Objects.requireNonNull(transactions.execute(status -> userRepository.saveAndFlush(
				new User("Rollback Creator", "rollback.creator@example.com", "{bcrypt}test"))));
		ExpenseGroup group = Objects.requireNonNull(transactions.execute(status -> {
			ExpenseGroup saved = expenseGroupRepository.saveAndFlush(new ExpenseGroup("Rollback", creator));
			groupMemberRepository.saveAndFlush(new GroupMember(saved, creator));
			return saved;
		}));
		CreateExpenseRequest request = new CreateExpenseRequest(
				"Rollback expense",
				new BigDecimal("1.00"),
				creator.getId(),
				null,
				List.of(new CreateExpenseSplitRequest(creator.getId(), new BigDecimal("1.00"))));

		doThrow(new DataIntegrityViolationException("Simulated split persistence failure"))
				.when(splitRepositorySpy).saveAllAndFlush(any());
		try {
			assertNotNull(assertThrows(DataIntegrityViolationException.class,
					() -> expenseService.createExpense(group.getId(), creator.getId().toString(), request)));
			assertEquals(0, expenseRepository.countByGroup_Id(group.getId()));
			assertEquals(0, expenseSplitRepository.countByExpense_Group_Id(group.getId()));
		} finally {
			transactions.executeWithoutResult(status -> {
				List<Expense> expenses = expenseRepository.findAllByGroup_Id(group.getId());
				for (Expense expense : expenses) {
					expenseSplitRepository.deleteAll(expenseSplitRepository.findAllByExpense_Id(expense.getId()));
				}
				expenseRepository.deleteAll(expenses);
				groupMemberRepository.deleteAll(groupMemberRepository.findAll().stream()
						.filter(member -> member.getGroup().getId().equals(group.getId()))
						.toList());
				expenseGroupRepository.delete(group);
				userRepository.delete(creator);
			});
		}
	}

	@Test
	void replacementSplitFailureRollsBackExpenseAndPreviousShares() {
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);
		String suffix = UUID.randomUUID().toString();
		User creator = Objects.requireNonNull(transactions.execute(status -> userRepository.saveAndFlush(
				new User("Update Rollback Creator", "update.rollback." + suffix + "@example.com", "{bcrypt}test"))));
		ExpenseGroup group = Objects.requireNonNull(transactions.execute(status -> {
			ExpenseGroup saved = expenseGroupRepository.saveAndFlush(new ExpenseGroup("Update rollback " + suffix, creator));
			groupMemberRepository.saveAndFlush(new GroupMember(saved, creator));
			return saved;
		}));
		CreateExpenseRequest original = new CreateExpenseRequest(
				"Original before failed update",
				new BigDecimal("1.00"),
				creator.getId(),
				null,
				List.of(new CreateExpenseSplitRequest(creator.getId(), new BigDecimal("1.00"))));
		ExpenseResponse originalResponse = expenseService.createExpense(group.getId(), creator.getId().toString(), original);
		CreateExpenseRequest replacement = new CreateExpenseRequest(
				"Replacement that fails",
				new BigDecimal("2.00"),
				creator.getId(),
				null,
				List.of(new CreateExpenseSplitRequest(creator.getId(), new BigDecimal("2.00"))));

		doThrow(new DataIntegrityViolationException("Simulated replacement split persistence failure"))
				.when(splitRepositorySpy).saveAllAndFlush(any());
		try {
			assertNotNull(assertThrows(DataIntegrityViolationException.class,
					() -> expenseService.updateExpense(
							group.getId(), originalResponse.id(), creator.getId().toString(), replacement)));
			transactions.executeWithoutResult(status -> {
				Expense unchanged = expenseRepository.findById(originalResponse.id()).orElseThrow();
				assertEquals("Original before failed update", unchanged.getDescription());
				assertEquals(new BigDecimal("1.00"), unchanged.getAmount());
				List<ExpenseSplit> shares = expenseSplitRepository.findAllByExpense_Id(originalResponse.id());
				assertEquals(1, shares.size());
				assertEquals(new BigDecimal("1.00"), shares.get(0).getAmount());
			});
		} finally {
			transactions.executeWithoutResult(status -> {
				List<Expense> expenses = expenseRepository.findAllByGroup_Id(group.getId());
				for (Expense expense : expenses) {
					expenseSplitRepository.deleteAll(expenseSplitRepository.findAllByExpense_Id(expense.getId()));
				}
				expenseRepository.deleteAll(expenses);
				groupMemberRepository.deleteAll(groupMemberRepository.findAll().stream()
						.filter(member -> member.getGroup().getId().equals(group.getId()))
						.toList());
				expenseGroupRepository.delete(group);
				userRepository.delete(creator);
			});
		}
	}
}
