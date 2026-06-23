package com.boot.drrr.common.redis;

public final class RedisKeys {
    public static final String PREFIX = "drrr:";
    public static final String ROOM_ACTIVE = PREFIX + "room:active";
    public static final String ROOM_EMPTY = PREFIX + "room:empty";
    public static final String USER_RECONNECTING = PREFIX + "user:reconnecting";
    public static final String LOBBY_ACTIVE_USERS = PREFIX + "lobby:active-users";

    private RedisKeys() {
    }

    public static String user(String userId) {
        return PREFIX + "user:" + userId;
    }

    public static String room(String roomId) {
        return PREFIX + "room:" + roomId;
    }

    public static String roomMembers(String roomId) {
        return PREFIX + "room:members:" + roomId;
    }

    public static String roomMemberDetail(String roomId) {
        return PREFIX + "room:member-detail:" + roomId;
    }

    public static String roomMessages(String roomId) {
        return PREFIX + "room:messages:" + roomId;
    }

    public static String roomEvents(String roomId) {
        return PREFIX + "room:events:" + roomId;
    }

    public static String roomMute(String roomId) {
        return PREFIX + "room:mute:" + roomId;
    }

    public static String roomMuteDetail(String roomId, String userId) {
        return PREFIX + "room:mute:detail:" + roomId + ":" + userId;
    }

    public static String roomBan(String roomId) {
        return PREFIX + "room:ban:" + roomId;
    }

    public static String roomBanDetail(String roomId, String userId) {
        return PREFIX + "room:ban:detail:" + roomId + ":" + userId;
    }
}
