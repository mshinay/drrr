package com.boot.drrr.ws;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.common.ws.WsOutboundMessage;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class RoomWebSocketOperations {
    private final RoomWebSocketConnectionRegistry registry;
    private final JsonCodec jsonCodec;

    public RoomWebSocketOperations(RoomWebSocketConnectionRegistry registry, JsonCodec jsonCodec) {
        this.registry = registry;
        this.jsonCodec = jsonCodec;
    }

    public void broadcastToRoom(String roomId, String type, String requestId, Object payload) {
        broadcastToRoom(roomId, new WsOutboundMessage<>(type, requestId, payload));
    }

    public void broadcastToRoom(String roomId, WsOutboundMessage<?> message) {
        List<WebSocketSession> sessions = registry.listRoomSessions(roomId);
        for (WebSocketSession session : sessions) {
            send(session, message);
        }
    }

    public void pushToUser(String roomId, String userId, String type, String requestId, Object payload) {
        pushToUser(roomId, userId, new WsOutboundMessage<>(type, requestId, payload));
    }

    public void pushToUser(String roomId, String userId, WsOutboundMessage<?> message) {
        registry.findRoomUserSession(roomId, userId)
                .ifPresent(session -> send(session, message));
    }

    public void pushToUsers(String roomId, Iterable<String> userIds, WsOutboundMessage<?> message) {
        if (userIds == null) {
            return;
        }
        LinkedHashSet<String> distinctUserIds = new LinkedHashSet<>();
        for (String userId : userIds) {
            if (userId != null && !userId.isBlank()) {
                distinctUserIds.add(userId);
            }
        }
        for (String userId : distinctUserIds) {
            pushToUser(roomId, userId, message);
        }
    }

    public void pushError(WebSocketSession session, String requestId, BusinessException exception) {
        send(session, new WsOutboundMessage<>(
                "ERROR",
                requestId,
                new WsErrorPayload(exception.getErrorCode().name(), exception.getMessage())
        ));
    }

    public void pushInternalError(WebSocketSession session, String requestId) {
        pushError(session, requestId, new BusinessException(ErrorCode.INTERNAL_ERROR));
    }

    private void send(WebSocketSession session, WsOutboundMessage<?> message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(jsonCodec.encode(message)));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to send websocket message", exception);
        }
    }
}
