package com.boot.drrr.service.governance;

import com.boot.drrr.domain.room.RoomStatus;

public record KickMemberResult(
        boolean kicked,
        String targetUserId,
        RoomStatus roomStatus,
        boolean ownerTransferred,
        String newOwnerUserId
) {
}
