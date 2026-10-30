package com.splitsmart.service;

import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.splitsmart.dto.AddGroupMemberRequest;
import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupServiceTest {

	private UserRepository userRepository;
	private ExpenseGroupRepository expenseGroupRepository;
	private GroupMemberRepository groupMemberRepository;
	private GroupService groupService;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		expenseGroupRepository = mock(ExpenseGroupRepository.class);
		groupMemberRepository = mock(GroupMemberRepository.class);
		groupService = new GroupService(userRepository, expenseGroupRepository, groupMemberRepository);
	}

	@Test
	void concurrentUniqueMembershipViolationReturnsConflict() {
		User creator = new User("Creator", "creator@example.com", "{bcrypt}hash");
		User target = new User("Target", "target@example.com", "{bcrypt}hash");
		ExpenseGroup group = new ExpenseGroup("Test group", creator);
		when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
		when(expenseGroupRepository.findById(5L)).thenReturn(Optional.of(group));
		when(groupMemberRepository.existsByGroup_IdAndUser_Id(5L, null)).thenReturn(true, false);
		when(userRepository.findByEmail("target@example.com")).thenReturn(Optional.of(target));
		when(groupMemberRepository.saveAndFlush(any())).thenThrow(
				new DataIntegrityViolationException("Duplicate group membership",
						new ConstraintViolationException(
								"Duplicate membership", null, "uk_group_members_group_user")));

		ResponseStatusException exception = assertThrows(
				ResponseStatusException.class,
				() -> groupService.addMember(5L, "1", new AddGroupMemberRequest("target@example.com")));

		assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
	}

	@Test
	void unrelatedIntegrityViolationIsNotReportedAsDuplicateMembership() {
		User creator = new User("Creator", "creator@example.com", "{bcrypt}hash");
		User target = new User("Target", "target@example.com", "{bcrypt}hash");
		ExpenseGroup group = new ExpenseGroup("Test group", creator);
		DataIntegrityViolationException databaseFailure = new DataIntegrityViolationException(
				"Different constraint violation",
				new ConstraintViolationException("Other constraint", null, "fk_other_constraint"));
		when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
		when(expenseGroupRepository.findById(5L)).thenReturn(Optional.of(group));
		when(groupMemberRepository.existsByGroup_IdAndUser_Id(5L, null)).thenReturn(true, false);
		when(userRepository.findByEmail("target@example.com")).thenReturn(Optional.of(target));
		when(groupMemberRepository.saveAndFlush(any())).thenThrow(databaseFailure);

		DataIntegrityViolationException exception = assertThrows(
				DataIntegrityViolationException.class,
				() -> groupService.addMember(5L, "1", new AddGroupMemberRequest("target@example.com")));

		assertSame(databaseFailure, exception);
	}

	@Test
	void missingAuthenticatedSubjectIsRejectedAsUnauthorized() {
		ResponseStatusException exception = assertThrows(
				ResponseStatusException.class,
				() -> groupService.listMembers(5L, null));

		assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
	}
}
