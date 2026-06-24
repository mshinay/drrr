package com.boot.drrr.ws;

public record RoomWebSocketSessionContext(
        String sessionId,
        String roomId,
        String userId
) {
}
