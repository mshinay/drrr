package com.boot.drrr.web.dto.governance;

import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.service.governance.KickMemberResult;

public record KickMemberResponse(
        boolean kicked,
        String targetUserId,
        RoomStatus roomStatus,
        boolean ownerTransferred,
        String newOwnerUserId
) {
    public static KickMemberResponse from(KickMemberResult result) {
        return new KickMemberResponse(
                result.kicked(),
                result.targetUserId(),
                result.roomStatus(),
                result.ownerTransferred(),
                result.newOwnerUserId()
        );
    }
}
