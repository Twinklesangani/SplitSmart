package com.splitsmart.service;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.splitsmart.dto.RegisterRequest;
import com.splitsmart.entity.User;
import com.splitsmart.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

	private UserRepository userRepository;
	private AuthService authService;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		authService = new AuthService(
				userRepository,
				PasswordEncoderFactories.createDelegatingPasswordEncoder(),
				mock(JwtEncoder.class));
	}

	@Test
	void concurrentEmailUniqueConstraintViolationReturnsConflict() {
		when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(
				new DataIntegrityViolationException("Unique constraint violation",
						new ConstraintViolationException("Duplicate email", null, "uk_users_email")));

		ResponseStatusException exception = assertThrows(
				ResponseStatusException.class,
				() -> authService.register(new RegisterRequest("Test User", "test@example.com", "SecurePass123!")));

		assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
	}

	@Test
	void unrelatedIntegrityViolationIsNotReportedAsDuplicateEmail() {
		when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
		DataIntegrityViolationException databaseFailure = new DataIntegrityViolationException(
				"Different constraint violation",
				new ConstraintViolationException("Foreign key violation", null, "fk_other_constraint"));
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(databaseFailure);

		DataIntegrityViolationException exception = assertThrows(
				DataIntegrityViolationException.class,
				() -> authService.register(new RegisterRequest("Test User", "test@example.com", "SecurePass123!")));

		assertEquals(databaseFailure, exception);
	}

	@Test
	void missingCurrentUserSubjectIsRejectedAsInvalidCredentials() {
		assertThrows(BadCredentialsException.class, () -> authService.currentUser(null));
	}
}
