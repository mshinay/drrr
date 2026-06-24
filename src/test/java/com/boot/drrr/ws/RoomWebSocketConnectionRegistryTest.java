package com.boot.drrr.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class RoomWebSocketConnectionRegistryTest {

    @Test
    void registerAndUnregisterTracksSessionsByRoomUserAndSessionId() {
        RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();
        TestWebSocketSession first = new TestWebSocketSession("s-1", URI.create("ws://localhost/ws/rooms/r-1?userId=u-1"));
        TestWebSocketSession second = new TestWebSocketSession("s-2", URI.create("ws://localhost/ws/rooms/r-1?userId=u-2"));

        registry.register("r-1", "u-1", first);
        registry.register("r-1", "u-2", second);

        assertThat(registry.findBySessionId("s-1")).hasValue(new RoomWebSocketSessionContext("s-1", "r-1", "u-1"));
        assertThat(registry.findRoomUserSession("r-1", "u-2")).contains(second);
        assertThat(registry.listRoomSessions("r-1")).containsExactlyInAnyOrder(first, second);

        assertThat(registry.unregister("s-1")).hasValue(new RoomWebSocketSessionContext("s-1", "r-1", "u-1"));
        assertThat(registry.findBySessionId("s-1")).isEmpty();
        assertThat(registry.findRoomUserSession("r-1", "u-1")).isEmpty();
        assertThat(registry.listRoomSessions("r-1")).containsExactly(second);
    }

    @Test
    void registerReplacesSameUserSessionMapping() {
        RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();
        TestWebSocketSession oldSession = new TestWebSocketSession("s-old", URI.create("ws://localhost/ws/rooms/r-1?userId=u-1"));
        TestWebSocketSession newSession = new TestWebSocketSession("s-new", URI.create("ws://localhost/ws/rooms/r-1?userId=u-1"));

        registry.register("r-1", "u-1", oldSession);
        registry.register("r-1", "u-1", newSession);

        assertThat(registry.findBySessionId("s-old")).isEmpty();
        assertThat(registry.findRoomUserSession("r-1", "u-1")).contains(newSession);
        assertThat(registry.listRoomSessions("r-1")).containsExactly(newSession);
    }
}
