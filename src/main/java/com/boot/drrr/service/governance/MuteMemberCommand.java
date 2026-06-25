package com.boot.drrr.service.governance;

public record MuteMemberCommand(
        String operatorUserId,
        int durationMinutes,
        String reason
) {
}
