package com.splitsmart.controller;

import java.util.List;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestParam;
import com.splitsmart.dto.HistoryPage;
import io.swagger.v3.oas.annotations.headers.Header;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.splitsmart.dto.ApiErrorResponse;
import com.splitsmart.dto.CreateSettlementRequest;
import com.splitsmart.dto.SettlementResponse;
import com.splitsmart.service.SettlementService;

@RestController
@RequestMapping("/api/groups/{groupId}/settlements")
@SecurityRequirement(name = "bearerAuth")
public class SettlementController {

	private final SettlementService settlementService;

	public SettlementController(SettlementService settlementService) {
		this.settlementService = settlementService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Record a settlement")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Settlement recorded"),
			@ApiResponse(responseCode = "400", description = "Invalid group ID or settlement",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "Sender does not match the authenticated member",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "Request ID already used",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public SettlementResponse createSettlement(
			@PathVariable Long groupId,
			@Valid @RequestBody CreateSettlementRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return settlementService.createSettlement(groupId, jwt.getSubject(), request);
	}

	@GetMapping
	@Operation(summary = "List settlements in a group")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Group settlements",
                    headers = @Header(name = "X-Next-Before", description = "Exclusive cursor for the next page. Absent at the end.", schema = @Schema(type = "string")),
					content = @Content(array = @ArraySchema(schema = @Schema(implementation = SettlementResponse.class)))),
			@ApiResponse(responseCode = "400", description = "Invalid group ID or history parameters",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
    public ResponseEntity<List<SettlementResponse>> listSettlements(
            @PathVariable Long groupId,
            @Parameter(description = "Exclusive descending record-ID cursor.", schema = @Schema(minimum = "1"))
            @RequestParam(required = false) Long before,
            @Parameter(description = "Maximum records per page.", schema = @Schema(minimum = "1", maximum = "100", defaultValue = "25"))
            @RequestParam(defaultValue = "25") int limit,
            @Parameter(description = "Find a group repayment by its UUID, independently of the current page. Cannot combine with before.")
            @RequestParam(required = false) String requestId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters,
            @AuthenticationPrincipal Jwt jwt) {
        HistoryPage.validateParameters(parameters, Set.of("limit", "before", "requestId"));
        return settlementService.listSettlements(groupId, jwt.getSubject(), before, limit, requestId).response();
    }
}
