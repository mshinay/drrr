package com.boot.drrr.service.room;

import com.boot.drrr.domain.room.HistoryStrategy;

public record CreateRoomCommand(
        String userId,
        String name,
        String description,
        String password,
        int maxMembers,
        boolean userListVisible,
        HistoryStrategy historyStrategy,
        boolean allowOwnerConfigChange
) {
}
