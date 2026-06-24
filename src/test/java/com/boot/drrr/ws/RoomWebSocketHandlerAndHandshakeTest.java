package com.boot.drrr.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;
import com.boot.drrr.service.user.RoomSessionContext;
import com.boot.drrr.service.user.UserSessionService;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.ObjectMapper;

class RoomWebSocketHandlerAndHandshakeTest {

    @Test
    void handshakeStoresValidatedRoomContext() {
        StubUserSessionService userSessionService = new StubUserSessionService();
        userSessionService.validationContext = sampleContext();
        RoomWebSocketHandshakeInterceptor interceptor = new RoomWebSocketHandshakeInterceptor(userSessionService);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/rooms/r-1");
        servletRequest.setQueryString("userId=u-1");
        servletRequest.setRequestURI("/ws/rooms/r-1");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse),
                null,
                attributes
        );

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(RoomWebSocketAttributes.ROOM_ID, "r-1");
        assertThat(attributes).containsEntry(RoomWebSocketAttributes.USER_ID, "u-1");
        assertThat(userSessionService.validatedUserId).isEqualTo("u-1");
        assertThat(userSessionService.validatedRoomId).isEqualTo("r-1");
    }

    @Test
    void handshakeRejectsInvalidRoomContext() {
        StubUserSessionService userSessionService = new StubUserSessionService();
        userSessionService.validationFailure = new BusinessException(ErrorCode.ROOM_CONTEXT_MISMATCH);
        RoomWebSocketHandshakeInterceptor interceptor = new RoomWebSocketHandshakeInterceptor(userSessionService);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/rooms/r-9");
        servletRequest.setQueryString("userId=u-9");
        servletRequest.setRequestURI("/ws/rooms/r-9");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse),
                null,
                new HashMap<>()
        );

        assertThat(accepted).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void handlerRegistersConnectionSendsErrorEnvelopeAndMarksDisconnect() throws Exception {
        StubUserSessionService userSessionService = new StubUserSessionService();
        userSessionService.validationContext = sampleContext();
        RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();
        JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());
        RoomWebSocketOperations operations = new RoomWebSocketOperations(registry, jsonCodec);
        RoomWebSocketHandler handler = new RoomWebSocketHandler(userSessionService, registry, operations, jsonCodec);
        TestWebSocketSession session = new TestWebSocketSession("s-1", URI.create("ws://localhost/ws/rooms/r-1?userId=u-1"));
        session.getAttributes().put(RoomWebSocketAttributes.ROOM_ID, "r-1");
        session.getAttributes().put(RoomWebSocketAttributes.USER_ID, "u-1");

        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("{\"type\":\"SEND_PUBLIC_MESSAGE\",\"requestId\":\"req-1\",\"payload\":{\"content\":\"hello\"}}"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.findRoomUserSession("r-1", "u-1")).isEmpty();
        assertThat(userSessionService.connectedUserId).isEqualTo("u-1");
        assertThat(userSessionService.connectedRoomId).isEqualTo("r-1");
        assertThat(session.textMessages()).hasSize(1);
        assertThat(session.textMessages().get(0).getPayload()).contains("\"type\":\"ERROR\"");
        assertThat(session.textMessages().get(0).getPayload()).contains("unsupported websocket message type: SEND_PUBLIC_MESSAGE");
        assertThat(userSessionService.disconnectedUserId).isEqualTo("u-1");
        assertThat(userSessionService.disconnectedRoomId).isEqualTo("r-1");
    }

    private RoomSessionContext sampleContext() {
        Room room = new Room(
                "r-1",
                "Room",
                "",
                null,
                8,
                "u-1",
                "u-1",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.NONE, null),
                true,
                1L,
                2L,
                null
        );
        RoomMember member = new RoomMember("r-1", "u-1", "Alice", MemberStatus.ONLINE, 1L, 2L, true);
        UserSession userSession = new UserSession("u-1", "Alice", "r-1", UserStatus.ONLINE, false, null, null, 1L, 2L);
        return new RoomSessionContext("r-1", "u-1", userSession, room, member);
    }

    private static final class StubUserSessionService extends UserSessionService {
        private RoomSessionContext validationContext;
        private BusinessException validationFailure;
        private String validatedUserId;
        private String validatedRoomId;
        private String connectedUserId;
        private String connectedRoomId;
        private String disconnectedUserId;
        private String disconnectedRoomId;

        private StubUserSessionService() {
            super(null, null, null, null, null, null, new com.boot.drrr.config.DrrrProperties());
        }

        @Override
        public RoomSessionContext validateRoomConnection(String userId, String roomId) {
            this.validatedUserId = userId;
            this.validatedRoomId = roomId;
            if (validationFailure != null) {
                throw validationFailure;
            }
            return validationContext;
        }

        @Override
        public RoomSessionContext markRoomConnected(String userId, String roomId) {
            this.connectedUserId = userId;
            this.connectedRoomId = roomId;
            return validationContext;
        }

        @Override
        public void markRoomDisconnected(String userId, String roomId) {
            this.disconnectedUserId = userId;
            this.disconnectedRoomId = roomId;
        }
    }
}
