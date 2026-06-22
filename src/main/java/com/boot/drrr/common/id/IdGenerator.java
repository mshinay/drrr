package com.boot.drrr.common.id;

public interface IdGenerator {
    String newUserId();

    String newRoomId();

    String newMessageId();

    String newEventId();
}
