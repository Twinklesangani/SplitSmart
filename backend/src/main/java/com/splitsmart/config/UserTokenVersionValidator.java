package com.splitsmart.config;

import com.splitsmart.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Checked for every bearer-authenticated API, without a revocation cache. */
final class UserTokenVersionValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2TokenValidatorResult INVALID = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Authentication is required.", null));
    private final UserRepository users;

    UserTokenVersionValidator(UserRepository users) { this.users = users; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            Object claim = token.getClaims().get("ver");
            // Pre-rollout JWTs are version zero; a reset permanently revokes them for that user.
            long version = claim == null ? 0 : new java.math.BigDecimal(claim.toString()).longValueExact();
            if (version < 0 || claim instanceof String || claim instanceof Boolean) return INVALID;
            return users.findById(Long.parseLong(token.getSubject()))
                    .filter(user -> user.getAuthTokenVersion() == version)
                    .map(user -> OAuth2TokenValidatorResult.success()).orElse(INVALID);
        } catch (RuntimeException failure) {
            // A database outage must fail closed, without disclosing account or database details.
            return INVALID;
        }
    }
}
