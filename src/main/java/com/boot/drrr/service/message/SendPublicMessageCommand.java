package com.boot.drrr.service.message;

public record SendPublicMessageCommand(
        String roomId,
        String senderUserId,
        String content
) {
}
