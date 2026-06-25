package com.boot.drrr.ws.message;

public record SendPublicMessagePayload(
        String roomId,
        String senderUserId,
        String content
) {
}
