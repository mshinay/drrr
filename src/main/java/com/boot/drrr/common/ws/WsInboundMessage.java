package com.boot.drrr.common.ws;

public record WsInboundMessage<T>(
        String type,
        String requestId,
        T payload
) {
}
