package com.splitsmart.service;

/** No user-supplied text, credentials or token values enter activity payloads. */
public record ActivityEvent(String type, Long groupId, Long actorId, Long resourceId, String key) {}
