package com.splitsmart.controller;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.splitsmart.dto.ApiErrorResponse;

@RestControllerAdvice(basePackages = "com.splitsmart.controller")
public class ApiExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<ApiErrorResponse> handleBadCredentials(HttpServletRequest request) {
		return response(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid email or password.", request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidation(HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed.", request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "malformed_request",
				"Request body is malformed or contains an unsupported value.", request);
	}

	@ExceptionHandler(MultipartException.class)
	public ResponseEntity<ApiErrorResponse> handleMalformedMultipartRequest(HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "malformed_request",
				"Multipart request is malformed.", request);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(HttpServletRequest request) {
		return response(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
				"HTTP method is not supported for this endpoint.", request);
	}

	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class })
	public ResponseEntity<ApiErrorResponse> handleInvalidParameters(HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "invalid_parameter", "A request parameter is invalid.", request);
	}

	@ExceptionHandler(MissingPathVariableException.class)
	public ResponseEntity<ApiErrorResponse> handleMissingPathVariable(
			MissingPathVariableException exception,
			HttpServletRequest request) {
		if (!exception.isMissingAfterConversion()) {
			LOGGER.error("Required URI template variable '{}' was not present.", exception.getVariableName());
			return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
					"An unexpected error occurred.", request);
		}
		return response(HttpStatus.BAD_REQUEST, "invalid_parameter", "A request path parameter is invalid.", request);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpServletRequest request) {
		return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
				"Content type is not supported.", request);
	}

	@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
	public ResponseEntity<ApiErrorResponse> handleNotAcceptable(HttpServletRequest request) {
		return response(HttpStatus.NOT_ACCEPTABLE, "not_acceptable",
				"Response format is not acceptable.", request);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleNotFound(HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, "not_found", "Endpoint not found.", request);
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiErrorResponse> handleResponseStatus(
			ResponseStatusException exception,
			HttpServletRequest request) {
		HttpStatusCode status = exception.getStatusCode();
		String message = exception.getReason() == null ? "Request failed." : exception.getReason();
		return ResponseEntity.status(status).body(error(codeFor(status), message, request));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
			Exception exception,
			HttpServletRequest request) {
		LOGGER.error("Unhandled API exception of type {}.", exception.getClass().getName());
		return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
				"An unexpected error occurred.", request);
	}

	private static ResponseEntity<ApiErrorResponse> response(
			HttpStatusCode status,
			String code,
			String message,
			HttpServletRequest request) {
		return ResponseEntity.status(status).body(error(code, message, request));
	}

	private static ApiErrorResponse error(String code, String message, HttpServletRequest request) {
		return new ApiErrorResponse(code, message, Instant.now(), request.getRequestURI());
	}

	private static String codeFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "bad_request";
			case 401 -> "unauthorized";
			case 403 -> "forbidden";
			case 404 -> "not_found";
			case 409 -> "conflict";
			default -> "request_failed";
		};
	}
}
