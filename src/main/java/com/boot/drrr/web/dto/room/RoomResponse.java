package com.boot.drrr.web.dto.room;

import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomStatus;

public record RoomResponse(
        String roomId,
        String name,
        String description,
        int maxMembers,
        String ownerUserId,
        String initialOwnerUserId,
        RoomStatus status,
        boolean userListVisible,
        HistoryStrategy historyStrategy,
        boolean allowOwnerConfigChange,
        long createdAt,
        long lastActiveAt,
        Long emptySince
) {
    public static RoomResponse from(Room room) {
        return new RoomResponse(
                room.roomId(),
                room.name(),
                room.description(),
                room.maxMembers(),
                room.ownerUserId(),
                room.initialOwnerUserId(),
                room.status(),
                room.userListVisible(),
                room.historyStrategy(),
                room.allowOwnerConfigChange(),
                room.createdAt(),
                room.lastActiveAt(),
                room.emptySince()
        );
    }
}
