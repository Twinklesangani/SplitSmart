package com.splitsmart.config;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.splitsmart.dto.ApiErrorResponse;

@Configuration
public class SecurityConfig {

	@Bean
	FilterRegistrationBean<MultipartExceptionFilter> multipartExceptionFilter(ObjectMapper objectMapper) {
		FilterRegistrationBean<MultipartExceptionFilter> registration = new FilterRegistrationBean<>();
		registration.setFilter(new MultipartExceptionFilter(objectMapper));
		registration.addUrlPatterns("/api/*");
		registration.setOrder(Ordered.LOWEST_PRECEDENCE);
		return registration;
	}

	@Bean
	SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			ObjectMapper objectMapper,
			CorsConfigurationSource corsConfigurationSource) throws Exception {
		AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
				writeError(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
						"unauthorized", "Authentication is required.");
		AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
				writeError(objectMapper, request, response, HttpStatus.FORBIDDEN,
						"forbidden", "Access is denied.");

		return http
				// Stateless bearer-token APIs do not authenticate with cookies or server sessions.
				.csrf(csrf -> csrf.disable())
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						// Allow Spring to preserve API error statuses without exposing direct /error requests.
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/health").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
						.requestMatchers("/api/**").authenticated()
						.anyRequest().denyAll())
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(
			@Value("${app.cors.allowed-origins}") String configuredOrigins) {
		List<String> allowedOrigins = List.of(configuredOrigins.split(",")).stream()
				.map(String::trim)
				.filter(origin -> !origin.isEmpty())
				.toList();
		if (allowedOrigins.isEmpty() || allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
			throw new IllegalStateException("APP_CORS_ALLOWED_ORIGINS must contain exact origins and cannot use a wildcard.");
		}

		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}

	private static void writeError(
			ObjectMapper objectMapper,
			HttpServletRequest request,
			HttpServletResponse response,
			HttpStatus status,
			String code,
			String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		objectMapper.writeValue(response.getOutputStream(),
				new ApiErrorResponse(code, message, Instant.now(), request.getRequestURI()));
	}
}
