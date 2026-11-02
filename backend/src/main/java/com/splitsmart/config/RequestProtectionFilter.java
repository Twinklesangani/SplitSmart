package com.splitsmart.config;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitsmart.dto.ApiErrorResponse;

public final class RequestProtectionFilter extends OncePerRequestFilter {
    private final RequestProtectionProperties limits;
    private final AuthAttemptLimiter limiter;
    private final ObjectMapper mapper;
    private final Set<String> trustedProxies;

    public RequestProtectionFilter(RequestProtectionProperties limits, AuthAttemptLimiter limiter, ObjectMapper mapper) {
        this.limits = limits;
        this.limiter = limiter;
        this.mapper = mapper;
        this.trustedProxies = Arrays.stream(limits.trustedProxies().split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> {
                    String address = numericAddress(value);
                    if (address == null) throw new IllegalArgumentException("Trusted proxies must be literal IP addresses.");
                    return address;
                }).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws IOException, ServletException {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) { chain.doFilter(request, response); return; }
        String query = request.getQueryString();
        if (query != null && (query.length() > limits.maxQueryChars()
                || query.chars().filter(c -> c == '&').count() >= limits.maxQueryParameters())) {
            error(request, response, 400, "invalid_request", "The request query exceeds the supported limits.");
            return;
        }
        boolean login = "POST".equals(request.getMethod()) && "/api/auth/login".equals(path);
        boolean register = "POST".equals(request.getMethod()) && "/api/auth/register".equals(path);
        int maximum = login || register ? limits.authBodyBytes() : limits.apiBodyBytes();
        if (request.getContentLengthLong() > maximum) {
            error(request, response, 413, "payload_too_large", "The request body exceeds the supported size.");
            return;
        }
        if (login || register) {
            long retry = limiter.acquire((login ? "login-source:" : "register-source:") + clientAddress(request),
                    login ? limits.loginSourceLimit() : limits.registrationSourceLimit());
            if (rejectLimited(request, response, retry)) return;
        }
        // Read at most the limit plus one byte, even for chunked/unknown-length input.
        byte[] body = request.getInputStream().readNBytes(maximum + 1);
        if (body.length > maximum) {
            error(request, response, 413, "payload_too_large", "The request body exceeds the supported size.");
            return;
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private boolean rejectLimited(HttpServletRequest request, HttpServletResponse response, long seconds) throws IOException {
        if (seconds == 0) return false;
        response.setHeader("Retry-After", Long.toString(seconds));
        error(request, response, 429, "rate_limited", "Too many requests. Please wait and try again.");
        return true;
    }

    private void error(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), new ApiErrorResponse(code, message, Instant.now(), request.getRequestURI()));
    }

    String clientAddress(HttpServletRequest request) {
        String peer = numericAddress(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!trustedProxies.contains(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 512
                || java.util.Collections.list(request.getHeaders("X-Forwarded-For")).size() != 1) return peer;
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 10) return peer;
        String current = peer;
        for (int index = hops.length - 1; index >= 0 && trustedProxies.contains(current); index--) {
            String candidate = numericAddress(hops[index].trim());
            if (candidate == null) return peer;
            current = candidate;
        }
        return current;
    }

    private static String numericAddress(String address) {
        if (address == null || address.length() > 45 || !address.matches("[0-9a-fA-F:.]+")) return null;
        if (!address.contains(":") && !address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return null;
        if (!address.contains(":")) {
            for (String part : address.split("\\.")) {
                if (Integer.parseInt(part) > 255) return null;
            }
        }
        try { return InetAddress.getByName(address).getHostAddress(); }
        catch (java.net.UnknownHostException exception) { return null; }
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Nonblocking request bodies are not supported.");
                }
            };
        }
    }
}
