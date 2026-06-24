package com.boot.drrr.web.dto.room;

import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.service.room.LeaveRoomResult;

public record LeaveRoomResponse(
        boolean left,
        boolean ownerTransferred,
        String newOwnerUserId,
        RoomStatus roomStatus
) {
    public static LeaveRoomResponse from(LeaveRoomResult result) {
        return new LeaveRoomResponse(
                result.left(),
                result.ownerTransferred(),
                result.newOwnerUserId(),
                result.roomStatus()
        );
    }
}
