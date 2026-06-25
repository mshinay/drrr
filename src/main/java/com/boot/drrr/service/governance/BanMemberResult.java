package com.boot.drrr.service.governance;

public record BanMemberResult(
        boolean banned,
        String targetUserId,
        boolean kicked
) {
}
