package com.splitsmart.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.GroupMember;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.SettlementRepository;
import com.splitsmart.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SettlementControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ExpenseGroupRepository expenseGroupRepository;

	@Autowired
	private GroupMemberRepository groupMemberRepository;

	@Autowired
	private SettlementRepository settlementRepository;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Test
	void partialAndFullSettlementsUpdateBalancesAndDuplicateRequestIsRejected() throws Exception {
		User creditor = createUser("Settlement Creditor");
		User debtor = createUser("Settlement Debtor");
		User secondDebtor = createUser("Settlement Second Debtor");
		ExpenseGroup group = createGroup("Settlement balances", creditor, debtor, secondDebtor);
		createExpense(group.getId(), creditor, "Dinner", "900.00",
				"\"splitType\":\"EQUAL\",\"participantUserIds\":[%d,%d,%d]"
						.formatted(creditor.getId(), debtor.getId(), secondDebtor.getId()));

		String partialRequestId = UUID.randomUUID().toString();
		postSettlement(group.getId(), debtor, """
				{"fromUserId":%d,"toUserId":%d,"amount":200.00,"requestId":"%s","note":"UPI repayment"}
				""".formatted(debtor.getId(), creditor.getId(), partialRequestId))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.groupId").value(group.getId()))
				.andExpect(jsonPath("$.fromUserId").value(debtor.getId()))
				.andExpect(jsonPath("$.toUserId").value(creditor.getId()))
				.andExpect(jsonPath("$.amount").value("200.00"))
				.andExpect(jsonPath("$.recordedByUserId").value(debtor.getId()))
				.andExpect(jsonPath("$.requestId").value(partialRequestId))
				.andExpect(jsonPath("$.note").value("UPI repayment"))
				.andExpect(jsonPath("$.createdAt").isNotEmpty());

		assertEquals(1, settlementRepository.count());
		mockMvc.perform(get("/api/groups/{groupId}/balances", group.getId())
						.header("Authorization", bearerToken(creditor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalExpenseAmount").value("900.00"))
				.andExpect(jsonPath("$.balances[0].netBalance").value("400.00"))
				.andExpect(jsonPath("$.balances[0].totalReceivedSettlements").value("200.00"))
				.andExpect(jsonPath("$.balances[1].netBalance").value("-100.00"))
				.andExpect(jsonPath("$.balances[1].totalSentSettlements").value("200.00"))
				.andExpect(jsonPath("$.balances[2].netBalance").value("-300.00"));

		postSettlement(group.getId(), debtor, """
				{"fromUserId":%d,"toUserId":%d,"amount":200.00,"requestId":"%s"}
				""".formatted(debtor.getId(), creditor.getId(), partialRequestId))
				.andExpect(status().isConflict());
		assertEquals(1, settlementRepository.count());

		postSettlement(group.getId(), debtor, """
				{"fromUserId":%d,"toUserId":%d,"amount":101.00,"requestId":"%s"}
				""".formatted(debtor.getId(), creditor.getId(), UUID.randomUUID()))
				.andExpect(status().isBadRequest());
		assertEquals(1, settlementRepository.count());

		postSettlement(group.getId(), debtor, """
				{"fromUserId":%d,"toUserId":%d,"amount":100.00,"requestId":"%s"}
				""".formatted(debtor.getId(), creditor.getId(), UUID.randomUUID()))
				.andExpect(status().isCreated());
		postSettlement(group.getId(), secondDebtor, """
				{"fromUserId":%d,"toUserId":%d,"amount":300.00,"requestId":"%s"}
				""".formatted(secondDebtor.getId(), creditor.getId(), UUID.randomUUID()))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/groups/{groupId}/balances", group.getId())
						.header("Authorization", bearerToken(debtor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalExpenseAmount").value("900.00"))
				.andExpect(jsonPath("$.balances[0].netBalance").value("0.00"))
				.andExpect(jsonPath("$.balances[1].netBalance").value("0.00"))
				.andExpect(jsonPath("$.balances[2].netBalance").value("0.00"));

		mockMvc.perform(get("/api/groups/{groupId}/settlements", group.getId())
						.header("Authorization", bearerToken(creditor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].amount").value("300.00"))
				.andExpect(jsonPath("$[0].recordedByUserId").value(secondDebtor.getId()))
				.andExpect(jsonPath("$[2].requestId").value(partialRequestId));
	}

	@Test
	void senderSpoofingAndPrivateGroupAccessAreRejected() throws Exception {
		User creditor = createUser("Settlement Auth Creditor");
		User debtor = createUser("Settlement Auth Debtor");
		User outsider = createUser("Settlement Auth Outsider");
		ExpenseGroup group = createGroup("Settlement auth", creditor, debtor);
		ExpenseGroup otherGroup = createGroup("Other settlement auth", creditor);
		createExpense(group.getId(), creditor, "Auth expense", "90.00",
				"\"splitType\":\"EQUAL\",\"participantUserIds\":[%d,%d]"
						.formatted(creditor.getId(), debtor.getId()));

		postSettlement(group.getId(), debtor, request(creditor, debtor, "10.00"))
				.andExpect(status().isForbidden());
		assertEquals(0, settlementRepository.count());
		postSettlement(group.getId(), outsider, request(outsider, creditor, "10.00"))
				.andExpect(status().isNotFound());
		postSettlement(otherGroup.getId(), creditor, request(creditor, debtor, "10.00"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/groups/{groupId}/settlements", group.getId())
						.header("Authorization", bearerToken(outsider)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/settlements", 99999999L)
						.header("Authorization", bearerToken(creditor)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/settlements", group.getId()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/groups/{groupId}/settlements", group.getId())
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(request(debtor, creditor, "10.00")))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/groups/{groupId}/settlements", group.getId())
						.header("Authorization", "Bearer invalid"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void invalidSettlementRequestsDoNotPersistRows() throws Exception {
		User creditor = createUser("Settlement Validation Creditor");
		User debtor = createUser("Settlement Validation Debtor");
		User outsider = createUser("Settlement Validation Outsider");
		ExpenseGroup group = createGroup("Settlement validation", creditor, debtor);
		createExpense(group.getId(), creditor, "Validation expense", "10.00",
				"\"splits\":[{\"userId\":%d,\"amount\":5.00},{\"userId\":%d,\"amount\":5.00}]"
						.formatted(creditor.getId(), debtor.getId()));

		List<String> invalidRequests = List.of(
				"{\"fromUserId\":" + debtor.getId() + ",\"toUserId\":" + creditor.getId()
						+ ",\"amount\":0,\"requestId\":\"" + UUID.randomUUID() + "\"}",
				"{\"fromUserId\":" + debtor.getId() + ",\"toUserId\":" + creditor.getId()
						+ ",\"amount\":-1.00,\"requestId\":\"" + UUID.randomUUID() + "\"}",
				"{\"fromUserId\":" + debtor.getId() + ",\"toUserId\":" + creditor.getId()
						+ ",\"amount\":1.001,\"requestId\":\"" + UUID.randomUUID() + "\"}",
				"{\"fromUserId\":" + debtor.getId() + ",\"toUserId\":" + creditor.getId()
						+ ",\"amount\":100000000000000000.00,\"requestId\":\"" + UUID.randomUUID() + "\"}",
				"{\"fromUserId\":" + debtor.getId() + ",\"toUserId\":" + outsider.getId()
						+ ",\"amount\":1.00,\"requestId\":\"" + UUID.randomUUID() + "\"}",
				"{\"fromUserId\":" + debtor.getId() + ",\"toUserId\":" + creditor.getId()
						+ ",\"amount\":1.00,\"requestId\":\"not-a-uuid\"}",
				"{\"fromUserId\":" + debtor.getId() + ",\"toUserId\":" + creditor.getId()
						+ ",\"amount\":1.00}",
				"{\"fromUserId\":" + debtor.getId() + ",\"toUserId\":" + creditor.getId()
						+ ",\"amount\":1.00,\"requestId\":\"" + UUID.randomUUID()
						+ "\",\"note\":\"" + "x".repeat(201) + "\"}");
		for (String invalid : invalidRequests) {
			postSettlement(group.getId(), debtor, invalid).andExpect(status().isBadRequest());
		}
		postSettlement(group.getId(), debtor, """
				{"fromUserId":%d,"toUserId":%d,"amount":1.00,"requestId":"%s"}
				""".formatted(debtor.getId(), debtor.getId(), UUID.randomUUID()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("bad_request"))
				.andExpect(jsonPath("$.message").value("Settlement sender and recipient must differ."));
		postSettlement(group.getId(), debtor, "{").andExpect(status().isBadRequest());
		assertEquals(0, settlementRepository.count());
	}

	@Test
	void settlementListIsEmptyForMemberAndDeletedUserTokenIsUnauthorized() throws Exception {
		User member = createUser("Settlement Empty Member");
		User deleted = createUser("Settlement Deleted User");
		ExpenseGroup group = createGroup("Settlement empty", member);
		mockMvc.perform(get("/api/groups/{groupId}/settlements", group.getId())
						.header("Authorization", bearerToken(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		String deletedToken = bearerToken(deleted);
		userRepository.delete(deleted);
		userRepository.flush();
		mockMvc.perform(get("/api/groups/{groupId}/settlements", group.getId())
						.header("Authorization", deletedToken))
				.andExpect(status().isUnauthorized());
	}

	private void createExpense(Long groupId, User payer, String description, String amount, String splitFields)
			throws Exception {
		String request = """
				{"description":"%s","amount":%s,"paidByUserId":%d,%s}
				""".formatted(description, amount, payer.getId(), splitFields);
		mockMvc.perform(post("/api/groups/{groupId}/expenses", groupId).header("Idempotency-Key", "\"" + java.util.UUID.randomUUID() + "\"")
						.header("Authorization", bearerToken(payer))
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(request))
				.andExpect(status().isCreated());
	}

	private ResultActions postSettlement(Long groupId, User requester, String body) throws Exception {
		return mockMvc.perform(post("/api/groups/{groupId}/settlements", groupId)
				.header("Authorization", bearerToken(requester))
				.contentType(MediaType.APPLICATION_JSON_VALUE)
				.content(body));
	}

	private String request(User from, User to, String amount) {
		return """
				{"fromUserId":%d,"toUserId":%d,"amount":%s,"requestId":"%s"}
				""".formatted(from.getId(), to.getId(), amount, UUID.randomUUID());
	}

	private @NonNull User createUser(String name) {
		String suffix = UUID.randomUUID().toString();
		return userRepository.saveAndFlush(new User(
				name,
				suffix + "@settlements.example",
				"{bcrypt}test"));
	}

	private ExpenseGroup createGroup(String name, User... members) {
		ExpenseGroup group = expenseGroupRepository.saveAndFlush(new ExpenseGroup(name, members[0]));
		for (User member : members) {
			groupMemberRepository.saveAndFlush(new GroupMember(group, member));
		}
		return group;
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
