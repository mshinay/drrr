package com.boot.drrr.service.room;

import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import java.util.List;

public record JoinRoomView(
        Room room,
        RoomMember member,
        List<RoomMember> members
) {
}
