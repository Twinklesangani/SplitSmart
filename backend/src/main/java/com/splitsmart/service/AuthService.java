package com.splitsmart.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.splitsmart.config.AuthAttemptLimiter;
import com.splitsmart.config.AuthRateLimitException;
import com.splitsmart.config.RequestProtectionProperties;
import com.splitsmart.dto.LoginRequest;
import com.splitsmart.dto.LoginResponse;
import com.splitsmart.dto.RegisterRequest;
import com.splitsmart.dto.RegisterResponse;
import com.splitsmart.entity.User;
import com.splitsmart.repository.UserRepository;

@Service
public class AuthService {

	private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email";
	private static final long ACCESS_TOKEN_LIFETIME_SECONDS = 3600;
	private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password.";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtEncoder jwtEncoder;
    private final AuthAttemptLimiter limiter;
    private final RequestProtectionProperties limits;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
            AuthAttemptLimiter limiter, RequestProtectionProperties limits) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtEncoder = jwtEncoder;
        this.limiter = limiter;
        this.limits = limits;
	}

	public RegisterResponse register(RegisterRequest request) {
		if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes.");
		}

		if (userRepository.existsByEmail(request.email())) {
			throw emailAlreadyRegistered();
		}

		User user = new User(
				request.name(),
				request.email(),
				passwordEncoder.encode(request.password()));

		try {
			User savedUser = userRepository.saveAndFlush(user);
			return new RegisterResponse(savedUser.getId(), savedUser.getName(), savedUser.getEmail());
		} catch (DataIntegrityViolationException exception) {
			if (isEmailUniqueConstraintViolation(exception)) {
				throw emailAlreadyRegistered();
			}
			throw exception;
		}
	}

	public LoginResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }
		Optional<User> user = userRepository.findByEmail(request.email());
        // Resolve the real account first: database-equivalent email aliases must share a budget.
        String target = user.isPresent()
                ? "login-target:user:" + user.get().getId()
                : "login-target:email:" + emailHash(request.email());
        long retryAfter = limiter.acquire(target, limits.loginTargetLimit());
        if (retryAfter > 0) throw new AuthRateLimitException(retryAfter);
		if (user.isEmpty() || !passwordEncoder.matches(request.password(), user.get().getPasswordHash())) {
			throw new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE);
		}

		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plusSeconds(ACCESS_TOKEN_LIFETIME_SECONDS);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("https://splitsmart.local")
				.subject(user.get().getId().toString())
				.claim("ver", user.get().getAuthTokenVersion())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

		return new LoginResponse(token, "Bearer", ACCESS_TOKEN_LIFETIME_SECONDS);
	}

    private static String emailHash(String email) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(email.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

	public RegisterResponse currentUser(String subject) {
		final long userId;
		if (subject == null) {
			throw new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE);
		}
		try {
			userId = Long.parseLong(subject);
		} catch (NumberFormatException exception) {
			throw new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE);
		}

		User user = userRepository.findById(userId)
				.orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE));
		return new RegisterResponse(user.getId(), user.getName(), user.getEmail());
	}

	private static boolean isEmailUniqueConstraintViolation(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
					&& EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

	private static ResponseStatusException emailAlreadyRegistered() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
	}
}
