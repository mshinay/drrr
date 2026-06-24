package com.boot.drrr.service.room;

import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;

public record CreateRoomView(
        Room room,
        RoomMember member
) {
}
