package com.splitsmart;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitsmart.dto.ResetPasswordRequest;
import com.splitsmart.config.PasswordRecoveryProperties;
import com.splitsmart.repository.UserRepository;
import com.splitsmart.service.PasswordRecoveryService;
import com.splitsmart.service.PasswordResetDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.recovery.token-lifetime-minutes=15")
class PasswordRecoveryIntegrationTest {
    private static final String ORIGINAL = "OriginalPassword123!";
    private static final String REPLACEMENT = "ReplacementPassword123!";
    private static final java.util.concurrent.atomic.AtomicInteger SOURCES = new java.util.concurrent.atomic.AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtEncoder jwt;
    @Autowired PasswordRecoveryService recovery;
    @Autowired PasswordRecoveryProperties recoveryProperties;
    @Autowired @Qualifier("passwordRecoveryExecutor") ThreadPoolTaskExecutor executor;
    @MockitoBean PasswordResetDelivery delivery;
    @MockitoBean Clock clock;
    @MockitoSpyBean PasswordEncoder passwords;
    private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Instant> messageExpiries = new LinkedBlockingQueue<>();
    private String email;
    private long userId;
    private String accessToken;
    private String source;

    @BeforeEach
    void account() throws Exception {
        when(clock.instant()).thenReturn(Instant.now());
        when(delivery.isAvailable()).thenReturn(true);
        when(delivery.isConfigured()).thenReturn(true);
        doAnswer(call -> { messages.add(call.getArgument(1)); messageExpiries.add(call.getArgument(2)); return null; })
                .when(delivery).sendReset(anyString(), anyString(), any());
        email = UUID.randomUUID() + "@example.invalid";
        source = "10.1.0." + SOURCES.incrementAndGet();
        String registration = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Recovery Test", "email", email, "password", ORIGINAL))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        userId = json.readTree(registration).get("id").asLong();
        accessToken = login(ORIGINAL);
    }

    private MockHttpServletRequestBuilder fromSource(MockHttpServletRequestBuilder request) {
        return request.with(servlet -> { servlet.setRemoteAddr(source); return servlet; });
    }

    private String login(String password) throws Exception {
        var response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("accessToken").asText();
    }

    private String forgot(String address) throws Exception {
        return mvc.perform(fromSource(post("/api/auth/forgot-password")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", address))))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
    }

    private void drain() throws Exception { executor.submit(() -> {}).get(10, TimeUnit.SECONDS); }

    private void advance() {
        Instant next = clock.instant().plusSeconds(61);
        when(clock.instant()).thenReturn(next);
    }

    private String issue() throws Exception {
        forgot(email);
        drain();
        String token = messages.poll(2, TimeUnit.SECONDS);
        assertNotNull(token, "Fake delivery should capture a reset message");
        return token;
    }

    private org.springframework.test.web.servlet.ResultActions reset(String token, String password, String confirmation) throws Exception {
        return mvc.perform(fromSource(post("/api/auth/reset-password")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ResetPasswordRequest(token, password, confirmation))));
    }

    private String legacyToken() {
        Instant now = Instant.now();
        return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject(Long.toString(userId)).issuer("https://splitsmart.local")
                        .issuedAt(now).expiresAt(now.plusSeconds(3600)).build())).getTokenValue();
    }

    @Test
    void validResetHashesPasswordRevokesOldAndLegacyTokensAndAllowsNewLogin() throws Exception {
        String legacy = legacyToken();
        for (String token : new String[]{accessToken, legacy}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            mvc.perform(get("/api/groups").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }
        String token = issue();
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        String digest = jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens WHERE user_id = ?", String.class, userId);
        assertTrue(digest != null && digest.matches("[a-f0-9]{64}") && !digest.equals(token));
        reset(token, REPLACEMENT, REPLACEMENT).andExpect(status().isNoContent());
        var changed = users.findById(userId).orElseThrow();
        assertTrue(changed.getPasswordHash().startsWith("{bcrypt}"));
        assertTrue(passwords.matches(REPLACEMENT, changed.getPasswordHash()));
        assertEquals(1, changed.getAuthTokenVersion());
        for (String old : new String[]{accessToken, legacy}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + old)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/groups").header("Authorization", "Bearer " + old)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", ORIGINAL))))
                .andExpect(status().isUnauthorized());
        String fresh = login(REPLACEMENT);
        mvc.perform(get("/api/groups").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
        reset(token, ORIGINAL, ORIGINAL).andExpect(status().isBadRequest());
    }

    @Test
    void configuredLifetimeIsPersistedAndMatchesTheDeliveredLink() throws Exception {
        issue();
        LocalDateTime[] times = jdbc.queryForObject(
                "SELECT created_at, expires_at FROM password_reset_tokens WHERE user_id = ?",
                (rows, index) -> new LocalDateTime[] { rows.getTimestamp("created_at").toLocalDateTime(),
                        rows.getTimestamp("expires_at").toLocalDateTime() }, userId);
        LocalDateTime created = times[0];
        LocalDateTime stored = times[1];
        LocalDateTime expected = created.plusMinutes(recoveryProperties.tokenLifetimeMinutes());
        assertEquals(15, recoveryProperties.tokenLifetimeMinutes());
        assertEquals(expected, stored);
        assertEquals(stored.toInstant(java.time.ZoneOffset.UTC), messageExpiries.poll(2, TimeUnit.SECONDS));
    }

    @Test
    void invalidExpiredAndUsedTokensReturnSameSafeError() throws Exception {
        String token = issue();
        String unknown = reset("A".repeat(43), REPLACEMENT, REPLACEMENT).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE password_reset_tokens SET expires_at = ? WHERE user_id = ?",
                LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC).minusSeconds(1), userId);
        String expired = reset(token, REPLACEMENT, REPLACEMENT).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(unknown).get("message"), json.readTree(expired).get("message"));
        assertEquals(0, users.findById(userId).orElseThrow().getAuthTokenVersion());
        assertTrue(passwords.matches(ORIGINAL, users.findById(userId).orElseThrow().getPasswordHash()));
    }

    @Test
    void concurrentConsumptionSucceedsExactlyOnceAndInvalidatesSiblingTokens() throws Exception {
        String token = issue();
        advance();
        String sibling = issue();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var action = (java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try { recovery.reset(new ResetPasswordRequest(token, REPLACEMENT, REPLACEMENT)); return true; }
                catch (ResponseStatusException invalid) { return false; }
            };
            var first = pool.submit(action);
            var second = pool.submit(action);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        reset(sibling, ORIGINAL, ORIGINAL).andExpect(status().isBadRequest());
        assertEquals(1, users.findById(userId).orElseThrow().getAuthTokenVersion());
    }

    @Test
    void passwordFailuresRollbackConsumptionAndVersion() throws Exception {
        String token = issue();
        doThrow(new IllegalStateException("Synthetic encoder failure")).when(passwords).encode(REPLACEMENT);
        reset(token, REPLACEMENT, REPLACEMENT).andExpect(status().isInternalServerError());
        assertEquals(0, users.findById(userId).orElseThrow().getAuthTokenVersion());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? AND consumed_at IS NOT NULL", Integer.class, userId));
        doCallRealMethod().when(passwords).encode(REPLACEMENT);
        reset(token, REPLACEMENT, REPLACEMENT).andExpect(status().isNoContent());
    }

    @Test
    void genericResponsesAndDisabledOrFailedDeliveryNeverRevealTokens() throws Exception {
        String known = forgot(email);
        String unknown = forgot(UUID.randomUUID() + "@example.invalid");
        assertEquals(known, unknown);
        drain();
        assertEquals(1, messages.size());
        assertFalse(known.contains(messages.peek()));
        when(delivery.isAvailable()).thenReturn(false);
        when(delivery.isConfigured()).thenReturn(false);
        String disabledKnown = mvc.perform(fromSource(post("/api/auth/forgot-password")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        String disabledUnknown = mvc.perform(fromSource(post("/api/auth/forgot-password")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "unknown@example.invalid"))))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertEquals(disabledKnown, disabledUnknown);
        drain();
        assertEquals(1, messages.size());
        when(delivery.isAvailable()).thenReturn(true);
        when(delivery.isConfigured()).thenReturn(true);
        advance();
        doThrow(new IllegalStateException("Synthetic provider failure")).when(delivery).sendReset(anyString(), anyString(), any());
        assertEquals(known, forgot(email));
        drain();
        assertEquals(known, forgot(UUID.randomUUID() + "@example.invalid"));
        drain();
        // Private transport shutdown must not turn public responses into an account oracle.
        when(delivery.isAvailable()).thenReturn(false);
        assertEquals(known, forgot(email));
        assertEquals(known, forgot(UUID.randomUUID() + "@example.invalid"));
        drain();
    }

    @Test
    void durableCooldownAndHourlyBudgetLimitDelivery() throws Exception {
        issue();
        forgot(email.toUpperCase(java.util.Locale.ROOT));
        drain();
        assertEquals(0, messages.size());
        for (int index = 0; index < 2; index++) {
            advance();
            issue();
        }
        advance();
        forgot(email);
        drain();
        assertEquals(0, messages.size());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?", Integer.class, userId));
    }

    @Test
    void sourceLimitsAndRequestValidationProtectPublicEndpoints() throws Exception {
        for (int i = 0; i < 10; i++) forgot(UUID.randomUUID() + "@example.invalid");
        mvc.perform(fromSource(post("/api/auth/forgot-password")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        String token = "A".repeat(43);
        reset(token, "short", "short").andExpect(status().isBadRequest());
        reset(token, REPLACEMENT, ORIGINAL).andExpect(status().isBadRequest());
        reset(token, "é".repeat(40), "é".repeat(40)).andExpect(status().isBadRequest());
        for (int i = 0; i < 7; i++) reset(token, REPLACEMENT, REPLACEMENT).andExpect(status().isBadRequest());
        reset(token, REPLACEMENT, REPLACEMENT).andExpect(status().isTooManyRequests());
        assertFalse(new ResetPasswordRequest(token, REPLACEMENT, REPLACEMENT).toString().contains(token));
        drain();
    }
    @Test
    void passwordChangeNoticeRunsOnlyAfterCommitAndCannotUndoTheReset() throws Exception {
        String token=issue();
        var sawCommittedVersion=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            sawCommittedVersion.set(users.findById(userId).orElseThrow().getAuthTokenVersion()==1);
            throw new IllegalStateException("Synthetic delivery failure");
        }).when(delivery).sendPasswordChanged(email);
        reset(token,REPLACEMENT,REPLACEMENT).andExpect(status().isNoContent());
        drain();
        assertTrue(sawCommittedVersion.get(),"Delivery must observe the committed token version.");
        assertTrue(passwords.matches(REPLACEMENT,users.findById(userId).orElseThrow().getPasswordHash()));
        reset(token,ORIGINAL,ORIGINAL).andExpect(status().isBadRequest());
        drain();
        verify(delivery,times(1)).sendPasswordChanged(email);
    }
}
