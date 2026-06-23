package com.boot.drrr.web.dto;

import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;

public record SessionResponse(
        String userId,
        String nickname,
        UserStatus status,
        String currentRoomId
) {
    public static SessionResponse from(UserSession userSession) {
        return new SessionResponse(
                userSession.userId(),
                userSession.nickname(),
                userSession.status(),
                userSession.currentRoomId()
        );
    }
}
