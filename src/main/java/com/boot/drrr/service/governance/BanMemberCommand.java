package com.boot.drrr.service.governance;

public record BanMemberCommand(
        String operatorUserId,
        String reason
) {
}
