package com.splitsmart.dto;

import java.time.Instant;

public record InvitationResponse(Long id, Long groupId, String groupName, Instant expiresAt) {}
