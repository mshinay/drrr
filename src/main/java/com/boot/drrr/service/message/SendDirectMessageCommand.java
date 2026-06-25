package com.boot.drrr.service.message;

public record SendDirectMessageCommand(
        String roomId,
        String senderUserId,
        String targetUserId,
        String content
) {
}
