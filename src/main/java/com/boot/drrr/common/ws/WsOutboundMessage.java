package com.boot.drrr.common.ws;

public record WsOutboundMessage<T>(
        String type,
        String requestId,
        T payload
) {
}
