package com.splitsmart.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceGateTest {
    @Test void enabledGateStopsAllApiMethodsBeforeAuthenticationOrControllers() throws Exception {
        for (String method : new String[]{"GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD"}) {
            var request = new MockHttpServletRequest(method,"/api/auth/login");
            request.addHeader("Authorization","Bearer SYNTHETIC_NOT_A_TOKEN");
            request.addHeader("X-Maintenance-Bypass","true");
            var response = new MockHttpServletResponse();
            var reached = new AtomicBoolean();
            new MaintenanceGate(true).doFilter(request,response,(a,b)->reached.set(true));
            assertFalse(reached.get());
            assertEquals(503,response.getStatus());
            assertEquals("60",response.getHeader("Retry-After"));
            assertEquals("no-store",response.getHeader("Cache-Control"));
            assertFalse(response.getContentAsString().contains("SYNTHETIC"));
        }
    }

    @Test void onlyExactReadOnlyActuatorProbesPass() throws Exception {
        for (String path : new String[]{"/actuator/health/readiness","/actuator/health/liveness"}) {
            var reached = new AtomicBoolean();
            new MaintenanceGate(true).doFilter(new MockHttpServletRequest("GET",path),
                    new MockHttpServletResponse(),(a,b)->reached.set(true));
            assertTrue(reached.get());
        }
        for (String path : new String[]{"/actuator/health/readiness/../../api/expenses",
                "/actuator/health/readiness;ignored", "/api/health", "/actuator/health", "/error"}) {
            var reached = new AtomicBoolean();
            new MaintenanceGate(true).doFilter(new MockHttpServletRequest("GET",path),
                    new MockHttpServletResponse(),(a,b)->reached.set(true));
            assertFalse(reached.get());
        }
        var response = new MockHttpServletResponse();
        new MaintenanceGate(true).doFilter(new MockHttpServletRequest("POST","/actuator/health/readiness"),
                response,(a,b)->fail("Probe write bypass"));
        assertEquals(503,response.getStatus());
    }

    @Test void disabledGatePreservesNormalSecurityChain() throws Exception {
        var reached = new AtomicBoolean();
        new MaintenanceGate(false).doFilter(new MockHttpServletRequest("POST","/api/expenses"),
                new MockHttpServletResponse(),(a,b)->reached.set(true));
        assertTrue(reached.get());
    }

    @Test void pausedSchedulerNeverContactsDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        new NotificationMaintenance(jdbc,new MaintenanceGate(true)).cleanup();
        verifyNoInteractions(jdbc);
    }

    @Test void normalSchedulerStillChecksRetention() {
        var jdbc = mock(JdbcTemplate.class);
        new NotificationMaintenance(jdbc,new MaintenanceGate(false)).cleanup();
        verify(jdbc).queryForList(anyString(),eq(Long.class),any(Object.class));
    }
}
