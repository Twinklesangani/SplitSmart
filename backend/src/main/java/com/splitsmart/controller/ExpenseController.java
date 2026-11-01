package com.splitsmart.controller;

import java.util.List;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import com.splitsmart.dto.ApiErrorResponse;
import com.splitsmart.dto.CreateExpenseRequest;
import com.splitsmart.dto.ExpenseResponse;
import com.splitsmart.service.ExpenseService;

@RestController
@RequestMapping("/api/groups/{groupId}/expenses")
@SecurityRequirement(name = "bearerAuth")
public class ExpenseController {

	private final ExpenseService expenseService;

	public ExpenseController(ExpenseService expenseService) {
		this.expenseService = expenseService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a group expense")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Expense created"),
			@ApiResponse(responseCode = "400", description = "Invalid group ID, expense, or split",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public ExpenseResponse createExpense(
			@PathVariable Long groupId,
			@Valid @RequestBody CreateExpenseRequest request,
			@Parameter(required = true, description = "Quoted UUID identifying one creation operation. Reuse for retries.")
			@RequestHeader(value = "Idempotency-Key", required = false) String operationKey,
			@AuthenticationPrincipal Jwt jwt) {
		return expenseService.createExpense(groupId, jwt.getSubject(), request, operationKey);
	}

	@GetMapping
	@Operation(summary = "List expenses in a group")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Group expenses",
					content = @Content(array = @ArraySchema(schema = @Schema(implementation = ExpenseResponse.class)))),
			@ApiResponse(responseCode = "400", description = "Invalid group ID",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public List<ExpenseResponse> listExpenses(
			@PathVariable Long groupId,
			@AuthenticationPrincipal Jwt jwt) {
		return expenseService.listExpenses(groupId, jwt.getSubject());
	}

	@GetMapping("/{expenseId}")
	@Operation(summary = "Get a group expense")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Expense"),
			@ApiResponse(responseCode = "400", description = "Invalid group or expense ID",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group or expense not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public ExpenseResponse getExpense(
			@PathVariable Long groupId,
			@PathVariable Long expenseId,
			@AuthenticationPrincipal Jwt jwt) {
		return expenseService.getExpense(groupId, expenseId, jwt.getSubject());
	}

	@PutMapping("/{expenseId}")
	@Operation(summary = "Replace a group expense and its splits")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Expense updated"),
			@ApiResponse(responseCode = "400", description = "Invalid group or expense ID, expense, or split",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "Only the expense creator may edit it",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group or expense not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public ExpenseResponse updateExpense(
			@PathVariable Long groupId,
			@PathVariable Long expenseId,
			@Valid @RequestBody CreateExpenseRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return expenseService.updateExpense(groupId, expenseId, jwt.getSubject(), request);
	}

	@DeleteMapping("/{expenseId}")
	@Operation(summary = "Delete a group expense")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Expense deleted", content = @Content),
			@ApiResponse(responseCode = "400", description = "Invalid group or expense ID",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "Only the expense creator may delete it",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group or expense not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public ResponseEntity<Void> deleteExpense(
			@PathVariable Long groupId,
			@PathVariable Long expenseId,
			@AuthenticationPrincipal Jwt jwt) {
		expenseService.deleteExpense(groupId, expenseId, jwt.getSubject());
		return ResponseEntity.noContent().build();
	}
}
