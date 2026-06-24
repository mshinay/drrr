package com.boot.drrr.service.user;

import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.user.UserSession;

public record RoomSessionContext(
        String roomId,
        String userId,
        UserSession userSession,
        Room room,
        RoomMember roomMember
) {
}
