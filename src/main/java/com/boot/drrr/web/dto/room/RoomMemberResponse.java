package com.boot.drrr.web.dto.room;

import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.RoomMember;

public record RoomMemberResponse(
        String roomId,
        String userId,
        String nickname,
        MemberStatus memberStatus,
        long joinedAt,
        Long lastActiveAt,
        boolean isOwner
) {
    public static RoomMemberResponse from(RoomMember member) {
        return new RoomMemberResponse(
                member.roomId(),
                member.userId(),
                member.nickname(),
                member.memberStatus(),
                member.joinedAt(),
                member.lastActiveAt(),
                member.isOwner()
        );
    }
}
