package com.boot.drrr.web.dto.room;

import com.boot.drrr.service.room.JoinRoomCommand;
import jakarta.validation.constraints.NotBlank;

public record JoinRoomRequest(
        @NotBlank String userId,
        String password
) {
    public JoinRoomCommand toCommand() {
        return new JoinRoomCommand(userId, password);
    }
}
