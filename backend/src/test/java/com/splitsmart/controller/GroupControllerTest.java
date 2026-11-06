package com.splitsmart.controller;

import java.time.Instant;

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

import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.GroupMember;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GroupControllerTest {

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
	private JwtEncoder jwtEncoder;

	@Test
	void authenticatedUserCreatesGroupAndBecomesItsOnlyInitialMember() throws Exception {
		User creator = createUser("Creator");

		String response = mockMvc.perform(post("/api/groups")
						.header("Authorization", bearerToken(creator))
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"  Goa Trip  ","createdByUserId":999999}
								"""))
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON_VALUE))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Goa Trip"))
				.andExpect(jsonPath("$.createdByUserId").value(creator.getId()))
				.andReturn().getResponse().getContentAsString();

		long groupId = objectMapper.readTree(response).get("id").asLong();
		ExpenseGroup group = expenseGroupRepository.findById(groupId).orElseThrow();
		assertEquals("Goa Trip", group.getName());
		assertEquals(creator.getId(), group.getCreatedBy().getId());
		assertEquals(1, groupMemberRepository.countByGroup_IdAndUser_Id(groupId, creator.getId()));
		assertEquals(1, groupMemberRepository.count());
	}

	@Test
	void invalidGroupNamesReturnBadRequest() throws Exception {
		User user = createUser("Name Validation");
		String token = bearerToken(user);

		mockMvc.perform(post("/api/groups").header("Authorization", token)
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{\"name\":\"   \"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/groups").header("Authorization", token)
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/groups").header("Authorization", token)
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{\"name\":\"" + "x".repeat(121) + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void groupEndpointsRequireAuthentication() throws Exception {
		mockMvc.perform(post("/api/groups")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{\"name\":\"Private\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/groups"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void whitespacePathVariableReturnsBadRequest() throws Exception {
		User user = createUser("Invalid Path");

		mockMvc.perform(get("/api/groups/{groupId}/members", " ")
						.header("Authorization", bearerToken(user)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_parameter"));
	}

	@Test
	void listReturnsOnlyGroupsTheAuthenticatedUserBelongsToInDescendingIdOrder() throws Exception {
		User userA = createUser("User A");
		User userB = createUser("User B");
		ExpenseGroup olderGroup = createGroup("Older group", userA);
		addMember(olderGroup, userA);
		ExpenseGroup privateGroup = createGroup("User B private", userB);
		addMember(privateGroup, userB);
		ExpenseGroup joinedGroup = createGroup("Joined group", userB);
		addMember(joinedGroup, userA);

		mockMvc.perform(get("/api/groups").header("Authorization", bearerToken(userA)))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON_VALUE))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(joinedGroup.getId()))
				.andExpect(jsonPath("$[0].name").value("Joined group"))
				.andExpect(jsonPath("$[0].createdByUserId").value(userB.getId()))
				.andExpect(jsonPath("$[1].id").value(olderGroup.getId()))
				.andExpect(jsonPath("$[1].name").value("Older group"));
	}

	@Test
	void userWithoutMembershipsReceivesEmptyArray() throws Exception {
		User user = createUser("No Groups");

		mockMvc.perform(get("/api/groups").header("Authorization", bearerToken(user)))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

	@Test
	void deletedUserCannotCreateOrListGroupsWithPreviouslyIssuedToken() throws Exception {
		User deletedUser = createUser("Deleted User");
		String token = bearerToken(deletedUser);
		userRepository.delete(deletedUser);
		userRepository.flush();

		mockMvc.perform(post("/api/groups").header("Authorization", token)
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{\"name\":\"Must not persist\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/groups").header("Authorization", token))
				.andExpect(status().isUnauthorized());
		assertEquals(0, expenseGroupRepository.count());
		assertEquals(0, groupMemberRepository.count());
	}

	@Test
	void creatorCannotEnrollWithoutRecipientAcceptance() throws Exception {
		User creator = createUser("Member Creator");
		User friend = createUser("Member Friend");
		ExpenseGroup group = createGroup("Members", creator);
		addMember(group, creator);
		postMember(group.getId(), bearerToken(creator), friend.getEmail()).andExpect(status().isConflict());
		assertEquals(0, groupMemberRepository.countByGroup_IdAndUser_Id(group.getId(), friend.getId()));
	}
	@Test
	void memberListIncludesAllMembersInMembershipOrderWithoutSensitiveFields() throws Exception {
		User creator = createUser("List Creator");
		User firstMember = createUser("First Member");
		User secondMember = createUser("Second Member");
		ExpenseGroup group = createGroup("Ordered members", creator);
		addMember(group, creator);
		addMember(group, firstMember);
		addMember(group, secondMember);

		String response = mockMvc.perform(get("/api/groups/{groupId}/members", group.getId())
						.header("Authorization", bearerToken(firstMember)))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON_VALUE))
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].userId").value(creator.getId()))
				.andExpect(jsonPath("$[1].userId").value(firstMember.getId()))
				.andExpect(jsonPath("$[2].userId").value(secondMember.getId()))
				.andReturn().getResponse().getContentAsString();

		org.junit.jupiter.api.Assertions.assertFalse(response.contains("password"));
	}

	@Test
	void duplicateAdditionsIncludingCreatorReturnConflictAndPersistOnce() throws Exception {
		User creator = createUser("Duplicate Creator");
		User member = createUser("Duplicate Member");
		ExpenseGroup group = createGroup("Duplicate membership", creator);
		addMember(group, creator);
		addMember(group, member);
		String token = bearerToken(creator);

		postMember(group.getId(), token, member.getEmail())
				.andExpect(status().isConflict());
		postMember(group.getId(), token, creator.getEmail())
				.andExpect(status().isConflict());
		postMember(group.getId(), token, member.getEmail())
				.andExpect(status().isConflict());

		assertEquals(1, groupMemberRepository.countByGroup_IdAndUser_Id(group.getId(), member.getId()));
	}

	@Test
	void unknownTargetAndInvalidEmailsReturnExpectedStatuses() throws Exception {
		User creator = createUser("Validation Creator");
		ExpenseGroup group = createGroup("Validate member", creator);
		addMember(group, creator);
		String token = bearerToken(creator);

		postMember(group.getId(), token, "missing@groups.example")
				.andExpect(status().isConflict());
		postMember(group.getId(), token, "   ")
				.andExpect(status().isBadRequest());
		postMember(group.getId(), token, "not-an-email")
				.andExpect(status().isBadRequest());
		postMember(group.getId(), token, "x".repeat(250) + "@example.com")
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/groups/{groupId}/members", group.getId())
						.header("Authorization", token)
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/groups/{groupId}/members", group.getId())
						.header("Authorization", token)
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void memberCannotAddButUnrelatedUsersCannotAddOrView() throws Exception {
		User creator = createUser("Access Creator");
		User member = createUser("Access Member");
		User unrelated = createUser("Unrelated User");
		User target = createUser("Access Target");
		ExpenseGroup group = createGroup("Access control", creator);
		addMember(group, creator);
		addMember(group, member);

		postMember(group.getId(), bearerToken(member), target.getEmail())
				.andExpect(status().isForbidden());
		postMember(group.getId(), bearerToken(unrelated), target.getEmail())
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/members", group.getId())
						.header("Authorization", bearerToken(unrelated)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/groups/{groupId}/members", 99999999L)
						.header("Authorization", bearerToken(creator)))
				.andExpect(status().isNotFound());
		postMember(99999999L, bearerToken(creator), target.getEmail())
				.andExpect(status().isNotFound());
	}

	@Test
	void creatorMustStillBeAMemberToAddAnotherUser() throws Exception {
		User creator = createUser("Former Creator Member");
		User target = createUser("Former Creator Target");
		ExpenseGroup group = createGroup("Creator membership required", creator);

		postMember(group.getId(), bearerToken(creator), target.getEmail())
				.andExpect(status().isNotFound());
	}

	@Test
	void memberEndpointsRejectMissingInvalidAndDeletedUserTokens() throws Exception {
		User creator = createUser("Token Creator");
		User deletedUser = createUser("Deleted Member Api User");
		ExpenseGroup group = createGroup("Token checks", creator);
		addMember(group, creator);
		String deletedToken = bearerToken(deletedUser);
		userRepository.delete(deletedUser);
		userRepository.flush();

		mockMvc.perform(get("/api/groups/{groupId}/members", group.getId()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/groups/{groupId}/members", group.getId())
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(objectMapper.writeValueAsString(java.util.Map.of(
								"email", creator.getEmail()))))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/groups/{groupId}/members", group.getId())
						.header("Authorization", "Bearer invalid.token.value"))
				.andExpect(status().isUnauthorized());
		postMember(group.getId(), "Bearer invalid.token.value", creator.getEmail())
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/groups/{groupId}/members", group.getId())
						.header("Authorization", deletedToken))
				.andExpect(status().isUnauthorized());
		postMember(group.getId(), deletedToken, creator.getEmail())
				.andExpect(status().isUnauthorized());
	}

	private @NonNull User createUser(String name) {
		return userRepository.saveAndFlush(new User(
				name,
				name.toLowerCase().replace(' ', '.') + "@groups.example",
				"{bcrypt}test"));
	}

	private ExpenseGroup createGroup(String name, User creator) {
		return expenseGroupRepository.saveAndFlush(new ExpenseGroup(name, creator));
	}

	private void addMember(ExpenseGroup group, User member) {
		groupMemberRepository.saveAndFlush(new GroupMember(group, member));
	}

	private org.springframework.test.web.servlet.ResultActions postMember(
			Long groupId, String token, String email) throws Exception {
		return mockMvc.perform(post("/api/groups/{groupId}/members", groupId)
				.header("Authorization", token)
				.contentType(MediaType.APPLICATION_JSON_VALUE)
				.content(objectMapper.writeValueAsString(java.util.Map.of("email", email))));
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
