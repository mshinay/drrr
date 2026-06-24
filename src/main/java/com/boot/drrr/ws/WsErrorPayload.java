package com.boot.drrr.ws;

public record WsErrorPayload(
        String code,
        String message
) {
}
