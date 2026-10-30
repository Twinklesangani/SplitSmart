package com.splitsmart.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.splitsmart.dto.ApiErrorResponse;
import com.splitsmart.dto.GroupBalanceResponse;
import com.splitsmart.service.ExpenseService;

@RestController
@RequestMapping("/api/groups/{groupId}/balances")
@SecurityRequirement(name = "bearerAuth")
public class BalanceController {

	private final ExpenseService expenseService;

	public BalanceController(ExpenseService expenseService) {
		this.expenseService = expenseService;
	}

	@GetMapping
	@Operation(summary = "Get group expense balances")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Group balances"),
			@ApiResponse(responseCode = "400", description = "Invalid group ID",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public GroupBalanceResponse getGroupBalances(
			@PathVariable Long groupId,
			@AuthenticationPrincipal Jwt jwt) {
		return expenseService.getGroupBalances(groupId, jwt.getSubject());
	}
}
