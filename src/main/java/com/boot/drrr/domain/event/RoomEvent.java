package com.boot.drrr.domain.event;

import tools.jackson.databind.JsonNode;

public record RoomEvent(
        String eventId,
        String roomId,
        RoomEventType type,
        String operatorUserId,
        String targetUserId,
        JsonNode payload,
        long occurredAt
) {
}
