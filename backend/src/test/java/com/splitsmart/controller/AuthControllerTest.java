package com.splitsmart.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.splitsmart.entity.User;
import com.splitsmart.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerTest {

	private static final String VALID_PASSWORD = "SecurePass123!";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Test
	void loginReturnsSignedOneHourTokenWithOnlyRequiredClaims() throws Exception {
		User user = createUser("Login User", "login@example.com");
		String token = login(" LOGIN@EXAMPLE.COM ", VALID_PASSWORD);

		Jwt decoded = jwtDecoder.decode(token);
		assertEquals("https://splitsmart.local", decoded.getIssuer().toString());
		assertEquals(user.getId().toString(), decoded.getSubject());
		assertEquals(3600, Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()).getSeconds());
		assertEquals(java.util.Set.of("iss", "sub", "iat", "exp"), decoded.getClaims().keySet());
	}

	@Test
	void invalidEmailAndPasswordReturnEquivalentSafeUnauthorizedResponses() throws Exception {
		createUser("Login User", "login@example.com");

		String wrongPassword = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(loginJson("login@example.com", "WrongPassword123!")))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON_VALUE))
				.andExpect(jsonPath("$.code").value("unauthorized"))
				.andExpect(jsonPath("$.message").value("Invalid email or password."))
				.andReturn().getResponse().getContentAsString();
		String unknownEmail = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(loginJson("unknown@example.com", VALID_PASSWORD)))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON_VALUE))
				.andExpect(jsonPath("$.code").value("unauthorized"))
				.andExpect(jsonPath("$.message").value("Invalid email or password."))
				.andReturn().getResponse().getContentAsString();

		JsonNode wrongPasswordError = objectMapper.readTree(wrongPassword);
		JsonNode unknownEmailError = objectMapper.readTree(unknownEmail);
		assertEquals(wrongPasswordError.get("code"), unknownEmailError.get("code"));
		assertEquals(wrongPasswordError.get("message"), unknownEmailError.get("message"));
		assertTrue(!wrongPassword.contains("WrongPassword123!"));
		assertTrue(!unknownEmail.contains("unknown@example.com"));
	}

	@Test
	void malformedLoginRequestReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{\"email\":\"login@example.com\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void currentUserRequiresTokenAndReturnsOnlyAuthenticatedUser() throws Exception {
		User authenticatedUser = createUser("Authenticated User", "authenticated@example.com");
		User otherUser = createUser("Other User", "other@example.com");
		String token = login("authenticated@example.com", VALID_PASSWORD);

		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized());
		String response = mockMvc.perform(get("/api/auth/me")
						.param("id", otherUser.getId().toString())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON_VALUE))
				.andExpect(jsonPath("$.id").value(authenticatedUser.getId()))
				.andExpect(jsonPath("$.name").value("Authenticated User"))
				.andExpect(jsonPath("$.email").value("authenticated@example.com"))
				.andReturn().getResponse().getContentAsString();

		assertTrue(!response.contains("password"));
	}

	@Test
	void malformedTamperedWrongSignatureAndExpiredTokensAreUnauthorized() throws Exception {
		User user = createUser("Token User", "token@example.com");
		String validToken = issueToken(user.getId().toString(), Instant.now(), Instant.now().plusSeconds(3600),
				jwtEncoder);
		String tamperedToken = tamperSignature(validToken);
		JwtEncoder differentKeyEncoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(new SecretKeySpec(
						Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
						"HmacSHA256")));
		String wrongSignatureToken = issueToken(
				user.getId().toString(), Instant.now(), Instant.now().plusSeconds(3600), differentKeyEncoder);
		Instant expiredAt = Instant.now().minusSeconds(600);
		String expiredToken = issueToken(user.getId().toString(), expiredAt.minusSeconds(3600), expiredAt, jwtEncoder);

		assertMeUnauthorized("not.a.jwt");
		assertMeUnauthorized(tamperedToken);
		assertMeUnauthorized(wrongSignatureToken);
		assertMeUnauthorized(expiredToken);
	}

	@Test
	void deletedUserCannotUsePreviouslyIssuedToken() throws Exception {
		User user = createUser("Deleted User", "deleted@example.com");
		String token = login("deleted@example.com", VALID_PASSWORD);
		userRepository.delete(user);
		userRepository.flush();

		mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void registrationReturnsNormalizedUserWithoutPassword() throws Exception {
		String response = mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":" Test User ","email":" Test@Example.COM ","password":"SecurePass123!"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON_VALUE))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Test User"))
				.andExpect(jsonPath("$.email").value("test@example.com"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertTrue(!response.contains("password"));
		assertTrue(!response.contains("password_hash"));

		User user = userRepository.findByEmail("test@example.com").orElseThrow();
		assertNotNull(user.getId());
		assertNotEquals(VALID_PASSWORD, user.getPasswordHash());
		assertTrue(passwordEncoder.matches(VALID_PASSWORD, user.getPasswordHash()));
	}

	@Test
	void duplicateEmailReturnsConflict() throws Exception {
		registerValidUser();

		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"Another User","email":"test@example.com","password":"SecurePass123!"}
								"""))
				.andExpect(status().isConflict());
	}

	@Test
	void duplicateEmailWithDifferentCaseReturnsConflict() throws Exception {
		registerValidUser();

		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"Another User","email":" TEST@EXAMPLE.COM ","password":"SecurePass123!"}
								"""))
				.andExpect(status().isConflict());
	}

	@Test
	void invalidEmailReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"Test User","email":"not-an-email","password":"SecurePass123!"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("validation_failed"))
				.andExpect(jsonPath("$.message").value("Request validation failed."));
	}

	@Test
	void blankNameReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"   ","email":"test@example.com","password":"SecurePass123!"}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void registrationRejectsNonStringTextFields() throws Exception {
		long initialUserCount = userRepository.count();
		for (String requestBody : List.of(
				"""
				{"name":false,"email":"test@example.com","password":"SecurePass123!"}
				""",
				"""
				{"name":"Test User","email":false,"password":"SecurePass123!"}
				""",
				"""
				{"name":"Test User","email":"test@example.com","password":12345678}
				""")) {
			mockMvc.perform(post("/api/auth/register")
							.contentType(MediaType.APPLICATION_JSON_VALUE)
							.content(requestBody))
					.andExpect(status().isBadRequest());
		}
		assertEquals(initialUserCount, userRepository.count());
	}

	@Test
	void missingAndBlankPasswordReturnsBadRequestWithoutCreatingAnAccount() throws Exception {
		long initialUserCount = userRepository.count();
		for (String requestBody : List.of(
				"""
				{"name":"Test User","email":"test@example.com"}
				""",
				"""
				{"name":"Test User","email":"test@example.com","password":null}
				""",
				"""
				{"name":"Test User","email":"test@example.com","password":"        "}
				""")) {
			mockMvc.perform(post("/api/auth/register")
							.contentType(MediaType.APPLICATION_JSON_VALUE)
							.content(requestBody))
					.andExpect(status().isBadRequest());
		}
		assertEquals(initialUserCount, userRepository.count());
	}

	@Test
	void shortPasswordReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"Test User","email":"test@example.com","password":"short"}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void acceptsAsciiPasswordAtThe72ByteBoundaryWithoutTruncation() throws Exception {
		String password = "A".repeat(72);

		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"Boundary User","email":"ascii-boundary@example.com","password":"%s"}
								""".formatted(password)))
				.andExpect(status().isCreated());

		User user = userRepository.findByEmail("ascii-boundary@example.com").orElseThrow();
		assertTrue(passwordEncoder.matches(password, user.getPasswordHash()));
		login("ascii-boundary@example.com", password);
	}

	@Test
	void acceptsMultibytePasswordAtThe72Utf8ByteBoundary() throws Exception {
		String password = "€".repeat(24);

		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"Boundary User","email":"unicode-boundary@example.com","password":"%s"}
								""".formatted(password)))
				.andExpect(status().isCreated());

		User user = userRepository.findByEmail("unicode-boundary@example.com").orElseThrow();
		assertTrue(passwordEncoder.matches(password, user.getPasswordHash()));
		login("unicode-boundary@example.com", password);
	}

	@Test
	void rejectsPasswordAbove72Utf8BytesWithoutCreatingAnAccount() throws Exception {
		long initialUserCount = userRepository.count();
		String password = "€".repeat(24) + "a";

		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"Boundary User","email":"over-boundary@example.com","password":"%s"}
								""".formatted(password)))
				.andExpect(status().isBadRequest());

		assertEquals(initialUserCount, userRepository.count());
	}

	@Test
	void passwordOver72Utf8BytesReturnsBadRequest() throws Exception {
		String password = "😀".repeat(19);

		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"Test User","email":"test@example.com","password":"%s"}
								""".formatted(password)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void malformedJsonReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON_VALUE))
				.andExpect(jsonPath("$.code").value("malformed_request"))
				.andExpect(jsonPath("$.timestamp").isNotEmpty())
				.andExpect(jsonPath("$.path").value("/api/auth/register"));
	}

	private void registerValidUser() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content("""
								{"name":"Test User","email":"test@example.com","password":"SecurePass123!"}
								"""))
				.andExpect(status().isCreated());
	}

	private @NonNull User createUser(String name, String email) {
		return userRepository.saveAndFlush(new User(name, email, passwordEncoder.encode(VALID_PASSWORD)));
	}

	private String login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON_VALUE)
						.content(loginJson(email, password)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(3600))
				.andReturn().getResponse().getContentAsString()
				.replaceAll("(?s).*\"accessToken\":\"([^\"]+)\".*", "$1");
	}

	private String loginJson(String email, String password) {
		return """
				{"email":"%s","password":"%s"}
				""".formatted(email, password);
	}

	private String issueToken(String subject, Instant issuedAt, Instant expiresAt, JwtEncoder encoder) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("https://splitsmart.local")
				.subject(subject)
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.build();
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	private String tamperSignature(String token) {
		String[] parts = token.split("\\.");
		String signature = parts[2];
		char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
		parts[2] = replacement + signature.substring(1);
		return String.join(".", parts);
	}

	private void assertMeUnauthorized(String token) throws Exception {
		mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}
}
