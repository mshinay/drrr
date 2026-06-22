package com.boot.drrr.domain.room;

public record RoomMember(
        String roomId,
        String userId,
        String nickname,
        MemberStatus memberStatus,
        long joinedAt,
        Long lastActiveAt,
        boolean isOwner
) {
}
