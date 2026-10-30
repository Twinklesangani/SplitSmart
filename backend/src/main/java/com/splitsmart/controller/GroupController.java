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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.splitsmart.dto.AddGroupMemberRequest;
import com.splitsmart.dto.ApiErrorResponse;
import com.splitsmart.dto.CreateGroupRequest;
import com.splitsmart.dto.GroupMemberResponse;
import com.splitsmart.dto.GroupResponse;
import com.splitsmart.service.GroupService;

@RestController
@RequestMapping("/api/groups")
@SecurityRequirement(name = "bearerAuth")
public class GroupController {

	private final GroupService groupService;

	public GroupController(GroupService groupService) {
		this.groupService = groupService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a group")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Group created"),
			@ApiResponse(responseCode = "400", description = "Invalid group name",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public GroupResponse createGroup(
			@Valid @RequestBody CreateGroupRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return groupService.createGroup(jwt.getSubject(), request);
	}

	@GetMapping
	@Operation(summary = "List groups for the authenticated member")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Member groups",
					content = @Content(array = @ArraySchema(schema = @Schema(implementation = GroupResponse.class)))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public List<GroupResponse> listGroups(@AuthenticationPrincipal Jwt jwt) {
		return groupService.listGroups(jwt.getSubject());
	}

	@PostMapping("/{groupId}/members")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Add a member to a group")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Member added"),
			@ApiResponse(responseCode = "400", description = "Invalid group ID or email",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "Only the group creator may add members",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group or target user not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "User is already a member",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public GroupMemberResponse addMember(
			@PathVariable Long groupId,
			@Valid @RequestBody AddGroupMemberRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return groupService.addMember(groupId, jwt.getSubject(), request);
	}

	@GetMapping("/{groupId}/members")
	@Operation(summary = "List group members")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Group members",
					content = @Content(array = @ArraySchema(schema = @Schema(implementation = GroupMemberResponse.class)))),
			@ApiResponse(responseCode = "400", description = "Invalid group ID",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "401", description = "Authentication is required or invalid",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Group not found",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
			@ApiResponse(responseCode = "500", description = "Unexpected server error",
					content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	})
	public List<GroupMemberResponse> listMembers(
			@PathVariable Long groupId,
			@AuthenticationPrincipal Jwt jwt) {
		return groupService.listMembers(groupId, jwt.getSubject());
	}
}
