package com.boot.drrr.domain.user;

public record UserSession(
        String userId,
        String nickname,
        String currentRoomId,
        UserStatus status,
        boolean connected,
        Long lastConnectedAt,
        Long lastDisconnectedAt,
        long createdAt,
        long updatedAt
) {
}
