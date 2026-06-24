package com.boot.drrr.web.dto.room;

import com.boot.drrr.service.room.UpdateRoomView;

public record UpdateRoomResponse(
        RoomResponse room
) {
    public static UpdateRoomResponse from(UpdateRoomView view) {
        return new UpdateRoomResponse(RoomResponse.from(view.room()));
    }
}
