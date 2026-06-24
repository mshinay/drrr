package com.boot.drrr.service.room;

import com.boot.drrr.domain.room.RoomStatus;

public record LeaveRoomResult(
        boolean left,
        boolean ownerTransferred,
        String newOwnerUserId,
        RoomStatus roomStatus
) {
}
