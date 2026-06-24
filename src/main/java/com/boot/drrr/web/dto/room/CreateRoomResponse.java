package com.boot.drrr.web.dto.room;

import com.boot.drrr.service.room.CreateRoomView;

public record CreateRoomResponse(
        RoomResponse room,
        RoomMemberResponse member
) {
    public static CreateRoomResponse from(CreateRoomView view) {
        return new CreateRoomResponse(
                RoomResponse.from(view.room()),
                RoomMemberResponse.from(view.member())
        );
    }
}
