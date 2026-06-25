package com.boot.drrr.ws.event;

import com.boot.drrr.domain.event.RoomEvent;

public record RoomEventOccurredPayload(
        RoomEvent event
) {
}
