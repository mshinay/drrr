package com.boot.drrr.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.domain.message.Message;
import com.boot.drrr.domain.message.MessageType;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;
import com.boot.drrr.service.message.MessageService;
import com.boot.drrr.service.message.SendDirectMessageCommand;
import com.boot.drrr.service.message.SendPublicMessageCommand;
import com.boot.drrr.service.user.RoomSessionContext;
import com.boot.drrr.service.user.UserSessionService;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
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
        userSessionService.validationContext = createContext("u-1", "Alice");
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
    void handlerBroadcastsPublicMessageToAllOnlineRoomMembers() throws Exception {
        StubUserSessionService userSessionService = new StubUserSessionService();
        StubMessageService messageService = new StubMessageService();
        RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();
        JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());
        RoomWebSocketOperations operations = new RoomWebSocketOperations(registry, jsonCodec);
        RoomWebSocketHandler handler = new RoomWebSocketHandler(userSessionService, messageService, registry, operations, jsonCodec);

        TestWebSocketSession alice = session("s-1", "u-1");
        TestWebSocketSession bob = session("s-2", "u-2");
        TestWebSocketSession claire = session("s-3", "u-3");
        handler.afterConnectionEstablished(alice);
        handler.afterConnectionEstablished(bob);
        handler.afterConnectionEstablished(claire);

        handler.handleMessage(alice, new TextMessage("{" +
                "\"type\":\"SEND_PUBLIC_MESSAGE\"," +
                "\"requestId\":\"req-public\"," +
                "\"payload\":{\"roomId\":\"r-1\",\"senderUserId\":\"u-1\",\"content\":\"hello\"}}"));

        assertThat(messageService.publicCommands).containsExactly(new SendPublicMessageCommand("r-1", "u-1", "hello"));
        assertThat(alice.textMessages()).hasSize(1);
        assertThat(bob.textMessages()).hasSize(1);
        assertThat(claire.textMessages()).hasSize(1);
        assertThat(alice.textMessages().get(0).getPayload()).contains("MESSAGE_CREATED").contains("req-public").contains("hello everyone");
        assertThat(bob.textMessages().get(0).getPayload()).contains("hello everyone");
        assertThat(claire.textMessages().get(0).getPayload()).contains("hello everyone");
    }

    @Test
    void handlerPushesDirectMessageOnlyToSenderAndTarget() throws Exception {
        StubUserSessionService userSessionService = new StubUserSessionService();
        StubMessageService messageService = new StubMessageService();
        RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();
        JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());
        RoomWebSocketOperations operations = new RoomWebSocketOperations(registry, jsonCodec);
        RoomWebSocketHandler handler = new RoomWebSocketHandler(userSessionService, messageService, registry, operations, jsonCodec);

        TestWebSocketSession alice = session("s-1", "u-1");
        TestWebSocketSession bob = session("s-2", "u-2");
        TestWebSocketSession claire = session("s-3", "u-3");
        handler.afterConnectionEstablished(alice);
        handler.afterConnectionEstablished(bob);
        handler.afterConnectionEstablished(claire);

        handler.handleMessage(alice, new TextMessage("{" +
                "\"type\":\"SEND_DIRECT_MESSAGE\"," +
                "\"requestId\":\"req-direct\"," +
                "\"payload\":{\"roomId\":\"r-1\",\"senderUserId\":\"u-1\",\"targetUserId\":\"u-2\",\"content\":\"psst\"}}"));

        assertThat(messageService.directCommands).containsExactly(new SendDirectMessageCommand("r-1", "u-1", "u-2", "psst"));
        assertThat(alice.textMessages()).hasSize(1);
        assertThat(bob.textMessages()).hasSize(1);
        assertThat(claire.textMessages()).isEmpty();
        assertThat(alice.textMessages().get(0).getPayload()).contains("MESSAGE_CREATED").contains("req-direct").contains("secret hi");
        assertThat(bob.textMessages().get(0).getPayload()).contains("secret hi");
    }

    @Test
    void handlerStillReturnsErrorEnvelopeForUnsupportedMessageType() throws Exception {
        StubUserSessionService userSessionService = new StubUserSessionService();
        StubMessageService messageService = new StubMessageService();
        RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();
        JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());
        RoomWebSocketOperations operations = new RoomWebSocketOperations(registry, jsonCodec);
        RoomWebSocketHandler handler = new RoomWebSocketHandler(userSessionService, messageService, registry, operations, jsonCodec);
        TestWebSocketSession session = session("s-1", "u-1");

        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("{\"type\":\"UNKNOWN\",\"requestId\":\"req-1\",\"payload\":{}}"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.findRoomUserSession("r-1", "u-1")).isEmpty();
        assertThat(session.textMessages()).hasSize(1);
        assertThat(session.textMessages().get(0).getPayload()).contains("\"type\":\"ERROR\"");
        assertThat(session.textMessages().get(0).getPayload()).contains("unsupported websocket message type: UNKNOWN");
        assertThat(userSessionService.disconnectedUserId).isEqualTo("u-1");
        assertThat(userSessionService.disconnectedRoomId).isEqualTo("r-1");
    }

    private static TestWebSocketSession session(String sessionId, String userId) {
        TestWebSocketSession session = new TestWebSocketSession(sessionId, URI.create("ws://localhost/ws/rooms/r-1?userId=" + userId));
        session.getAttributes().put(RoomWebSocketAttributes.ROOM_ID, "r-1");
        session.getAttributes().put(RoomWebSocketAttributes.USER_ID, userId);
        return session;
    }

    private static RoomSessionContext createContext(String userId, String nickname) {
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
        RoomMember member = new RoomMember("r-1", userId, nickname, MemberStatus.ONLINE, 1L, 2L, "u-1".equals(userId));
        UserSession userSession = new UserSession(userId, nickname, "r-1", UserStatus.ONLINE, false, null, null, 1L, 2L);
        return new RoomSessionContext("r-1", userId, userSession, room, member);
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
            super(null, null, null, null, null, null, null, new com.boot.drrr.config.DrrrProperties());
        }

        @Override
        public RoomSessionContext validateRoomConnection(String userId, String roomId) {
            this.validatedUserId = userId;
            this.validatedRoomId = roomId;
            if (validationFailure != null) {
                throw validationFailure;
            }
            return validationContext != null ? validationContext : createContext(userId, "User-" + userId);
        }

        @Override
        public RoomSessionContext markRoomConnected(String userId, String roomId) {
            this.connectedUserId = userId;
            this.connectedRoomId = roomId;
            return createContext(userId, "User-" + userId);
        }

        @Override
        public void markRoomDisconnected(String userId, String roomId) {
            this.disconnectedUserId = userId;
            this.disconnectedRoomId = roomId;
        }
    }

    private static final class StubMessageService extends MessageService {
        private final List<SendPublicMessageCommand> publicCommands = new java.util.ArrayList<>();
        private final List<SendDirectMessageCommand> directCommands = new java.util.ArrayList<>();

        private StubMessageService() {
            super(null, null, null, null, null, null, null, new com.boot.drrr.common.lock.JvmRoomLock());
        }

        @Override
        public Message sendPublicMessage(SendPublicMessageCommand command) {
            publicCommands.add(command);
            return new Message(
                    "m-public",
                    command.roomId(),
                    MessageType.PUBLIC,
                    command.senderUserId(),
                    "Alice",
                    null,
                    null,
                    "hello everyone",
                    List.of(),
                    null,
                    null,
                    10L
            );
        }

        @Override
        public Message sendDirectMessage(SendDirectMessageCommand command) {
            directCommands.add(command);
            return new Message(
                    "m-direct",
                    command.roomId(),
                    MessageType.DIRECT,
                    command.senderUserId(),
                    "Alice",
                    command.targetUserId(),
                    "Bob",
                    "secret hi",
                    List.of(command.senderUserId(), command.targetUserId()),
                    null,
                    null,
                    20L
            );
        }
    }
}

