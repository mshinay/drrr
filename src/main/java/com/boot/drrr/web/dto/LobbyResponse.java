package com.boot.drrr.web.dto;

import com.boot.drrr.service.lobby.LobbyView;
import java.util.List;

public record LobbyResponse(
        long activeUsersLast5Minutes,
        List<LobbyRoomCardResponse> rooms
) {
    public static LobbyResponse from(LobbyView lobbyView) {
        return new LobbyResponse(
                lobbyView.activeUsersLast5Minutes(),
                lobbyView.rooms().stream()
                        .map(LobbyRoomCardResponse::from)
                        .toList()
        );
    }

    public record LobbyRoomCardResponse(
            String roomId,
            String name,
            String description,
            long currentMembers,
            int maxMembers,
            long lastActiveAt,
            long createdAt
    ) {
        static LobbyRoomCardResponse from(LobbyView.LobbyRoomSummary summary) {
            return new LobbyRoomCardResponse(
                    summary.roomId(),
                    summary.name(),
                    summary.description(),
                    summary.currentMembers(),
                    summary.maxMembers(),
                    summary.lastActiveAt(),
                    summary.createdAt()
            );
        }
    }
}
