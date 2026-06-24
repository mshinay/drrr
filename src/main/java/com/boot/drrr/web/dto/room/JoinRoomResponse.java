package com.boot.drrr.web.dto.room;

import com.boot.drrr.service.room.JoinRoomView;
import java.util.List;

public record JoinRoomResponse(
        RoomResponse room,
        RoomMemberResponse member,
        List<RoomMemberResponse> members,
        List<Object> historyMessages
) {
    public static JoinRoomResponse from(JoinRoomView view) {
        return new JoinRoomResponse(
                RoomResponse.from(view.room()),
                RoomMemberResponse.from(view.member()),
                view.members().stream().map(RoomMemberResponse::from).toList(),
                List.of()
        );
    }
}
