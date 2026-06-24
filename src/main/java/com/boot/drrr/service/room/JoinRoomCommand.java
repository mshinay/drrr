package com.boot.drrr.service.room;

public record JoinRoomCommand(
        String userId,
        String password
) {
}
