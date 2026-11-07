package com.splitsmart.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="app.maintenance.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaintenanceGateIntegrationTest {
    @Autowired MockMvc mvc;

    @Test void registeredGatePrecedesAuthenticationAndPublicMutations() throws Exception {
        mvc.perform(get("/api/groups")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("maintenance"));
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content("{\"SYNTHETIC_MARKER\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After","60"));
        mvc.perform(post("/api/auth/reset-password"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(options("/api/groups"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void databaseReadinessStillWorksWithoutExposingConfiguration() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isServiceUnavailable());
    }
}
