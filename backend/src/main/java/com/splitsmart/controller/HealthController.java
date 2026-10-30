package com.splitsmart.controller;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	@GetMapping("/api/health")
	@Operation(summary = "Check API health")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "API is available")
	})
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}
}
