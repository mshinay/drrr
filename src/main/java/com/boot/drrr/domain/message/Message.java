package com.boot.drrr.domain.message;

import com.boot.drrr.domain.event.RoomEventType;
import java.util.List;

public record Message(
        String messageId,
        String roomId,
        MessageType type,
        String senderUserId,
        String senderNickname,
        String targetUserId,
        String targetNickname,
        String content,
        List<String> visibleTo,
        String sourceEventId,
        RoomEventType sourceEventType,
        long sentAt
) {
}
