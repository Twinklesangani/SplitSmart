package com.splitsmart.config;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartException;

import com.splitsmart.dto.ApiErrorResponse;

public class MultipartExceptionFilter extends OncePerRequestFilter {

	private final ObjectMapper objectMapper;

	public MultipartExceptionFilter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(
			@NonNull HttpServletRequest request,
			@NonNull HttpServletResponse response,
			@NonNull FilterChain filterChain) throws ServletException, IOException {
		try {
			filterChain.doFilter(request, response);
		} catch (MultipartException exception) {
			if (!writeMalformedRequest(request, response)) {
				throw exception;
			}
		} catch (ServletException exception) {
			if (!hasMultipartCause(exception) || !writeMalformedRequest(request, response)) {
				throw exception;
			}
		}
	}

	private boolean writeMalformedRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (!request.getRequestURI().startsWith("/api/") || response.isCommitted()) {
			return false;
		}
		response.setStatus(HttpStatus.BAD_REQUEST.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
				"malformed_request", "Multipart request is malformed.",
				Instant.now(), request.getRequestURI()));
		return true;
	}

	private static boolean hasMultipartCause(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof MultipartException) {
				return true;
			}
		}
		return false;
	}
}
