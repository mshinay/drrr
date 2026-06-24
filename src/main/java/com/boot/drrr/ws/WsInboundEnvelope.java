package com.boot.drrr.ws;

import tools.jackson.databind.JsonNode;

public record WsInboundEnvelope(
        String type,
        String requestId,
        JsonNode payload
) {
}
