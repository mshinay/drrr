package com.boot.drrr.web.dto.room;

import com.boot.drrr.service.room.CreateRoomCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateRoomRequest(
        @NotBlank String userId,
        @NotBlank String name,
        String description,
        String password,
        @Min(1) int maxMembers,
        boolean userListVisible,
        @Valid @NotNull HistoryStrategyPayload historyStrategy,
        boolean allowOwnerConfigChange
) {
    public CreateRoomCommand toCommand() {
        return new CreateRoomCommand(
                userId,
                name,
                description,
                password,
                maxMembers,
                userListVisible,
                historyStrategy.toDomain(),
                allowOwnerConfigChange
        );
    }
}
