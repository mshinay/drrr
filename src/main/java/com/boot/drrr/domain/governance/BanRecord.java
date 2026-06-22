package com.boot.drrr.domain.governance;

public record BanRecord(
        String roomId,
        String userId,
        String bannedBy,
        long bannedAt,
        String reason
) {
}
