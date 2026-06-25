package com.boot.drrr.ws;

public record RoomRemovedPayload(
        String roomId,
        String reason
) {
}
