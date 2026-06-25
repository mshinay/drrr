package com.boot.drrr.ws.message;

public record SendDirectMessagePayload(
        String roomId,
        String senderUserId,
        String targetUserId,
        String content
) {
}
