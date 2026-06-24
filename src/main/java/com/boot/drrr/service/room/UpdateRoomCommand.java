package com.boot.drrr.service.room;

import com.boot.drrr.domain.room.HistoryStrategy;

public record UpdateRoomCommand(
        String operatorUserId,
        String name,
        String description,
        boolean userListVisible,
        HistoryStrategy historyStrategy,
        boolean allowOwnerConfigChange
) {
}
