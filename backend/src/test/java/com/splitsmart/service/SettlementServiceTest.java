package com.splitsmart.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.SettlementRepository;
import com.splitsmart.repository.UserRepository;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SettlementServiceTest {

	private SettlementService settlementService;

	@BeforeEach
	void setUp() {
		settlementService = new SettlementService(
				mock(ExpenseGroupRepository.class),
				mock(GroupMemberRepository.class),
				mock(UserRepository.class),
				mock(SettlementRepository.class),
				mock(ExpenseService.class),
				mock(EntityManager.class));
	}

	@Test
	void missingAuthenticatedSubjectIsRejectedAsUnauthorized() {
		ResponseStatusException exception = assertThrows(
				ResponseStatusException.class,
				() -> settlementService.listSettlements(1L, null, null, 25, null));

		assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
	}
}
