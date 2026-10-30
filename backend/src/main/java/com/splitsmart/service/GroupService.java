package com.splitsmart.service;

import java.util.List;
import java.util.Objects;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.splitsmart.dto.AddGroupMemberRequest;
import com.splitsmart.dto.CreateGroupRequest;
import com.splitsmart.dto.GroupMemberResponse;
import com.splitsmart.dto.GroupResponse;
import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.GroupMember;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.UserRepository;

@Service
public class GroupService {

	private static final String MEMBER_UNIQUE_CONSTRAINT = "uk_group_members_group_user";
	private static final String INVALID_USER_MESSAGE = "Invalid authenticated user.";

	private final UserRepository userRepository;
	private final ExpenseGroupRepository expenseGroupRepository;
	private final GroupMemberRepository groupMemberRepository;

	public GroupService(
			UserRepository userRepository,
			ExpenseGroupRepository expenseGroupRepository,
			GroupMemberRepository groupMemberRepository) {
		this.userRepository = userRepository;
		this.expenseGroupRepository = expenseGroupRepository;
		this.groupMemberRepository = groupMemberRepository;
	}

	@Transactional
	public GroupResponse createGroup(String subject, CreateGroupRequest request) {
		User creator = loadUser(subject);
		ExpenseGroup group = new ExpenseGroup(request.name(), creator);
		ExpenseGroup savedGroup = expenseGroupRepository.saveAndFlush(group);

		GroupMember membership = new GroupMember(savedGroup, creator);
		groupMemberRepository.saveAndFlush(membership);

		return toResponse(savedGroup);
	}

	@Transactional(readOnly = true)
	public List<GroupResponse> listGroups(String subject) {
		User member = loadUser(subject);
		return expenseGroupRepository.findAllForMemberOrderByIdDesc(member.getId()).stream()
				.map(GroupService::toResponse)
				.toList();
	}

	@Transactional
	public GroupMemberResponse addMember(Long groupId, String subject, AddGroupMemberRequest request) {
		User requester = loadUser(subject);
		ExpenseGroup group = loadGroup(groupId);
		requireMembership(groupId, requester.getId());
		if (!Objects.equals(group.getCreatedBy().getId(), requester.getId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the group creator can add members.");
		}

		User target = userRepository.findByEmail(request.email())
				.orElseThrow(() -> notFound("User not found."));
		if (groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, target.getId())) {
			throw conflict("User is already a group member.");
		}

		try {
			groupMemberRepository.saveAndFlush(new GroupMember(group, target));
		} catch (DataIntegrityViolationException exception) {
			if (isMemberUniqueConstraintViolation(exception)) {
				throw conflict("User is already a group member.");
			}
			throw exception;
		}

		return toMemberResponse(target);
	}

	@Transactional(readOnly = true)
	public List<GroupMemberResponse> listMembers(Long groupId, String subject) {
		User requester = loadUser(subject);
		loadGroup(groupId);
		requireMembership(groupId, requester.getId());

		return groupMemberRepository.findAllWithUserByGroupIdOrderByIdAsc(groupId).stream()
				.map(membership -> toMemberResponse(membership.getUser()))
				.toList();
	}

	private User loadUser(String subject) {
		final Long userId;
		if (subject == null) {
			throw invalidAuthenticatedUser();
		}
		try {
			userId = Long.valueOf(subject);
		} catch (NumberFormatException exception) {
			throw invalidAuthenticatedUser();
		}

		return userRepository.findById(userId)
				.orElseThrow(GroupService::invalidAuthenticatedUser);
	}

	private static ResponseStatusException invalidAuthenticatedUser() {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_USER_MESSAGE);
	}

	private ExpenseGroup loadGroup(Long groupId) {
		return expenseGroupRepository.findById(groupId)
				.orElseThrow(() -> notFound("Group not found."));
	}

	private void requireMembership(Long groupId, Long userId) {
		if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
			throw notFound("Group not found.");
		}
	}

	private static boolean isMemberUniqueConstraintViolation(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& MEMBER_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

	private static ResponseStatusException notFound(String message) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
	}

	private static ResponseStatusException conflict(String message) {
		return new ResponseStatusException(HttpStatus.CONFLICT, message);
	}

	private static GroupResponse toResponse(ExpenseGroup group) {
		return new GroupResponse(group.getId(), group.getName(), group.getCreatedBy().getId());
	}

	private static GroupMemberResponse toMemberResponse(User user) {
		return new GroupMemberResponse(user.getId(), user.getName(), user.getEmail());
	}
}
