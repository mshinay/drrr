package com.boot.drrr.domain.room;

public record Room(
        String roomId,
        String name,
        String description,
        String passwordHash,
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
}
