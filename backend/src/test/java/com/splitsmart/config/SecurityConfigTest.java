package com.splitsmart.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

	private static final String FRONTEND_ORIGIN = "http://localhost:5173";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void allowsPreflightFromConfiguredFrontendOrigin() throws Exception {
		mockMvc.perform(options("/api/groups")
						.header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
				.andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
	}

	@Test
	void rejectsPreflightFromUnconfiguredOrigin() throws Exception {
		mockMvc.perform(options("/api/groups")
						.header(HttpHeaders.ORIGIN, "https://untrusted.example")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void missingAuthenticationReturnsSafeJsonAndPreservesAllowedCorsOrigin() throws Exception {
		mockMvc.perform(get("/api/groups").header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("unauthorized"))
				.andExpect(jsonPath("$.message").value("Authentication is required."))
				.andExpect(jsonPath("$.path").value("/api/groups"));
	}

	@Test
	void exposesOnlyRedactedLivenessAndDatabaseReadinessProbes() throws Exception {
		for (String path : new String[] {
				"/actuator/health/liveness",
				"/actuator/health/readiness"}) {
			mockMvc.perform(get(path))
					.andExpect(status().isOk())
					.andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
		}
	}

	@Test
	void keepsActuatorRootAndOtherManagementEndpointsInaccessible() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/actuator/env"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void unsupportedContentTypeReturnsSafeJsonError() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.TEXT_PLAIN)
						.content("sensitive-request-value"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("unsupported_media_type"))
				.andExpect(jsonPath("$.message").value("Content type is not supported."));
	}

	@Test
	void wildcardCorsOriginsAreRejected() {
		assertThrows(IllegalStateException.class,
				() -> new SecurityConfig().corsConfigurationSource("https://example.com,*"));
	}
}
