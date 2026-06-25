package com.boot.drrr.ws.message;

import com.boot.drrr.domain.message.Message;

public record MessageCreatedPayload(
        Message message
) {
}
