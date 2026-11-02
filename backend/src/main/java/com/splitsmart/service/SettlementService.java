package com.splitsmart.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.splitsmart.dto.CreateSettlementRequest;
import com.splitsmart.dto.GroupBalanceResponse;
import com.splitsmart.dto.MemberBalanceResponse;
import com.splitsmart.dto.SettlementResponse;
import com.splitsmart.dto.HistoryPage;
import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.Settlement;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.SettlementRepository;
import com.splitsmart.repository.UserRepository;

import jakarta.persistence.EntityManager;

@Service
public class SettlementService {

	private static final String REQUEST_UNIQUE_CONSTRAINT = "uk_settlements_group_request";

	private final ExpenseGroupRepository expenseGroupRepository;
	private final GroupMemberRepository groupMemberRepository;
	private final UserRepository userRepository;
	private final SettlementRepository settlementRepository;
	private final ExpenseService expenseService;
	private final EntityManager entityManager;

	public SettlementService(
			ExpenseGroupRepository expenseGroupRepository,
			GroupMemberRepository groupMemberRepository,
			UserRepository userRepository,
			SettlementRepository settlementRepository,
			ExpenseService expenseService,
			EntityManager entityManager) {
		this.expenseGroupRepository = expenseGroupRepository;
		this.groupMemberRepository = groupMemberRepository;
		this.userRepository = userRepository;
		this.settlementRepository = settlementRepository;
		this.expenseService = expenseService;
		this.entityManager = entityManager;
	}

	@Transactional
	public SettlementResponse createSettlement(
			Long groupId,
			String subject,
			CreateSettlementRequest request) {
		long requesterId = parseSubject(subject);
		ExpenseGroup group = expenseGroupRepository.findByIdForUpdate(groupId)
				.orElseThrow(() -> notFound("Group not found."));
		User requester = userRepository.findById(requesterId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.UNAUTHORIZED, "Invalid authenticated user."));
		requireMembership(groupId, requesterId);

		validateRequest(request);
		if (!request.fromUserId().equals(requesterId)) {
			throw new ResponseStatusException(
					HttpStatus.FORBIDDEN, "A member can only record their own payment.");
		}
		if (settlementRepository.existsByGroup_IdAndRequestId(groupId, request.requestId())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Settlement requestId already exists.");
		}
		if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, request.toUserId())) {
			throw badRequest("The settlement recipient must be a member of this group.");
		}

		GroupBalanceResponse balances = expenseService.getGroupBalances(groupId, subject);
		MemberBalanceResponse sender = findBalance(balances, request.fromUserId());
		MemberBalanceResponse receiver = findBalance(balances, request.toUserId());
		BigDecimal amount = request.amount();
		if (sender.netBalance().signum() >= 0 || receiver.netBalance().signum() <= 0
				|| amount.compareTo(sender.netBalance().abs()) > 0
				|| amount.compareTo(receiver.netBalance()) > 0) {
			throw badRequest("The settlement exceeds the current outstanding debt or receivable.");
		}

		Settlement settlement = new Settlement(
				group,
				requester,
				userRepository.getReferenceById(request.toUserId()),
				amount,
				requester,
				request.requestId(),
				request.note());
		try {
			Settlement saved = settlementRepository.saveAndFlush(settlement);
			entityManager.refresh(saved);
			return toResponse(saved);
		} catch (DataIntegrityViolationException exception) {
			if (isRequestUniqueConstraintViolation(exception)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Settlement requestId already exists.");
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public HistoryPage<SettlementResponse> listSettlements(Long groupId, String subject, Long before, int limit, String requestId) {
		long requesterId = parseSubject(subject);
		userRepository.findById(requesterId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.UNAUTHORIZED, "Invalid authenticated user."));
		if (!expenseGroupRepository.existsById(groupId)) {
			throw notFound("Group not found.");
		}
		requireMembership(groupId, requesterId);
        HistoryPage.validate(before, limit);
        if (requestId != null && (!requestId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}") || before != null)) {
            throw badRequest("requestId must be a UUID and cannot be combined with before.");
        }
        var fetched = settlementRepository.findHistory(groupId, before, requestId,
                org.springframework.data.domain.PageRequest.of(0, limit + 1));
        boolean more = fetched.size() > limit;
        var selected = more ? fetched.subList(0, limit) : fetched;
        return new HistoryPage<>(selected.stream().map(SettlementService::toResponse).toList(),
                more ? selected.get(selected.size() - 1).getId() : null);
	}

	private static long parseSubject(String subject) {
		if (subject == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authenticated user.");
		}
		try {
			return Long.parseLong(subject);
		} catch (NumberFormatException exception) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authenticated user.");
		}
	}

	private void validateRequest(CreateSettlementRequest request) {
		if (request == null || request.fromUserId() == null || request.fromUserId() <= 0
				|| request.toUserId() == null || request.toUserId() <= 0
				|| request.amount() == null || request.amount().signum() <= 0
				|| request.amount().scale() > 2 || request.amount().precision() - request.amount().scale() > 17
				|| request.requestId() == null || request.note() != null && request.note().length() > 200) {
			throw badRequest("Invalid settlement request.");
		}
		try {
			UUID.fromString(request.requestId());
		} catch (IllegalArgumentException exception) {
			throw badRequest("requestId must be a valid UUID.");
		}
		if (request.fromUserId().equals(request.toUserId())) {
			throw badRequest("Settlement sender and recipient must differ.");
		}
	}

	private MemberBalanceResponse findBalance(GroupBalanceResponse balances, Long userId) {
		return balances.balances().stream()
				.filter(balance -> balance.userId().equals(userId))
				.findFirst()
				.orElseThrow(() -> badRequest("Settlement participants must be current group members."));
	}

	private void requireMembership(Long groupId, Long userId) {
		if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
			throw notFound("Group not found.");
		}
	}

	private static boolean isRequestUniqueConstraintViolation(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& REQUEST_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

	private static SettlementResponse toResponse(Settlement settlement) {
		return new SettlementResponse(
				settlement.getId(),
				settlement.getGroup().getId(),
				settlement.getFromUser().getId(),
				settlement.getToUser().getId(),
				settlement.getAmount(),
				settlement.getRecordedBy().getId(),
				settlement.getRequestId(),
				settlement.getNote(),
				settlement.getCreatedAt());
	}

	private static ResponseStatusException notFound(String message) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}
}
