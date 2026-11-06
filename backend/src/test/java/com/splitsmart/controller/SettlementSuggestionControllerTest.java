package com.splitsmart.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.transaction.annotation.Transactional;

import com.splitsmart.dto.SettlementSuggestionResponse;
import com.splitsmart.entity.Expense;
import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.GroupMember;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.ExpenseRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.SettlementRepository;
import com.splitsmart.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SettlementSuggestionControllerTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ExpenseGroupRepository expenseGroupRepository;

	@Autowired
	private GroupMemberRepository groupMemberRepository;

	@Autowired
	private ExpenseRepository expenseRepository;

	@Autowired
	private SettlementRepository settlementRepository;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Test
	void equalBalancesAreSettledByDescendingAmountThenUserIdWithoutWritingRecords() throws Exception {
		User creditorA = createUser("Suggestion Creditor A");
		User creditorB = createUser("Suggestion Creditor B");
		User debtorA = createUser("Suggestion Debtor A");
		User debtorB = createUser("Suggestion Debtor B");
		ExpenseGroup group = createGroup("Suggestion tie group", creditorA, creditorB, debtorA, debtorB);
		createExpense(group, creditorA, debtorA, "10.00");
		createExpense(group, creditorB, debtorB, "10.00");
		long expensesBefore = expenseRepository.count();
		long settlementsBefore = settlementRepository.count();

		mockMvc.perform(get(suggestionsPath(group))
						.header("Authorization", bearerToken(debtorB)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.groupId").value(group.getId()))
				.andExpect(jsonPath("$.currencyCode").value("INR"))
				.andExpect(jsonPath("$.totalSuggestedAmount").value("20.00"))
				.andExpect(jsonPath("$.suggestions.length()").value(2))
				.andExpect(jsonPath("$.suggestions[0].fromUserId").value(debtorA.getId()))
				.andExpect(jsonPath("$.suggestions[0].toUserId").value(creditorA.getId()))
				.andExpect(jsonPath("$.suggestions[0].amount").value("10.00"))
				.andExpect(jsonPath("$.suggestions[1].fromUserId").value(debtorB.getId()))
				.andExpect(jsonPath("$.suggestions[1].toUserId").value(creditorB.getId()))
				.andExpect(jsonPath("$.suggestions[1].amount").value("10.00"));

		assertEquals(expensesBefore, expenseRepository.count());
		assertEquals(settlementsBefore, settlementRepository.count());
	}

	@Test
	void fourPersonFiftyRupeeCycleNeedsNoTransfersAndPreservesEveryObligation() throws Exception {
		User a = createUser("Cycle A"), b = createUser("Cycle B"), c = createUser("Cycle C"), d = createUser("Cycle D");
		ExpenseGroup group = createGroup("Fifty rupee circle", a, b, c, d);
		createExpense(group, b, a, "50.00"); // A owes B
		createExpense(group, c, b, "50.00");
		createExpense(group, d, c, "50.00");
		createExpense(group, a, d, "50.00");
		var originalIds = expenseRepository.findAll().stream().map(Expense::getId).sorted().toList();
		long settlementsBefore = settlementRepository.count();
		for (User member : new User[]{a,b,c,d}) {
			mockMvc.perform(get(suggestionsPath(group)).header("Authorization", bearerToken(member)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.totalSuggestedAmount").value("0.00"))
					.andExpect(jsonPath("$.suggestions.length()").value(0));
		}
		mockMvc.perform(get("/api/groups/{id}/balances", group.getId()).header("Authorization", bearerToken(a)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balances.length()").value(4))
				.andExpect(jsonPath("$.balances[*].netBalance").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("0.00"))));
		assertEquals(originalIds, expenseRepository.findAll().stream().map(Expense::getId).sorted().toList());
		assertEquals(settlementsBefore, settlementRepository.count());
		var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
		assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM expenses WHERE group_id=? AND amount=50.00", Integer.class, group.getId()));
		assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM expense_splits s JOIN expenses e ON e.id=s.expense_id WHERE e.group_id=? AND s.amount=50.00", Integer.class, group.getId()));
	}

	@Autowired private javax.sql.DataSource dataSource;

	@Test
	void suggestionsUseExactPaiseAndReflectPartialSettlement() throws Exception {
		User creditor = createUser("Suggestion Paise Creditor");
		User debtorA = createUser("Suggestion Paise Debtor A");
		User debtorB = createUser("Suggestion Paise Debtor B");
		ExpenseGroup group = createGroup("Suggestion paise group", creditor, debtorA, debtorB);
		postExpense(group, creditor, """
				{"description":"Paise expense","amount":10.01,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":3.33},
				           {"userId":%d,"amount":3.34},
				           {"userId":%d,"amount":3.34}]}
				""".formatted(creditor.getId(), creditor.getId(), debtorA.getId(), debtorB.getId()))
				.andExpect(status().isCreated());

		mockMvc.perform(get(suggestionsPath(group))
						.header("Authorization", bearerToken(creditor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalSuggestedAmount").value("6.68"))
				.andExpect(jsonPath("$.suggestions[0].fromUserId").value(debtorA.getId()))
				.andExpect(jsonPath("$.suggestions[0].amount").value("3.34"));

		mockMvc.perform(post("/api/groups/{groupId}/settlements", group.getId())
						.header("Authorization", bearerToken(debtorA))
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"fromUserId":%d,"toUserId":%d,"amount":1.01,"requestId":"%s"}
								""".formatted(debtorA.getId(), creditor.getId(), UUID.randomUUID())))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/groups/{groupId}/settlements", group.getId())
						.header("Authorization", bearerToken(debtorA))
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"fromUserId":%d,"toUserId":%d,"amount":3.34,"requestId":"%s"}
								""".formatted(debtorA.getId(), creditor.getId(), UUID.randomUUID())))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get(suggestionsPath(group))
						.header("Authorization", bearerToken(creditor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalSuggestedAmount").value("5.67"))
				.andExpect(jsonPath("$.suggestions.length()").value(2))
				.andExpect(jsonPath("$.suggestions[0].fromUserId").value(debtorB.getId()))
				.andExpect(jsonPath("$.suggestions[0].amount").value("3.34"))
				.andExpect(jsonPath("$.suggestions[1].fromUserId").value(debtorA.getId()))
				.andExpect(jsonPath("$.suggestions[1].amount").value("2.33"));
	}

	@Test
	void largestSupportedExpenseAmountIsSuggestedWithoutRounding() throws Exception {
		User creditor = createUser("Suggestion Large Creditor");
		User debtor = createUser("Suggestion Large Debtor");
		ExpenseGroup group = createGroup("Suggestion large amount group", creditor, debtor);
		String amount = "99999999999999999.99";
		postExpense(group, creditor, """
				{"description":"Large amount","amount":%s,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":%s}]}
				""".formatted(amount, creditor.getId(), debtor.getId(), amount))
				.andExpect(status().isCreated());

		String response = mockMvc.perform(get(suggestionsPath(group))
						.header("Authorization", bearerToken(debtor)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		SettlementSuggestionResponse result = objectMapper.readValue(response, SettlementSuggestionResponse.class);
		BigDecimal expected = new BigDecimal(amount);
		assertEquals(
				0,
				expected.compareTo(result.totalSuggestedAmount()),
				"Suggestion total was " + result.totalSuggestedAmount());
		assertEquals(1, result.suggestions().size());
		assertEquals(debtor.getId(), result.suggestions().get(0).fromUserId());
		assertEquals(creditor.getId(), result.suggestions().get(0).toUserId());
		assertEquals(0, expected.compareTo(result.suggestions().get(0).amount()));
	}

	@Test
	void suggestionsRecalculateAfterExpenseUpdateAndDeletion() throws Exception {
		User creator = createUser("Suggestion Update Creator");
		User member = createUser("Suggestion Update Member");
		ExpenseGroup group = createGroup("Suggestion update group", creator, member);
		String token = bearerToken(creator);
		postExpense(group, creator, expenseJson(creator, member, "10.00"))
				.andExpect(status().isCreated());
		Expense expense = expenseRepository.findAll().stream()
				.filter(candidate -> candidate.getGroup().getId().equals(group.getId()))
				.findFirst()
				.orElseThrow();

		mockMvc.perform(get(suggestionsPath(group)).header("Authorization", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suggestions[0].amount").value("10.00"));

		mockMvc.perform(put("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expense.getId())
						.header("Authorization", token)
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(expenseJson(creator, member, "20.00")))
				.andExpect(status().isOk());
		mockMvc.perform(get(suggestionsPath(group)).header("Authorization", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalSuggestedAmount").value("20.00"))
				.andExpect(jsonPath("$.suggestions[0].amount").value("20.00"));

		mockMvc.perform(delete("/api/groups/{groupId}/expenses/{expenseId}", group.getId(), expense.getId())
						.header("Authorization", token))
				.andExpect(status().isNoContent());
		mockMvc.perform(get(suggestionsPath(group)).header("Authorization", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalSuggestedAmount").value("0.00"))
				.andExpect(jsonPath("$.suggestions.length()").value(0));
		assertEquals(0, expenseRepository.count());
	}

	@Test
	void largestFirstGreedyResultIsDeterministicButNotNecessarilyGloballyMinimal() throws Exception {
		User creditorA = createUser("Greedy Creditor A");
		User creditorB = createUser("Greedy Creditor B");
		User creditorC = createUser("Greedy Creditor C");
		User debtorA = createUser("Greedy Debtor A");
		User debtorB = createUser("Greedy Debtor B");
		User debtorC = createUser("Greedy Debtor C");
		ExpenseGroup group = createGroup(
				"Greedy limitation group", creditorA, creditorB, creditorC, debtorA, debtorB, debtorC);
		postExpense(group, creditorA, """
				{"description":"Greedy expense A","amount":8.00,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":7.00},{"userId":%d,"amount":1.00}]}
				""".formatted(creditorA.getId(), debtorA.getId(), debtorC.getId()))
				.andExpect(status().isCreated());
		postExpense(group, creditorB, """
				{"description":"Greedy expense B","amount":7.00,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":6.00},{"userId":%d,"amount":1.00}]}
				""".formatted(creditorB.getId(), debtorB.getId(), debtorC.getId()))
				.andExpect(status().isCreated());
		postExpense(group, creditorC, """
				{"description":"Greedy expense C","amount":3.00,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":3.00}]}
				""".formatted(creditorC.getId(), debtorC.getId()))
				.andExpect(status().isCreated());

		mockMvc.perform(get(suggestionsPath(group)).header("Authorization", bearerToken(debtorA)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalSuggestedAmount").value("18.00"))
				.andExpect(jsonPath("$.suggestions.length()").value(5))
				.andExpect(jsonPath("$.suggestions[0].fromUserId").value(debtorA.getId()))
				.andExpect(jsonPath("$.suggestions[0].toUserId").value(creditorA.getId()))
				.andExpect(jsonPath("$.suggestions[0].amount").value("7.00"));
	}

	@Test
	void suggestionsRequireAuthenticationAndHideGroupsFromNonmembers() throws Exception {
		User member = createUser("Suggestion Auth Member");
		User outsider = createUser("Suggestion Auth Outsider");
		User deleted = createUser("Suggestion Deleted User");
		ExpenseGroup group = createGroup("Suggestion private group", member);
		String deletedToken = bearerToken(deleted);
		userRepository.delete(deleted);
		userRepository.flush();

		mockMvc.perform(get(suggestionsPath(group)).header("Authorization", bearerToken(outsider)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/settlement-suggestions", 99999999L)
						.header("Authorization", bearerToken(member)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get(suggestionsPath(group))).andExpect(status().isUnauthorized());
		mockMvc.perform(get(suggestionsPath(group)).header("Authorization", "******"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get(suggestionsPath(group)).header("Authorization", deletedToken))
				.andExpect(status().isUnauthorized());
	}

	private void createExpense(ExpenseGroup group, User payer, User participant, String amount) throws Exception {
		postExpense(group, payer, expenseJson(payer, participant, amount)).andExpect(status().isCreated());
	}

	private String expenseJson(User payer, User participant, String amount) {
		return """
				{"description":"Suggestion expense","amount":%s,"paidByUserId":%d,
				 "splits":[{"userId":%d,"amount":%s}]}
				""".formatted(amount, payer.getId(), participant.getId(), amount);
	}

	private org.springframework.test.web.servlet.ResultActions postExpense(
			ExpenseGroup group, User creator, String body) throws Exception {
		return mockMvc.perform(post("/api/groups/{groupId}/expenses", group.getId()).header("Idempotency-Key", "\"" + java.util.UUID.randomUUID() + "\"")
				.header("Authorization", bearerToken(creator))
				.contentType(MediaType.APPLICATION_JSON_VALUE)
				.content(body));
	}

	private @NonNull String suggestionsPath(ExpenseGroup group) {
		return "/api/groups/" + group.getId() + "/settlement-suggestions";
	}

	private ExpenseGroup createGroup(String name, User... members) {
		ExpenseGroup group = expenseGroupRepository.saveAndFlush(new ExpenseGroup(name, members[0]));
		for (User member : members) {
			groupMemberRepository.saveAndFlush(new GroupMember(group, member));
		}
		return group;
	}

	private @NonNull User createUser(String name) {
		String suffix = UUID.randomUUID().toString();
		return userRepository.saveAndFlush(new User(name, suffix + "@suggestions.example", "{bcrypt}test"));
	}

	private String bearerToken(User user) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("https://splitsmart.local")
				.subject(user.getId().toString())
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600))
				.build();
		return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}
}
