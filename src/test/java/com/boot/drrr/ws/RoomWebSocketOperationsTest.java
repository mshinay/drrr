package com.boot.drrr.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.common.ws.WsOutboundMessage;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RoomWebSocketOperationsTest {

    @Test
    void broadcastAndDirectPushSendOnlyTargetedSessions() {
        RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();
        RoomWebSocketOperations operations = new RoomWebSocketOperations(registry, new JsonCodec(new ObjectMapper()));
        TestWebSocketSession alice = new TestWebSocketSession("s-1", URI.create("ws://localhost/ws/rooms/r-1?userId=u-1"));
        TestWebSocketSession bob = new TestWebSocketSession("s-2", URI.create("ws://localhost/ws/rooms/r-1?userId=u-2"));
        TestWebSocketSession otherRoom = new TestWebSocketSession("s-3", URI.create("ws://localhost/ws/rooms/r-2?userId=u-3"));

        registry.register("r-1", "u-1", alice);
        registry.register("r-1", "u-2", bob);
        registry.register("r-2", "u-3", otherRoom);

        operations.broadcastToRoom("r-1", "ROOM_STATE_SYNC", "req-broadcast", Map.of("roomId", "r-1"));
        operations.pushToUser("r-1", "u-2", new WsOutboundMessage<>("MESSAGE_CREATED", "req-direct", Map.of("content", "hi")));
        operations.pushToUsers("r-1", List.of("u-1", "u-2", "u-2"), new WsOutboundMessage<>("ROOM_EVENT_OCCURRED", null, Map.of("type", "USER_JOIN")));

        assertThat(alice.textMessages()).hasSize(2);
        assertThat(alice.textMessages().get(0).getPayload()).contains("ROOM_STATE_SYNC").contains("req-broadcast");
        assertThat(alice.textMessages().get(1).getPayload()).contains("ROOM_EVENT_OCCURRED");
        assertThat(bob.textMessages()).hasSize(3);
        assertThat(bob.textMessages().get(0).getPayload()).contains("ROOM_STATE_SYNC");
        assertThat(bob.textMessages().get(1).getPayload()).contains("MESSAGE_CREATED").contains("req-direct");
        assertThat(bob.textMessages().get(2).getPayload()).contains("ROOM_EVENT_OCCURRED");
        assertThat(otherRoom.textMessages()).isEmpty();
    }
}
