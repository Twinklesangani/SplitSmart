package com.splitsmart.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExpenseControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

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
	private JwtEncoder jwtEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void memberCreatesExactExpensePaidByAnotherMemberAndPersistsSplits() throws Exception {
		User creator = createUser("Expense Creator");
		User payer = createUser("Expense Payer");
		User participant = createUser("Expense Participant");
		ExpenseGroup group = createGroup("Dinner", creator);
		addMember(group, payer);
		addMember(group, participant);

		String response = postExpense(group.getId(), bearerToken(creator), """
				{
				  "description":"  Dinner  ",
				  "amount":0.60,
				  "paidByUserId":%d,
				  "createdByUserId":%d,
				  "splits":[
				    {"userId":%d,"amount":0.10},
				    {"userId":%d,"amount":0.50}
				  ]
				}
				""".formatted(payer.getId(), participant.getId(), creator.getId(), participant.getId()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.groupId").value(group.getId()))
				.andExpect(jsonPath("$.description").value("Dinner"))
				.andExpect(jsonPath("$.amount").value("0.60"))
				.andExpect(jsonPath("$.currencyCode").value("INR"))
				.andExpect(jsonPath("$.paidByUserId").value(payer.getId()))
				.andExpect(jsonPath("$.createdByUserId").value(creator.getId()))
				.andExpect(jsonPath("$.splits.length()").value(2))
				.andExpect(jsonPath("$.splits[0].userId").value(creator.getId()))
				.andExpect(jsonPath("$.splits[0].amount").value("0.10"))
				.andExpect(jsonPath("$.splits[1].amount").value("0.50"))
				.andReturn().getResponse().getContentAsString();

		long expenseId = objectMapper.readTree(response).get("id").asLong();
		Expense expense = expenseRepository.findById(expenseId).orElseThrow();
		assertEquals("Dinner", expense.getDescription());
		assertEquals(payer.getId(), expense.getPaidBy().getId());
		assertEquals(creator.getId(), expense.getCreatedBy().getId());
		assertEquals(1, expenseRepository.countByGroup_Id(group.getId()));
		assertEquals(2, expenseSplitRepository.countByExpense_Id(expenseId));
	}

	@Test
	void equalSplitsArePersistedInUserIdOrderWithDeterministicRemainder() throws Exception {
		User creator = createUser("Equal Creator");
		User middle = createUser("Equal Middle");
		User highest = createUser("Equal Highest");
		ExpenseGroup group = createGroup("Equal splitting", creator);
		addMember(group, middle);
		addMember(group, highest);

		postExpense(group.getId(), bearerToken(creator), equalExpenseJson(
				"Even amount", "900.00", creator.getId(),
				List.of(highest.getId(), creator.getId(), middle.getId())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.splits[0].userId").value(creator.getId()))
				.andExpect(jsonPath("$.splits[0].amount").value("300.00"))
				.andExpect(jsonPath("$.splits[1].amount").value("300.00"))
				.andExpect(jsonPath("$.splits[2].amount").value("300.00"));

		String response = postExpense(group.getId(), bearerToken(creator), """
				{
				  "description":"Odd paise",
				  "amount":1000.00,
				  "paidByUserId":%d,
				  "splitType":"EQUAL",
				  "participantUserIds":[%d,%d,%d]
				}
				""".formatted(creator.getId(), highest.getId(), middle.getId(), creator.getId()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.splits.length()").value(3))
				.andExpect(jsonPath("$.splits[0].userId").value(creator.getId()))
				.andExpect(jsonPath("$.splits[0].amount").value("333.34"))
				.andExpect(jsonPath("$.splits[1].userId").value(middle.getId()))
				.andExpect(jsonPath("$.splits[1].amount").value("333.33"))
				.andExpect(jsonPath("$.splits[2].userId").value(highest.getId()))
				.andExpect(jsonPath("$.splits[2].amount").value("333.33"))
				.andReturn().getResponse().getContentAsString();
		long expenseId = objectMapper.readTree(response).get("id").asLong();
		List<ExpenseSplit> persisted = expenseSplitRepository.findAllByExpense_Id(expenseId);
		assertEquals(3, persisted.size());
		assertEquals(new BigDecimal("1000.00"),
				persisted.stream().map(ExpenseSplit::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
		assertEquals(new BigDecimal("333.34"), persisted.stream()
				.filter(split -> split.getUser().getId().equals(creator.getId()))
				.findFirst().orElseThrow().getAmount());
	}

	@Test
	void equalSplitSupportsPaiseSingleParticipantAndMaximumDecimalAmount() throws Exception {
		User creator = createUser("Equal Edge Creator");
		User member = createUser("Equal Edge Member");
		User other = createUser("Equal Edge Other");
		ExpenseGroup group = createGroup("Equal edge cases", creator);
		addMember(group, member);
		addMember(group, other);

		postExpense(group.getId(), bearerToken(creator), equalExpenseJson(
				"Three paise", "0.03", creator.getId(), List.of(other.getId(), member.getId(), creator.getId())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.splits[0].amount").value("0.01"))
				.andExpect(jsonPath("$.splits[1].amount").value("0.01"))
				.andExpect(jsonPath("$.splits[2].amount").value("0.01"));

		postExpense(group.getId(), bearerToken(creator), equalExpenseJson(
				"One participant", "100.00", creator.getId(), List.of(member.getId())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.splits.length()").value(1))
				.andExpect(jsonPath("$.splits[0].userId").value(member.getId()))
				.andExpect(jsonPath("$.splits[0].amount").value("100.00"));

		String maximumResponse = postExpense(group.getId(), bearerToken(creator), equalExpenseJson(
				"Maximum amount", "99999999999999999.99", creator.getId(),
				List.of(other.getId(), creator.getId(), member.getId())))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var shares = objectMapper.reader()
				.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
				.readTree(maximumResponse).get("splits");
		assertEquals(3, shares.size());
		assertEquals(creator.getId().longValue(), shares.get(0).get("userId").asLong());
		assertEquals(new BigDecimal("33333333333333333.33"), new BigDecimal(shares.get(0).get("amount").asText()));
		assertEquals(new BigDecimal("33333333333333333.33"), new BigDecimal(shares.get(1).get("amount").asText()));
		assertEquals(new BigDecimal("33333333333333333.33"), new BigDecimal(shares.get(2).get("amount").asText()));
		assertEquals(new BigDecimal("99999999999999999.99"), java.util.stream.StreamSupport
				.stream(shares.spliterator(), false)
				.map(node -> new BigDecimal(node.get("amount").asText()))
				.reduce(BigDecimal.ZERO, BigDecimal::add));
		long maxExpenseId = objectMapper.readTree(maximumResponse).get("id").asLong();
		List<ExpenseSplit> persisted = expenseSplitRepository.findAllByExpense_Id(maxExpenseId);
		assertEquals(new BigDecimal("99999999999999999.99"),
				persisted.stream().map(ExpenseSplit::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
	}

	@Test
	void equalSplitRejectsInvalidParticipantsAmountsAndModeFieldsWithoutSaving() throws Exception {
		User creator = createUser("Equal Invalid Creator");
		User member = createUser("Equal Invalid Member");
		User thirdMember = createUser("Equal Invalid Third Member");
		User unrelated = createUser("Equal Invalid Unrelated");
		ExpenseGroup group = createGroup("Equal invalid requests", creator);
		addMember(group, member);
		addMember(group, thirdMember);
		String token = bearerToken(creator);

		for (String invalid : List.of(
				"{\"description\":\"Too few paise\",\"amount\":0.02,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EQUAL\",\"participantUserIds\":[" + creator.getId() + ","
						+ member.getId() + "," + thirdMember.getId() + "]}",
				"{\"description\":\"Empty\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EQUAL\",\"participantUserIds\":[]}",
				"{\"description\":\"Missing\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EQUAL\"}",
				"{\"description\":\"Null participant\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EQUAL\",\"participantUserIds\":[" + creator.getId() + ",null]}",
				"{\"description\":\"Duplicate participant\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EQUAL\",\"participantUserIds\":[" + creator.getId() + ","
						+ creator.getId() + "]}",
				"{\"description\":\"Outside participant\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EQUAL\",\"participantUserIds\":[" + unrelated.getId() + "]}",
				"{\"description\":\"Outside payer\",\"amount\":1.00,\"paidByUserId\":" + unrelated.getId()
						+ ",\"splitType\":\"EQUAL\",\"participantUserIds\":[" + creator.getId() + "]}",
				"{\"description\":\"Precision\",\"amount\":1.001,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EQUAL\",\"participantUserIds\":[" + creator.getId() + "]}",
				"{\"description\":\"Amount exceeds DECIMAL(19,2)\",\"amount\":100000000000000000.00,"
						+ "\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EQUAL\",\"participantUserIds\":[" + creator.getId() + "]}",
				"{\"description\":\"Split exceeds DECIMAL(19,2)\",\"amount\":1.00,\"paidByUserId\":"
						+ creator.getId() + ",\"splits\":[{\"userId\":" + creator.getId()
						+ ",\"amount\":100000000000000000}]}",
				"{\"description\":\"Both modes\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EQUAL\",\"participantUserIds\":[" + creator.getId()
						+ "],\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.00}]}",
				"{\"description\":\"Wrong mode field\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"EXACT\",\"participantUserIds\":[" + creator.getId()
						+ "],\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.00}]}",
				"{\"description\":\"Unknown mode\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splitType\":\"PERCENTAGE\",\"participantUserIds\":[" + creator.getId() + "]}"
		)) {
			postExpense(group.getId(), token, invalid).andExpect(status().isBadRequest());
		}

		assertEquals(0, expenseRepository.countByGroup_Id(group.getId()));
		assertEquals(0, expenseSplitRepository.countByExpense_Group_Id(group.getId()));
		postExpense(group.getId(), bearerToken(unrelated), equalExpenseJson(
				"Private equal", "1.00", creator.getId(), List.of(creator.getId())))
				.andExpect(status().isNotFound());
	}

	@Test
	void exactModeCanBeExplicitAndEqualModeRequiresAuthentication() throws Exception {
		User creator = createUser("Split Mode Creator");
		ExpenseGroup group = createGroup("Split mode", creator);
		postExpense(group.getId(), bearerToken(creator), """
				{"description":"Explicit exact","amount":2.00,"paidByUserId":%d,
				 "splitType":"EXACT","splits":[{"userId":%d,"amount":2.00}]}
				""".formatted(creator.getId(), creator.getId()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.splits[0].amount").value("2.00"));
		mockMvc.perform(post("/api/groups/{groupId}/expenses", group.getId())
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(equalExpenseJson("Unauthenticated equal", "1.00",
								creator.getId(), List.of(creator.getId()))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void memberReadsExpenseButCannotUseAnotherGroupIdOrAccessPrivateExpense() throws Exception {
		User creator = createUser("Detail Creator");
		User member = createUser("Detail Member");
		User unrelated = createUser("Detail Unrelated");
		ExpenseGroup group = createGroup("Detail group", creator);
		ExpenseGroup otherGroup = createGroup("Other detail group", creator);
		addMember(group, member);
		String response = postExpense(group.getId(), bearerToken(creator),
				expenseJson(creator, member, "Readable", "5.00"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long expenseId = objectMapper.readTree(response).get("id").asLong();

		mockMvc.perform(get("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId)
						.header("Authorization", bearerToken(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(expenseId))
				.andExpect(jsonPath("$.groupId").value(group.getId()))
				.andExpect(jsonPath("$.amount").value("5.00"))
				.andExpect(jsonPath("$.paidByUserId").value(creator.getId()))
				.andExpect(jsonPath("$.createdByUserId").value(creator.getId()))
				.andExpect(jsonPath("$.splits[0].userId").value(member.getId()))
				.andExpect(jsonPath("$.splits[0].amount").value("5.00"));

		mockMvc.perform(get("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId)
						.header("Authorization", bearerToken(unrelated)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/expenses/{expenseId}", otherGroup.getId(), expenseId)
						.header("Authorization", bearerToken(creator)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), 99999999L)
						.header("Authorization", bearerToken(creator)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void creatorUpdatesExpenseAndReplacesExactAndEqualSharesWithoutChangingIdentity() throws Exception {
		User creator = createUser("Update Creator");
		User member = createUser("Update Member");
		User participant = createUser("Update Participant");
		ExpenseGroup group = createGroup("Update expense", creator);
		addMember(group, member);
		addMember(group, participant);
		String initialJson = postExpense(group.getId(), bearerToken(creator),
				expenseJson(member, creator, "Original", "9.00"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long expenseId = objectMapper.readTree(initialJson).get("id").asLong();
		Timestamp originalCreatedAt = jdbcTemplate.queryForObject(
				"SELECT created_at FROM expenses WHERE id = ?", Timestamp.class, expenseId);

		String equalUpdate = """
				{"description":"  Updated Dinner  ","amount":10.00,"paidByUserId":%d,
				 "splitType":"EQUAL","participantUserIds":[%d,%d,%d],
				 "createdByUserId":%d}
				""".formatted(member.getId(), participant.getId(), creator.getId(), member.getId(), member.getId());
		mockMvc.perform(put("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId)
						.header("Authorization", bearerToken(creator))
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(equalUpdate))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(expenseId))
				.andExpect(jsonPath("$.description").value("Updated Dinner"))
				.andExpect(jsonPath("$.amount").value("10.00"))
				.andExpect(jsonPath("$.paidByUserId").value(member.getId()))
				.andExpect(jsonPath("$.createdByUserId").value(creator.getId()))
				.andExpect(jsonPath("$.splits.length()").value(3))
				.andExpect(jsonPath("$.splits[0].userId").value(creator.getId()))
				.andExpect(jsonPath("$.splits[0].amount").value("3.34"))
				.andExpect(jsonPath("$.splits[1].amount").value("3.33"))
				.andExpect(jsonPath("$.splits[2].amount").value("3.33"));

		List<ExpenseSplit> equalShares = expenseSplitRepository.findAllByExpense_Id(expenseId);
		assertEquals(3, equalShares.size());
		assertEquals(new BigDecimal("10.00"),
				equalShares.stream().map(ExpenseSplit::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
		assertEquals(originalCreatedAt, jdbcTemplate.queryForObject(
				"SELECT created_at FROM expenses WHERE id = ?", Timestamp.class, expenseId));

		mockMvc.perform(put("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId)
						.header("Authorization", bearerToken(creator))
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"description":"Exact correction","amount":12.00,"paidByUserId":%d,
								 "splits":[{"userId":%d,"amount":4.00},
								           {"userId":%d,"amount":3.50},
								           {"userId":%d,"amount":4.50}]}
								""".formatted(creator.getId(), creator.getId(), member.getId(), participant.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(expenseId))
				.andExpect(jsonPath("$.createdByUserId").value(creator.getId()))
				.andExpect(jsonPath("$.amount").value("12.00"))
				.andExpect(jsonPath("$.splits.length()").value(3))
				.andExpect(jsonPath("$.splits[0].amount").value("4.00"))
				.andExpect(jsonPath("$.splits[1].amount").value("3.50"))
				.andExpect(jsonPath("$.splits[2].amount").value("4.50"));

		List<ExpenseSplit> exactShares = expenseSplitRepository.findAllByExpense_Id(expenseId);
		assertEquals(3, exactShares.size());
		assertEquals(new BigDecimal("12.00"),
				exactShares.stream().map(ExpenseSplit::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
		assertEquals(1, expenseRepository.countByGroup_Id(group.getId()));
		assertEquals(originalCreatedAt, jdbcTemplate.queryForObject(
				"SELECT created_at FROM expenses WHERE id = ?", Timestamp.class, expenseId));
	}

	@Test
	void invalidOrUnauthorizedUpdatesPreserveExpenseAndSplits() throws Exception {
		User creator = createUser("Update Auth Creator");
		User member = createUser("Update Auth Member");
		User unrelated = createUser("Update Auth Unrelated");
		ExpenseGroup group = createGroup("Update auth group", creator);
		addMember(group, member);
		String initialJson = postExpense(group.getId(), bearerToken(creator),
				expenseJson(creator, creator, "Stable expense", "5.00"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long expenseId = objectMapper.readTree(initialJson).get("id").asLong();
		String invalid = """
				{"description":"Changed","amount":7.00,"paidByUserId":%d,
				 "splitType":"EQUAL","participantUserIds":[%d,%d]}
				""".formatted(creator.getId(), creator.getId(), unrelated.getId());

		mockMvc.perform(put("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId)
						.header("Authorization", bearerToken(member))
						.contentType(MediaType.APPLICATION_JSON_VALUE).content(invalid))
				.andExpect(status().isForbidden());
		mockMvc.perform(put("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId)
						.header("Authorization", bearerToken(unrelated))
						.contentType(MediaType.APPLICATION_JSON_VALUE).content(invalid))
				.andExpect(status().isNotFound());
		mockMvc.perform(put("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId)
						.contentType(MediaType.APPLICATION_JSON_VALUE).content(invalid))
				.andExpect(status().isUnauthorized());
		for (String invalidRequest : List.of(
				"""
				{"description":"Bad total","amount":7.00,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":6.99}]}
				""".formatted(creator.getId(), creator.getId()),
				"""
				{"description":"Outside split","amount":7.00,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":7.00}]}
				""".formatted(creator.getId(), unrelated.getId()),
				"""
				{"description":"Too precise","amount":7.001,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":7.001}]}
				""".formatted(creator.getId(), creator.getId()))) {
			mockMvc.perform(put("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId)
							.header("Authorization", bearerToken(creator))
							.contentType(MediaType.APPLICATION_JSON_VALUE).content(invalidRequest))
					.andExpect(status().isBadRequest());
		}
		String outsidePayer = """
				{"description":"Outside payer","amount":7.00,"paidByUserId":%d,
				 "splitType":"EQUAL","participantUserIds":[%d]}
				""".formatted(unrelated.getId(), creator.getId());
		mockMvc.perform(put("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expenseId)
						.header("Authorization", bearerToken(creator))
						.contentType(MediaType.APPLICATION_JSON_VALUE).content(outsidePayer))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("bad_request"))
				.andExpect(jsonPath("$.message").value("The payer must be a member of this group."));

		Expense unchanged = expenseRepository.findById(expenseId).orElseThrow();
		assertEquals("Stable expense", unchanged.getDescription());
		assertEquals(new BigDecimal("5.00"), unchanged.getAmount());
		assertEquals(1, expenseSplitRepository.countByExpense_Id(expenseId));
	}

	@Test
	void creatorDeletesOnlyTargetExpenseAndItsSplits() throws Exception {
		User creator = createUser("Delete Creator");
		User member = createUser("Delete Member");
		User unrelated = createUser("Delete Unrelated");
		ExpenseGroup group = createGroup("Delete group", creator);
		addMember(group, member);
		String firstJson = postExpense(group.getId(), bearerToken(creator),
				expenseJson(creator, creator, "Delete target", "3.00"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long targetId = objectMapper.readTree(firstJson).get("id").asLong();
		String secondJson = postExpense(group.getId(), bearerToken(creator),
				expenseJson(creator, member, "Keep this", "4.00"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long keepId = objectMapper.readTree(secondJson).get("id").asLong();

		mockMvc.perform(delete("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), targetId)
						.header("Authorization", bearerToken(member)))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), targetId)
						.header("Authorization", bearerToken(unrelated)))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), targetId))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), targetId)
						.header("Authorization", bearerToken(creator)))
				.andExpect(status().isNoContent());
		assertEquals(0, expenseSplitRepository.countByExpense_Id(targetId));
		assertEquals(false, expenseRepository.existsById(targetId));
		assertEquals(true, expenseRepository.existsById(keepId));
		mockMvc.perform(delete("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), targetId)
						.header("Authorization", bearerToken(creator)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), keepId)
						.header("Authorization", bearerToken(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("Keep this"));
	}

	@Test
	void emptyGroupBalancesIncludeEveryMemberAndEnforceMembershipAuthorization() throws Exception {
		User creator = createUser("Empty Balance Creator");
		User member = createUser("Empty Balance Member");
		User inactive = createUser("Empty Balance Inactive");
		User outsider = createUser("Empty Balance Outsider");
		ExpenseGroup group = createGroup("Empty balance group", creator);
		addMember(group, member);
		addMember(group, inactive);

		String response = mockMvc.perform(get("/api/groups/{groupId}/balances", group.getId())
						.header("Authorization", bearerToken(member)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertBalances(group.getId(), response, "0.00", List.of(
				expectedBalance(creator, "0.00", "0.00", "0.00"),
				expectedBalance(member, "0.00", "0.00", "0.00"),
				expectedBalance(inactive, "0.00", "0.00", "0.00")));

		mockMvc.perform(get("/api/groups/{groupId}/balances", group.getId())
						.header("Authorization", bearerToken(outsider)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/balances", 99999999L)
						.header("Authorization", bearerToken(creator)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/balances", group.getId()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/groups/{groupId}/balances", group.getId())
						.header("Authorization", "Bearer invalid"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void balancesAggregatePaidAndSharesSeparatelyForMixedExpenses() throws Exception {
		User a = createUser("Balance A");
		User b = createUser("Balance B");
		User c = createUser("Balance C");
		User d = createUser("Balance D");
		User inactive = createUser("Balance Inactive");
		ExpenseGroup group = createGroup("Balance aggregate group", a);
		addMember(group, b);
		addMember(group, c);
		addMember(group, d);
		addMember(group, inactive);
		ExpenseGroup otherGroup = createGroup("Other balance group", a);

		postExpense(group.getId(), bearerToken(a),
				equalExpenseJson("Equal expense", "900.00", a.getId(),
						List.of(a.getId(), b.getId(), c.getId())))
				.andExpect(status().isCreated());
		assertBalances(group.getId(), getBalances(group.getId(), a), "900.00", List.of(
				expectedBalance(a, "900.00", "300.00", "600.00"),
				expectedBalance(b, "0.00", "300.00", "-300.00"),
				expectedBalance(c, "0.00", "300.00", "-300.00"),
				expectedBalance(d, "0.00", "0.00", "0.00"),
				expectedBalance(inactive, "0.00", "0.00", "0.00")));
		postExpense(group.getId(), bearerToken(a), """
				{"description":"Exact expense","amount":600.00,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":100.00},
				           {"userId":%d,"amount":200.00},
				           {"userId":%d,"amount":300.00}]}
				""".formatted(b.getId(), a.getId(), b.getId(), c.getId()))
				.andExpect(status().isCreated());
		assertBalances(group.getId(), getBalances(group.getId(), a), "1500.00", List.of(
				expectedBalance(a, "900.00", "400.00", "500.00"),
				expectedBalance(b, "600.00", "500.00", "100.00"),
				expectedBalance(c, "0.00", "600.00", "-600.00"),
				expectedBalance(d, "0.00", "0.00", "0.00"),
				expectedBalance(inactive, "0.00", "0.00", "0.00")));
		postExpense(group.getId(), bearerToken(a), """
				{"description":"Payer without share","amount":100.00,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":50.00},{"userId":%d,"amount":50.00}]}
				""".formatted(d.getId(), a.getId(), b.getId()))
				.andExpect(status().isCreated());
		postExpense(group.getId(), bearerToken(a),
				equalExpenseJson("One-paise remainder", "0.05", c.getId(),
						List.of(c.getId(), a.getId(), b.getId())))
				.andExpect(status().isCreated());
		postExpense(otherGroup.getId(), bearerToken(a),
				expenseJson(a, a, "Other group expense", "777.00"))
				.andExpect(status().isCreated());

		String response = mockMvc.perform(get("/api/groups/{groupId}/balances", group.getId())
						.header("Authorization", bearerToken(b)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertBalances(group.getId(), response, "1600.05", List.of(
				expectedBalance(a, "900.00", "450.02", "449.98"),
				expectedBalance(b, "600.00", "550.02", "49.98"),
				expectedBalance(c, "0.05", "600.01", "-599.96"),
				expectedBalance(d, "100.00", "0.00", "100.00"),
				expectedBalance(inactive, "0.00", "0.00", "0.00")));

		var responseTree = objectMapper.readTree(response);
		BigDecimal paidTotal = BigDecimal.ZERO;
		BigDecimal shareTotal = BigDecimal.ZERO;
		BigDecimal netTotal = BigDecimal.ZERO;
		for (var balance : responseTree.path("balances")) {
			paidTotal = paidTotal.add(new BigDecimal(balance.path("totalPaid").asText()));
			shareTotal = shareTotal.add(new BigDecimal(balance.path("totalShare").asText()));
			netTotal = netTotal.add(new BigDecimal(balance.path("netBalance").asText()));
		}
		assertEquals(0, new BigDecimal("1600.05").compareTo(paidTotal));
		assertEquals(0, new BigDecimal("1600.05").compareTo(shareTotal));
		assertEquals(0, BigDecimal.ZERO.compareTo(netTotal));
	}

	@Test
	void balancesRecalculateAfterExpenseUpdatesAndDeletion() throws Exception {
		User creator = createUser("Dynamic Balance Creator");
		User member = createUser("Dynamic Balance Member");
		ExpenseGroup group = createGroup("Dynamic balance group", creator);
		addMember(group, member);
		String targetJson = postExpense(group.getId(), bearerToken(creator),
				expenseJson(creator, creator, "Balance target", "10.00"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long targetExpenseId = objectMapper.readTree(targetJson).get("id").asLong();
		String retainedJson = postExpense(group.getId(), bearerToken(creator),
				expenseJson(member, member, "Balance retained", "4.00"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		long retainedExpenseId = objectMapper.readTree(retainedJson).get("id").asLong();

		assertBalance(group.getId(), creator, "14.00",
				expectedBalance(creator, "10.00", "10.00", "0.00"),
				expectedBalance(member, "4.00", "4.00", "0.00"));

		mockMvc.perform(put("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), targetExpenseId)
						.header("Authorization", bearerToken(creator))
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"description":"Updated target","amount":12.00,"paidByUserId":%d,
								 "splits":[{"userId":%d,"amount":2.00},
								           {"userId":%d,"amount":10.00}]}
								""".formatted(creator.getId(), creator.getId(), member.getId())))
				.andExpect(status().isOk());

		assertBalance(group.getId(), creator, "16.00",
				expectedBalance(creator, "12.00", "2.00", "10.00"),
				expectedBalance(member, "4.00", "14.00", "-10.00"));

		mockMvc.perform(delete("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), targetExpenseId)
						.header("Authorization", bearerToken(creator)))
				.andExpect(status().isNoContent());
		assertEquals(false, expenseRepository.existsById(targetExpenseId));
		assertEquals(true, expenseRepository.existsById(retainedExpenseId));
		assertBalance(group.getId(), creator, "4.00",
				expectedBalance(creator, "0.00", "0.00", "0.00"),
				expectedBalance(member, "4.00", "4.00", "0.00"));
	}

	@Test
	void deletedUserTokenCannotReadGroupBalances() throws Exception {
		User creator = createUser("Deleted Balance Token Creator");
		User deletedUser = createUser("Deleted Balance Token User");
		ExpenseGroup group = createGroup("Deleted token balance group", creator);
		String token = bearerToken(deletedUser);
		userRepository.delete(deletedUser);
		userRepository.flush();

		mockMvc.perform(get("/api/groups/{groupId}/balances", group.getId())
						.header("Authorization", token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void invalidAmountsSplitsDescriptionsAndGroupUsersReturnBadRequest() throws Exception {
		User creator = createUser("Invalid Expense Creator");
		User member = createUser("Invalid Expense Member");
		User unrelated = createUser("Invalid Expense Unrelated");
		ExpenseGroup group = createGroup("Invalid expenses", creator);
		addMember(group, member);
		String token = bearerToken(creator);

		for (String invalid : List.of(
				"{\"description\":\"  \",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.00}]}",
				"{\"description\":\"Missing amount\",\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.00}]}",
				"{\"description\":\"Zero\",\"amount\":0,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.00}]}",
				"{\"description\":\"Negative\",\"amount\":-1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.00}]}",
				"{\"description\":\"Precision\",\"amount\":1.001,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.001}]}",
				"{\"description\":\"Overflow\",\"amount\":100000000000000000.00,\"paidByUserId\":"
						+ creator.getId() + ",\"splits\":[{\"userId\":" + creator.getId()
						+ ",\"amount\":100000000000000000.00}]}",
				"{\"description\":\"Zero split\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":0.00}]}",
				"{\"description\":\"Negative split\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":-1.00}]}",
				"{\"description\":\"Mismatch\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":0.99}]}",
				"{\"description\":\"Duplicate\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":0.50},"
						+ "{\"userId\":" + creator.getId() + ",\"amount\":0.50}]}",
				"{\"description\":\"No splits\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[]}",
				"{\"description\":\"Outside payer\",\"amount\":1.00,\"paidByUserId\":" + unrelated.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.00}]}",
				"{\"description\":\"Outside participant\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + unrelated.getId() + ",\"amount\":1.00}]}",
				"{\"description\":\"Wrong currency\",\"amount\":1.00,\"currencyCode\":\"USD\","
						+ "\"paidByUserId\":" + creator.getId()
						+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.00}]}"
		)) {
			postExpense(group.getId(), token, invalid).andExpect(status().isBadRequest());
		}
		postExpense(group.getId(), token, "{\"description\":\"Missing splits\",\"amount\":1.00,"
				+ "\"paidByUserId\":" + creator.getId() + "}").andExpect(status().isBadRequest());
		postExpense(group.getId(), token, "{").andExpect(status().isBadRequest());
		postExpense(group.getId(), token, "{\"description\":\"" + "x".repeat(256)
				+ "\",\"amount\":1.00,\"paidByUserId\":" + creator.getId()
				+ ",\"splits\":[{\"userId\":" + creator.getId() + ",\"amount\":1.00}]}")
				.andExpect(status().isBadRequest());

		assertEquals(0, expenseRepository.countByGroup_Id(group.getId()));
		assertEquals(0, expenseSplitRepository.countByExpense_Group_Id(group.getId()));
	}

	@Test
	void listingIsMembershipScopedNewestFirstAndEmptyWhenNoExpenses() throws Exception {
		User creator = createUser("Listing Creator");
		User member = createUser("Listing Member");
		User unrelated = createUser("Listing Unrelated");
		ExpenseGroup group = createGroup("Expense listing", creator);
		addMember(group, member);
		ExpenseGroup emptyGroup = createGroup("Empty expense list", creator);

		postExpense(group.getId(), bearerToken(creator), expenseJson(creator, creator, "First", "1.00"))
				.andExpect(status().isCreated());
		postExpense(group.getId(), bearerToken(member), expenseJson(creator, member, "Second", "2.00"))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/groups/{groupId}/expenses", group.getId())
						.header("Authorization", bearerToken(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].description").value("Second"))
				.andExpect(jsonPath("$[0].paidByUserId").value(creator.getId()))
				.andExpect(jsonPath("$[0].createdByUserId").value(member.getId()))
				.andExpect(jsonPath("$[1].description").value("First"));

		mockMvc.perform(get("/api/groups/{groupId}/expenses", emptyGroup.getId())
						.header("Authorization", bearerToken(creator)))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
		mockMvc.perform(get("/api/groups/{groupId}/expenses", group.getId())
						.header("Authorization", bearerToken(unrelated)))
				.andExpect(status().isNotFound());
		postExpense(group.getId(), bearerToken(unrelated),
				expenseJson(creator, creator, "Private create", "1.00"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/expenses", 99999999L)
						.header("Authorization", bearerToken(creator)))
				.andExpect(status().isNotFound());
	}

	@Test
	void createAndListRequireAuthentication() throws Exception {
		User creator = createUser("Expense Authentication");
		ExpenseGroup group = createGroup("Authenticated expenses", creator);
		String body = expenseJson(creator, creator, "No token", "1.00");

		mockMvc.perform(post("/api/groups/{groupId}/expenses", group.getId())
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(body))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/groups/{groupId}/expenses", group.getId())
						.header("Authorization", "Bearer invalid")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(body))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/groups/{groupId}/expenses", group.getId())
						.header("Authorization", "Bearer invalid"))
				.andExpect(status().isUnauthorized());
	}

	private String expenseJson(User payer, User participant, String description, String amount) {
		return """
				{"description":"%s","amount":%s,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":%s}]}
				""".formatted(description, amount, payer.getId(), participant.getId(), amount);
	}

	private String equalExpenseJson(String description, String amount, Long payerId, List<Long> participantIds) {
		return """
				{"description":"%s","amount":%s,"paidByUserId":%d,
				 "splitType":"EQUAL","participantUserIds":%s}
				""".formatted(description, amount, payerId, participantIds);
	}

	private ResultActions postExpense(Long groupId, String token, String body) throws Exception {
		return mockMvc.perform(post("/api/groups/{groupId}/expenses", groupId)
				.header("Idempotency-Key", "\"" + java.util.UUID.randomUUID() + "\"")
				.header("Authorization", token)
				.contentType(MediaType.APPLICATION_JSON_VALUE)
				.content(body));
	}

	private void assertBalance(
			Long groupId,
			User requester,
			String totalExpenseAmount,
			ExpectedBalance... balances) throws Exception {
		String response = getBalances(groupId, requester);
		assertBalances(groupId, response, totalExpenseAmount, List.of(balances));
	}

	private String getBalances(Long groupId, User requester) throws Exception {
		return mockMvc.perform(get("/api/groups/{groupId}/balances", groupId)
						.header("Authorization", bearerToken(requester)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private void assertBalances(
			Long groupId,
			String response,
			String totalExpenseAmount,
			List<ExpectedBalance> expectedBalances) throws Exception {
		var responseTree = objectMapper.readTree(response);
		assertEquals(groupId.longValue(), responseTree.path("groupId").longValue());
		assertEquals("INR", responseTree.path("currencyCode").textValue());
		assertDecimal(totalExpenseAmount, new BigDecimal(responseTree.path("totalExpenseAmount").asText()));
		var actualBalances = responseTree.path("balances");
		assertEquals(expectedBalances.size(), actualBalances.size());
		for (int index = 0; index < expectedBalances.size(); index++) {
			ExpectedBalance expected = expectedBalances.get(index);
			var actual = actualBalances.get(index);
			assertEquals(expected.userId(), actual.path("userId").longValue());
			assertEquals(expected.name(), actual.path("name").textValue());
			assertEquals(expected.email(), actual.path("email").textValue());
			assertDecimal(expected.totalPaid(), new BigDecimal(actual.path("totalPaid").asText()));
			assertDecimal(expected.totalShare(), new BigDecimal(actual.path("totalShare").asText()));
			assertDecimal(expected.totalSent(), new BigDecimal(actual.path("totalSentSettlements").asText()));
			assertDecimal(expected.totalReceived(), new BigDecimal(actual.path("totalReceivedSettlements").asText()));
			assertDecimal(expected.netBalance(), new BigDecimal(actual.path("netBalance").asText()));
		}
	}

	private static void assertDecimal(String expected, BigDecimal actual) {
		assertEquals(0, new BigDecimal(expected).compareTo(actual));
	}

	private static ExpectedBalance expectedBalance(
			User user,
			String totalPaid,
			String totalShare,
			String netBalance) {
		return expectedBalance(user, totalPaid, totalShare, "0.00", "0.00", netBalance);
	}

	private static ExpectedBalance expectedBalance(
			User user,
			String totalPaid,
			String totalShare,
			String totalSent,
			String totalReceived,
			String netBalance) {
		return new ExpectedBalance(
				user.getId(),
				user.getName(),
				user.getEmail(),
				totalPaid,
				totalShare,
				totalSent,
				totalReceived,
				netBalance);
	}

	private record ExpectedBalance(
			Long userId,
			String name,
			String email,
			String totalPaid,
			String totalShare,
			String totalSent,
			String totalReceived,
			String netBalance) {
	}

	private @NonNull User createUser(String name) {
		return userRepository.saveAndFlush(new User(
				name,
				name.toLowerCase().replace(' ', '.') + "@expenses.example",
				"{bcrypt}test"));
	}

	private ExpenseGroup createGroup(String name, User creator) {
		ExpenseGroup group = expenseGroupRepository.saveAndFlush(new ExpenseGroup(name, creator));
		addMember(group, creator);
		return group;
	}

	private void addMember(ExpenseGroup group, User member) {
		groupMemberRepository.saveAndFlush(new GroupMember(group, member));
	}

	private String bearerToken(User user) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("https://splitsmart.local")
				.subject(user.getId().toString())
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600))
				.build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
		return "Bearer " + token;
	}
}
