package com.splitsmart.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.security.login-source-limit=3", "app.security.login-target-limit=2",
    "app.security.registration-source-limit=2", "app.security.auth-body-bytes=1024"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAbuseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired com.splitsmart.repository.UserRepository users;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @Test
    void sourceLimitCoversChangingUsernamesAndForgedForwarding() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("192.0.2.10"); return r; })
                .header("X-Forwarded-For", "198.51.100." + i)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"absent" + i + "@example.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("192.0.2.10"); return r; })
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"other@example.test\",\"password\":\"wrong\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void targetLimitCoversDifferentSourcesAndUnknownAccounts() throws Exception {
        for (int i = 0; i < 3; i++) {
            final String source = "192.0.2." + (20 + i);
            var result = mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr(source); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"                                                                                                                                                                                                                                                                target@example.test \",\"password\":\"wrong\"}"));
            if (i < 2) result.andExpect(status().isUnauthorized());
            else result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
    }

    @Test
    void registrationThrottlesValidRequestsBeforeAccountCreation() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            String email = "reg" + i + "-" + suffix + "@example.test";
            var result = mvc.perform(post("/api/auth/register").with(r -> { r.setRemoteAddr("192.0.2.40"); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Synthetic\",\"email\":\"" + email + "\",\"password\":\"SecurePass123!\"}"));
            if (i < 2) result.andExpect(status().isCreated());
            else result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
        org.junit.jupiter.api.Assertions.assertFalse(users.existsByEmail("reg2-" + suffix + "@example.test"));
    }

    @Test
    void oversizedJsonIsRejectedBeforeApplicationValidation() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"" + "x".repeat(2048) + "\"}"))
            .andExpect(status().isPayloadTooLarge());
    }
    @Test
    void realTomcatRejectsChunkedOversizeAndLargeHeaders() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var base = "http://127.0.0.1:" + port;
        var chunked = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/auth/login"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(() ->
                    new java.io.ByteArrayInputStream(("x".repeat(2048)).getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .build();
        org.junit.jupiter.api.Assertions.assertEquals(413,
                client.send(chunked, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        var headers = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/health"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("X-Synthetic", "x".repeat(17000)).GET().build();
        int status = client.send(headers, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        org.junit.jupiter.api.Assertions.assertTrue(status >= 400 && status < 500);
    }

    @Test
    void queryAndNestedInputAreBoundedWhileHealthRemainsAvailable() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health")
                .with(r -> { r.setQueryString("q=" + "x".repeat(2049)); return r; })).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health")
                .queryParam("q", "ok")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("192.0.2.80"); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.test\",\"password\":\"x\",\"extra\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void successfulAuthenticationStillCountsAndDoesNotRevealLimiterAccountExistence() throws Exception {
        String email = "normal-" + java.util.UUID.randomUUID() + "@example.test";
        String password = "SecurePass123!";
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        mvc.perform(post("/api/auth/register").with(r -> { r.setRemoteAddr("192.0.2.90"); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Synthetic\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("192.0.2.90"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("192.0.2.91"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("rate_limited"));
    }

}
