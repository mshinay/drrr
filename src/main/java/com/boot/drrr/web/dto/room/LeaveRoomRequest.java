package com.boot.drrr.web.dto.room;

import com.boot.drrr.service.room.LeaveRoomCommand;
import jakarta.validation.constraints.NotBlank;

public record LeaveRoomRequest(
        @NotBlank String userId
) {
    public LeaveRoomCommand toCommand() {
        return new LeaveRoomCommand(userId);
    }
}
