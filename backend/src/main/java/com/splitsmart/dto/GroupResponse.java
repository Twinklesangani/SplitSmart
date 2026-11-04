package com.splitsmart.dto;

public record GroupResponse(Long id, String name, Long createdByUserId, boolean archived) {
    public GroupResponse(Long id, String name, Long createdByUserId) { this(id, name, createdByUserId, false); }
}
