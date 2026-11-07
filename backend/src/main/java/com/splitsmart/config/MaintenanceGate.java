package com.splitsmart.config;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Operator-controlled full API pause; no credential, token or request bypass. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MaintenanceGate extends OncePerRequestFilter {
    private static final Set<String> PROBES = Set.of(
            "/actuator/health/readiness", "/actuator/health/liveness");
    private final boolean enabled;

    public MaintenanceGate(@Value("${app.maintenance.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        // Exact probe paths only. A nested/encoded API path cannot gain a bypass.
        boolean probe = ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && PROBES.contains(request.getRequestURI());
        if (!enabled || probe) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", "60");
        response.getWriter().write("{\"code\":\"maintenance\",\"message\":\"SplitSmart is temporarily unavailable. Please try again shortly.\"}");
    }
}
