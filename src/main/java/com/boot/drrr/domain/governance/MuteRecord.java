package com.boot.drrr.domain.governance;

public record MuteRecord(
        String roomId,
        String userId,
        String mutedBy,
        long startAt,
        long endAt,
        String reason
) {
}
