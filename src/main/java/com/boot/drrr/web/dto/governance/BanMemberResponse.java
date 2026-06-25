package com.boot.drrr.web.dto.governance;

import com.boot.drrr.service.governance.BanMemberResult;

public record BanMemberResponse(
        boolean banned,
        String targetUserId,
        boolean kicked
) {
    public static BanMemberResponse from(BanMemberResult result) {
        return new BanMemberResponse(result.banned(), result.targetUserId(), result.kicked());
    }
}
