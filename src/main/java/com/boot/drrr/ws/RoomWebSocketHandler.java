package com.boot.drrr.ws;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.service.user.UserSessionService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {
    private final UserSessionService userSessionService;
    private final RoomWebSocketConnectionRegistry registry;
    private final RoomWebSocketOperations operations;
    private final JsonCodec jsonCodec;

    public RoomWebSocketHandler(
            UserSessionService userSessionService,
            RoomWebSocketConnectionRegistry registry,
            RoomWebSocketOperations operations,
            JsonCodec jsonCodec
    ) {
        this.userSessionService = userSessionService;
        this.registry = registry;
        this.operations = operations;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String roomId = requiredAttribute(session, RoomWebSocketAttributes.ROOM_ID);
        String userId = requiredAttribute(session, RoomWebSocketAttributes.USER_ID);
        userSessionService.markRoomConnected(userId, roomId);
        registry.register(roomId, userId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String requestId = null;
        try {
            WsInboundEnvelope inbound = jsonCodec.decode(message.getPayload(), WsInboundEnvelope.class);
            requestId = inbound.requestId();
            if (inbound.type() == null || inbound.type().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "type must not be blank");
            }
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "unsupported websocket message type: " + inbound.type()
            );
        } catch (BusinessException exception) {
            operations.pushError(session, requestId, exception);
        } catch (RuntimeException exception) {
            operations.pushInternalError(session, requestId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) {
            operations.pushInternalError(session, null);
        }
        super.handleTransportError(session, exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session.getId())
                .ifPresent(context -> userSessionService.markRoomDisconnected(context.userId(), context.roomId()));
    }

    private String requiredAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "missing websocket attribute: " + key);
    }
}
