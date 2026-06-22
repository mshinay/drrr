package com.boot.drrr.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid request"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "user not found"),
    USER_ALREADY_IN_ROOM(HttpStatus.CONFLICT, "user already in room"),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "room not found"),
    ROOM_EXPIRED(HttpStatus.GONE, "room expired"),
    ROOM_FULL(HttpStatus.CONFLICT, "room is full"),
    PASSWORD_REQUIRED(HttpStatus.BAD_REQUEST, "password is required"),
    PASSWORD_INVALID(HttpStatus.FORBIDDEN, "password is invalid"),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "nickname duplicated"),
    USER_BANNED(HttpStatus.FORBIDDEN, "user is banned"),
    USER_MUTED(HttpStatus.FORBIDDEN, "user is muted"),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "member not found"),
    TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "target not found"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
    CONFIG_LOCKED(HttpStatus.CONFLICT, "room config is locked"),
    RECONNECT_EXPIRED(HttpStatus.GONE, "reconnect window expired"),
    ROOM_CONTEXT_MISMATCH(HttpStatus.CONFLICT, "room context mismatch"),
    EXPORT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "export failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
