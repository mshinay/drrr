package com.boot.drrr.service.governance;

public record KickMemberCommand(
        String operatorUserId,
        String reason
) {
}
