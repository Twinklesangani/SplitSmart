package com.splitsmart.service;

import com.splitsmart.repository.ExpenseCreationOperationRepository;
import com.splitsmart.entity.ExpenseCreationOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Locale;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.splitsmart.dto.CreateExpenseRequest;
import com.splitsmart.dto.CreateExpenseSplitRequest;
import com.splitsmart.dto.CreateExpenseRequest.SplitType;
import com.splitsmart.dto.GroupBalanceResponse;
import com.splitsmart.dto.HistoryPage;
import com.splitsmart.dto.MemberBalanceResponse;
import com.splitsmart.dto.ExpenseResponse;
import com.splitsmart.dto.ExpenseSplitResponse;
import com.splitsmart.dto.UserAmountTotal;
import com.splitsmart.entity.Expense;
import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.ExpenseSplit;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.ExpenseRepository;
import com.splitsmart.repository.ExpenseSplitRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.SettlementRepository;
import com.splitsmart.repository.UserRepository;

@Service
public class ExpenseService {

	private static final String CURRENCY_CODE = "INR";
	private static final String INVALID_USER_MESSAGE = "Invalid authenticated user.";
	private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

	private final UserRepository userRepository;
	private final ExpenseGroupRepository expenseGroupRepository;
	private final GroupMemberRepository groupMemberRepository;
	private final ExpenseRepository expenseRepository;
	private final ExpenseSplitRepository expenseSplitRepository;
	private final SettlementRepository settlementRepository;
	private final EntityManager entityManager;
	private final ExpenseCreationOperationRepository operations;
	private final ObjectMapper objectMapper;

	public ExpenseService(
			UserRepository userRepository,
			ExpenseGroupRepository expenseGroupRepository,
			GroupMemberRepository groupMemberRepository,
			ExpenseRepository expenseRepository,
			ExpenseSplitRepository expenseSplitRepository,
			SettlementRepository settlementRepository,
			EntityManager entityManager,
			ExpenseCreationOperationRepository operations,
			ObjectMapper objectMapper) {
		this.userRepository = userRepository;
		this.expenseGroupRepository = expenseGroupRepository;
		this.groupMemberRepository = groupMemberRepository;
		this.expenseRepository = expenseRepository;
		this.expenseSplitRepository = expenseSplitRepository;
		this.settlementRepository = settlementRepository;
		this.entityManager = entityManager;
		this.operations = operations;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public ExpenseResponse createExpense(Long groupId, String subject, CreateExpenseRequest request, String operationHeader) {
		User requester = loadUser(subject);
		loadGroup(groupId);
		requireMembership(groupId, requester.getId());
		ExpenseGroup group = lockGroup(groupId);
		requireMembership(groupId, requester.getId());
		validateRequest(request);

		if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, request.paidByUserId())) {
			throw badRequest("The payer must be a member of this group.");
		}

		List<CreateExpenseSplitRequest> requestedSplits = validateAndResolveSplits(groupId, request);
		if (operationHeader == null || !operationHeader.matches(
				"\"[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\"")) {
			throw badRequest("Idempotency-Key must be a quoted UUID.");
		}
		String key = operationHeader.substring(1, 37).toLowerCase(Locale.ROOT);
		String fingerprint = creationFingerprint(request, requestedSplits);
		var previous = operations.findByGroupIdAndUserIdAndOperationKey(groupId, requester.getId(), key);
		if (previous.isPresent()) {
			if (!previous.get().getFingerprint().equals(fingerprint)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT,
						"Idempotency-Key was already used for different expense details.");
			}
			try {
				return objectMapper.readValue(previous.get().getResponseJson(), ExpenseResponse.class);
			} catch (JsonProcessingException exception) {
				throw new IllegalStateException("Could not read stored expense creation outcome.", exception);
			}
		}

		User payer = userRepository.getReferenceById(request.paidByUserId());
		Expense expense = expenseRepository.saveAndFlush(new Expense(
				group,
				request.description(),
				request.amount(),
				CURRENCY_CODE,
				payer,
				requester));

		List<ExpenseSplit> splits = new ArrayList<>(requestedSplits.size());
		for (CreateExpenseSplitRequest split : requestedSplits) {
			splits.add(new ExpenseSplit(expense, userRepository.getReferenceById(split.userId()), split.amount()));
		}
		List<ExpenseSplit> savedSplits = expenseSplitRepository.saveAllAndFlush(splits);
		expense.replaceSplits(savedSplits);
		ExpenseResponse response = toResponse(expense, savedSplits);
		try {
			operations.saveAndFlush(new ExpenseCreationOperation(
					groupId, requester.getId(), key, fingerprint, objectMapper.writeValueAsString(response)));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not store expense creation outcome.", exception);
		}
		return response;
	}

	private String creationFingerprint(CreateExpenseRequest request, List<CreateExpenseSplitRequest> splits) {
		var normalizedSplits = splits.stream().sorted(Comparator.comparing(CreateExpenseSplitRequest::userId))
				.map(split -> List.of(split.userId(), split.amount().stripTrailingZeros().toPlainString())).toList();
		var logicalRequest = List.of(request.description(), request.amount().stripTrailingZeros().toPlainString(),
				request.paidByUserId(), CURRENCY_CODE,
				request.splitType() == null ? SplitType.EXACT : request.splitType(), normalizedSplits);
		try {
			byte[] bytes = objectMapper.writeValueAsBytes(logicalRequest);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (JsonProcessingException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Could not fingerprint expense creation.", exception);
		}
	}

	@Transactional(readOnly = true)
	public ExpenseResponse getExpense(Long groupId, Long expenseId, String subject) {
		User requester = loadUser(subject);
		loadGroup(groupId);
		requireMembership(groupId, requester.getId());

		Expense expense = loadExpense(groupId, expenseId);
		return toResponse(expense, expense.getSplits());
	}

	@Transactional
	public ExpenseResponse updateExpense(
			Long groupId,
			Long expenseId,
			String subject,
			CreateExpenseRequest request) {
		User requester = loadUser(subject);
		loadGroup(groupId);
		requireMembership(groupId, requester.getId());
		lockGroup(groupId);
		requireMembership(groupId, requester.getId());

		Expense expense = loadExpense(groupId, expenseId);
		requireCreator(expense, requester);
		validateRequest(request);
		if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, request.paidByUserId())) {
			throw badRequest("The payer must be a member of this group.");
		}
		List<CreateExpenseSplitRequest> requestedSplits = validateAndResolveSplits(groupId, request);

		detachExistingSplits(expense);
		expenseSplitRepository.deleteAllByExpenseId(expenseId);
		expenseSplitRepository.flush();

		User payer = userRepository.getReferenceById(request.paidByUserId());
		expense.updateDetails(request.description(), request.amount(), payer);
		expenseRepository.flush();

		List<ExpenseSplit> replacements = new ArrayList<>(requestedSplits.size());
		for (CreateExpenseSplitRequest split : requestedSplits) {
			replacements.add(new ExpenseSplit(
					expense,
					userRepository.getReferenceById(split.userId()),
					split.amount()));
		}
		List<ExpenseSplit> savedSplits = expenseSplitRepository.saveAllAndFlush(replacements);
		expense.replaceSplits(savedSplits);
		return toResponse(expense, savedSplits);
	}

	@Transactional
	public void deleteExpense(Long groupId, Long expenseId, String subject) {
		User requester = loadUser(subject);
		loadGroup(groupId);
		requireMembership(groupId, requester.getId());
		lockGroup(groupId);
		requireMembership(groupId, requester.getId());

		Expense expense = loadExpense(groupId, expenseId);
		requireCreator(expense, requester);

		detachExistingSplits(expense);
		entityManager.detach(expense);
		expenseSplitRepository.deleteAllByExpenseId(expenseId);
		expenseSplitRepository.flush();
		int deleted = expenseRepository.deleteByIdAndGroupId(expenseId, groupId);
		if (deleted != 1) {
			throw notFound("Expense not found.");
		}
	}

	@Transactional(readOnly = true)
	public HistoryPage<ExpenseResponse> listExpenses(Long groupId, String subject, Long before, int limit) {
		User requester = loadUser(subject);
		loadGroup(groupId);
		requireMembership(groupId, requester.getId());

        HistoryPage.validate(before, limit);
        List<Long> fetched = expenseRepository.findHistoryIds(groupId, before,
                org.springframework.data.domain.PageRequest.of(0, limit + 1));
        boolean more = fetched.size() > limit;
        List<Long> ids = more ? fetched.subList(0, limit) : fetched;
        List<ExpenseResponse> items = ids.isEmpty() ? List.of()
                : expenseRepository.findHistoryDetails(groupId, ids).stream()
                    .map(expense -> toResponse(expense, expense.getSplits())).toList();
        return new HistoryPage<>(items, more ? ids.get(ids.size() - 1) : null);
	}

	@Transactional(readOnly = true)
	public GroupBalanceResponse getGroupBalances(Long groupId, String subject) {
		User requester = loadUser(subject);
		loadGroup(groupId);
		requireMembership(groupId, requester.getId());

		Map<Long, BigDecimal> paidByUser = totalsByUser(expenseRepository.sumPaidByUserForGroup(groupId));
		Map<Long, BigDecimal> sharesByUser = totalsByUser(expenseSplitRepository.sumSharesByUserForGroup(groupId));
		Map<Long, BigDecimal> sentByUser = totalsByUser(settlementRepository.sumSentByUserForGroup(groupId));
		Map<Long, BigDecimal> receivedByUser = totalsByUser(settlementRepository.sumReceivedByUserForGroup(groupId));
		BigDecimal totalExpenseAmount = sumAmounts(paidByUser.values());
		BigDecimal totalShareAmount = sumAmounts(sharesByUser.values());
		BigDecimal totalSentSettlements = sumAmounts(sentByUser.values());
		BigDecimal totalReceivedSettlements = sumAmounts(receivedByUser.values());

		List<MemberBalanceResponse> balances = groupMemberRepository
				.findAllWithUserByGroupIdOrderByIdAsc(groupId).stream()
				.sorted(Comparator.comparing(membership -> membership.getUser().getId()))
				.map(membership -> {
					User member = membership.getUser();
					BigDecimal totalPaid = paidByUser.getOrDefault(member.getId(), ZERO_AMOUNT);
					BigDecimal totalShare = sharesByUser.getOrDefault(member.getId(), ZERO_AMOUNT);
					BigDecimal totalSent = sentByUser.getOrDefault(member.getId(), ZERO_AMOUNT);
					BigDecimal totalReceived = receivedByUser.getOrDefault(member.getId(), ZERO_AMOUNT);
					return new MemberBalanceResponse(
							member.getId(),
							member.getName(),
							member.getEmail(),
							totalPaid,
							totalShare,
							totalSent,
							totalReceived,
							totalPaid.subtract(totalShare).add(totalSent).subtract(totalReceived));
				})
				.toList();

		BigDecimal memberPaidTotal = balances.stream()
				.map(MemberBalanceResponse::totalPaid)
				.reduce(ZERO_AMOUNT, BigDecimal::add);
		BigDecimal memberShareTotal = balances.stream()
				.map(MemberBalanceResponse::totalShare)
				.reduce(ZERO_AMOUNT, BigDecimal::add);
		BigDecimal netBalanceTotal = balances.stream()
				.map(MemberBalanceResponse::netBalance)
				.reduce(ZERO_AMOUNT, BigDecimal::add);
		BigDecimal memberSentTotal = balances.stream()
				.map(MemberBalanceResponse::totalSentSettlements)
				.reduce(ZERO_AMOUNT, BigDecimal::add);
		BigDecimal memberReceivedTotal = balances.stream()
				.map(MemberBalanceResponse::totalReceivedSettlements)
				.reduce(ZERO_AMOUNT, BigDecimal::add);
		if (memberPaidTotal.compareTo(totalExpenseAmount) != 0
				|| memberShareTotal.compareTo(totalExpenseAmount) != 0
				|| totalShareAmount.compareTo(totalExpenseAmount) != 0
				|| memberSentTotal.compareTo(memberReceivedTotal) != 0
				|| totalSentSettlements.compareTo(totalReceivedSettlements) != 0
				|| netBalanceTotal.compareTo(ZERO_AMOUNT) != 0) {
			throw new IllegalStateException("Persisted group expense totals are inconsistent.");
		}

		return new GroupBalanceResponse(groupId, CURRENCY_CODE, totalExpenseAmount, balances);
	}

	private static Map<Long, BigDecimal> totalsByUser(List<UserAmountTotal> totals) {
		Map<Long, BigDecimal> amounts = new HashMap<>();
		for (UserAmountTotal total : totals) {
			amounts.put(total.userId(), total.total());
		}
		return amounts;
	}

	private static BigDecimal sumAmounts(Iterable<BigDecimal> amounts) {
		BigDecimal total = ZERO_AMOUNT;
		for (BigDecimal amount : amounts) {
			total = total.add(amount);
		}
		return total;
	}

	private void validateRequest(CreateExpenseRequest request) {
		if (request == null || request.description() == null
				|| request.description().isBlank() || request.description().length() > 255) {
			throw badRequest("Description must be nonblank and at most 255 characters.");
		}
		validateAmount(request.amount(), "Expense amount");
		if (request.paidByUserId() == null || request.paidByUserId() <= 0) {
			throw badRequest("Payer must be a valid group member.");
		}
		if (request.currencyCode() != null && !CURRENCY_CODE.equals(request.currencyCode())) {
			throw badRequest("Only INR is supported.");
		}
		SplitType splitType = request.splitType() == null ? SplitType.EXACT : request.splitType();
		if (splitType == SplitType.EXACT) {
			if (request.splits() == null || request.splits().isEmpty()) {
				throw badRequest("At least one exact split is required.");
			}
			if (request.participantUserIds() != null) {
				throw badRequest("Exact splitting does not accept participantUserIds.");
			}
		} else if (splitType == SplitType.EQUAL) {
			if (request.participantUserIds() == null || request.participantUserIds().isEmpty()) {
				throw badRequest("Equal splitting requires at least one participant.");
			}
			if (request.splits() != null) {
				throw badRequest("Equal splitting does not accept exact splits.");
			}
		} else {
			throw badRequest("Unsupported split type.");
		}
	}

	private List<CreateExpenseSplitRequest> validateAndResolveSplits(
			Long groupId,
			CreateExpenseRequest request) {
		List<CreateExpenseSplitRequest> requestedSplits = resolveSplits(groupId, request);
		Set<Long> participantIds = new HashSet<>();
		BigDecimal splitTotal = BigDecimal.ZERO;
		for (CreateExpenseSplitRequest split : requestedSplits) {
			if (split == null || split.userId() == null || split.userId() <= 0) {
				throw badRequest("Each split must have a valid participant.");
			}
			if (!participantIds.add(split.userId())) {
				throw badRequest("A participant can only appear once in the splits.");
			}
			validateAmount(split.amount(), "Each split amount");
			if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, split.userId())) {
				throw badRequest("Each split participant must be a member of this group.");
			}
			splitTotal = splitTotal.add(split.amount());
		}
		if (splitTotal.compareTo(request.amount()) != 0) {
			throw badRequest("Split amounts must equal the expense amount.");
		}
		return requestedSplits;
	}

	private List<CreateExpenseSplitRequest> resolveSplits(Long groupId, CreateExpenseRequest request) {
		if (request.splitType() != SplitType.EQUAL) {
			return request.splits();
		}

		List<Long> participantIds = request.participantUserIds();
		Set<Long> uniqueParticipantIds = new HashSet<>();
		for (Long participantId : participantIds) {
			if (participantId == null || participantId <= 0) {
				throw badRequest("Each equal-split participant must have a valid user ID.");
			}
			if (!uniqueParticipantIds.add(participantId)) {
				throw badRequest("A participant can only appear once in an equal split.");
			}
			if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, participantId)) {
				throw badRequest("Each split participant must be a member of this group.");
			}
		}

		List<Long> sortedParticipantIds = participantIds.stream().sorted(Comparator.naturalOrder()).toList();
		BigInteger[] division = request.amount().movePointRight(2).toBigIntegerExact()
				.divideAndRemainder(BigInteger.valueOf(sortedParticipantIds.size()));
		if (division[0].signum() <= 0) {
			throw badRequest("Each equal split must be at least INR 0.01.");
		}
		int remainderCount = division[1].intValueExact();

		List<CreateExpenseSplitRequest> calculatedSplits = new ArrayList<>(sortedParticipantIds.size());
		for (int index = 0; index < sortedParticipantIds.size(); index++) {
			BigInteger shareMinorUnits = division[0];
			if (index < remainderCount) {
				shareMinorUnits = shareMinorUnits.add(BigInteger.ONE);
			}
			calculatedSplits.add(new CreateExpenseSplitRequest(
					sortedParticipantIds.get(index),
					new BigDecimal(shareMinorUnits, 2)));
		}
		return calculatedSplits;
	}

	private static void validateAmount(BigDecimal amount, String label) {
		if (amount == null || amount.signum() <= 0
				|| amount.scale() > 2 || amount.precision() - amount.scale() > 17) {
			throw badRequest(label + " must be positive, have at most two decimal places, and fit DECIMAL(19,2).");
		}
	}

	private User loadUser(String subject) {
		final Long userId;
		if (subject == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_USER_MESSAGE);
		}
		try {
			userId = Long.valueOf(subject);
		} catch (NumberFormatException exception) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_USER_MESSAGE);
		}

		return userRepository.findById(userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_USER_MESSAGE));
	}

	private ExpenseGroup loadGroup(Long groupId) {
		return expenseGroupRepository.findById(groupId)
				.orElseThrow(() -> notFound("Group not found."));
	}

	private ExpenseGroup lockGroup(Long groupId) {
		return expenseGroupRepository.findByIdForUpdate(groupId)
				.orElseThrow(() -> notFound("Group not found."));
	}

	private Expense loadExpense(Long groupId, Long expenseId) {
		return expenseRepository.findWithDetailsByIdAndGroupId(expenseId, groupId)
				.orElseThrow(() -> notFound("Expense not found."));
	}

	private void detachExistingSplits(Expense expense) {
		List<ExpenseSplit> existingSplits = new ArrayList<>(expense.getSplits());
		for (ExpenseSplit split : existingSplits) {
			entityManager.detach(split);
		}
		expense.replaceSplits(List.of());
	}

	private void requireMembership(Long groupId, Long userId) {
		if (!groupMemberRepository.existsByGroup_IdAndUser_Id(groupId, userId)) {
			throw notFound("Group not found.");
		}
	}

	private void requireCreator(Expense expense, User requester) {
		if (!expense.getCreatedBy().getId().equals(requester.getId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the expense creator can modify it.");
		}
	}

	private static ExpenseResponse toResponse(Expense expense, List<ExpenseSplit> splits) {
		List<ExpenseSplitResponse> splitResponses = splits.stream()
				.map(split -> new ExpenseSplitResponse(split.getUser().getId(), split.getAmount()))
				.toList();
		return new ExpenseResponse(
				expense.getId(),
				expense.getGroup().getId(),
				expense.getDescription(),
				expense.getAmount(),
				expense.getCurrencyCode(),
				expense.getPaidBy().getId(),
				expense.getCreatedBy().getId(),
				splitResponses);
	}

	private static ResponseStatusException notFound(String message) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}
}
