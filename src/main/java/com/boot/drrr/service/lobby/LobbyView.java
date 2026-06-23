package com.boot.drrr.service.lobby;

import java.util.List;

public record LobbyView(
        long activeUsersLast5Minutes,
        List<LobbyRoomSummary> rooms
) {
    public record LobbyRoomSummary(
            String roomId,
            String name,
            String description,
            long currentMembers,
            int maxMembers,
            long lastActiveAt,
            long createdAt
    ) {
    }
}
