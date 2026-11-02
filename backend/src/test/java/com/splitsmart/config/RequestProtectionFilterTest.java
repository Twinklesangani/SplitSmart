package com.splitsmart.config;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class RequestProtectionFilterTest {
    private RequestProtectionFilter filter(String proxies) {
        var limits = new RequestProtectionProperties(60, 3, 2, 2, 16, 1024, 65536, 2048, 50, proxies);
        return new RequestProtectionFilter(limits, new AuthAttemptLimiter(16, 60, Clock.systemUTC()), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void forwardedChainsAreTrustedOnlyFromExplicitPeersAndStopAtFirstUntrustedHop() {
        var guard = filter("192.0.2.1,192.0.2.2");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.50");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        assertEquals("192.0.2.50", guard.clientAddress(request));
        request.setRemoteAddr("192.0.2.1");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "203.0.113.99,198.51.100.10,192.0.2.2");
        assertEquals("198.51.100.10", guard.clientAddress(request));
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "attacker.example");
        assertEquals("192.0.2.1", guard.clientAddress(request));
        request.removeHeader("X-Forwarded-For");
        request.addHeader("Forwarded", "for=203.0.113.1");
        assertEquals("192.0.2.1", guard.clientAddress(request));
    }

    @Test
    void invalidProxyConfigurationFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> filter("attacker.example"));
        assertThrows(IllegalArgumentException.class, () -> filter("999.0.0.1"));
    }

    @Test
    void bodyGuardReadsOnlyLimitPlusOneForUnknownLengthAndNothingForOversizedKnownLength() throws Exception {
        for (long declaredLength : new long[]{-1, 2000}) {
            var count = new AtomicInteger();
            var request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setRemoteAddr("192.0.2.70");
            var wrapped = new HttpServletRequestWrapper(request) {
                @Override public long getContentLengthLong() { return declaredLength; }
                @Override public ServletInputStream getInputStream() {
                    return new ServletInputStream() {
                        public int read() { count.incrementAndGet(); return 'x'; }
                        public int read(byte[] bytes, int offset, int length) {
                            java.util.Arrays.fill(bytes, offset, offset + length, (byte) 'x');
                            count.addAndGet(length);
                            return length;
                        }
                        public boolean isFinished() { return false; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) {}
                    };
                }
            };
            var response = new MockHttpServletResponse();
            filter("").doFilter(wrapped, response, (r, s) -> fail("Oversized input reached the application."));
            assertEquals(413, response.getStatus());
            assertEquals(declaredLength < 0 ? 1025 : 0, count.get());
        }
    }
    @Test
    void expenseParticipantCardinalityIsBoundedWithoutChangingMoneyPrecision() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var split = new com.splitsmart.dto.CreateExpenseSplitRequest(1L, new java.math.BigDecimal("0.01"));
            var valid = new com.splitsmart.dto.CreateExpenseRequest("Synthetic", new java.math.BigDecimal("1.28"),
                    1L, "INR", java.util.Collections.nCopies(128, split));
            assertTrue(factory.getValidator().validate(valid).isEmpty());
            var invalid = new com.splitsmart.dto.CreateExpenseRequest("Synthetic", new java.math.BigDecimal("1.29"),
                    1L, "INR", java.util.Collections.nCopies(129, split));
            assertTrue(factory.getValidator().validate(invalid).stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("splits")));
        }
    }

    @Test
    void queryParameterCountAcceptsTheBoundaryAndRejectsOneMore() throws Exception {
        for (int parameters : new int[]{50, 51}) {
            var request = new MockHttpServletRequest("GET", "/api/health");
            request.setQueryString(String.join("&", java.util.Collections.nCopies(parameters, "q=x")));
            var response = new MockHttpServletResponse();
            var reached = new java.util.concurrent.atomic.AtomicBoolean();
            filter("").doFilter(request, response, (r, s) -> reached.set(true));
            assertEquals(parameters == 50, reached.get());
            assertEquals(parameters == 50 ? 200 : 400, response.getStatus());
        }
    }

}
