package com.boot.drrr.domain.event;

public enum RoomEventType {
    USER_JOIN,
    USER_LEAVE,
    USER_RECONNECTING,
    USER_RECONNECTED,
    OWNER_TRANSFER,
    USER_MUTED,
    USER_KICKED,
    USER_BANNED,
    ROOM_EMPTY,
    ROOM_EXPIRED
}
