package com.boot.drrr.service.lobby;

public enum LobbySort {
    LAST_ACTIVE,
    MEMBER_COUNT,
    SURVIVAL_TIME;

    public static LobbySort from(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return LAST_ACTIVE;
        }
        try {
            return LobbySort.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return LAST_ACTIVE;
        }
    }
}
