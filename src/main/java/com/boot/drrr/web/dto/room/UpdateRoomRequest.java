package com.boot.drrr.web.dto.room;

import com.boot.drrr.service.room.UpdateRoomCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateRoomRequest(
        @NotBlank String operatorUserId,
        @NotBlank String name,
        String description,
        boolean userListVisible,
        @Valid @NotNull HistoryStrategyPayload historyStrategy,
        boolean allowOwnerConfigChange
) {
    public UpdateRoomCommand toCommand() {
        return new UpdateRoomCommand(
                operatorUserId,
                name,
                description,
                userListVisible,
                historyStrategy.toDomain(),
                allowOwnerConfigChange
        );
    }
}
