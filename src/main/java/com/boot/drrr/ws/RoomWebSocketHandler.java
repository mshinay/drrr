package com.boot.drrr.ws;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.common.ws.WsOutboundMessage;
import com.boot.drrr.domain.message.Message;
import com.boot.drrr.service.message.MessageService;
import com.boot.drrr.service.message.SendDirectMessageCommand;
import com.boot.drrr.service.message.SendPublicMessageCommand;
import com.boot.drrr.service.user.UserSessionService;
import com.boot.drrr.ws.message.MessageCreatedPayload;
import com.boot.drrr.ws.message.SendDirectMessagePayload;
import com.boot.drrr.ws.message.SendPublicMessagePayload;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {
    private final UserSessionService userSessionService;
    private final MessageService messageService;
    private final RoomWebSocketConnectionRegistry registry;
    private final RoomWebSocketOperations operations;
    private final JsonCodec jsonCodec;

    public RoomWebSocketHandler(
            UserSessionService userSessionService,
            MessageService messageService,
            RoomWebSocketConnectionRegistry registry,
            RoomWebSocketOperations operations,
            JsonCodec jsonCodec
    ) {
        this.userSessionService = userSessionService;
        this.messageService = messageService;
        this.registry = registry;
        this.operations = operations;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String roomId = requiredAttribute(session, RoomWebSocketAttributes.ROOM_ID);
        String userId = requiredAttribute(session, RoomWebSocketAttributes.USER_ID);
        registry.register(roomId, userId, session);
        try {
            userSessionService.markRoomConnected(userId, roomId);
        } catch (RuntimeException exception) {
            registry.unregister(session.getId());
            throw exception;
        }
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
            switch (inbound.type()) {
                case "SEND_PUBLIC_MESSAGE" -> handleSendPublicMessage(session, inbound);
                case "SEND_DIRECT_MESSAGE" -> handleSendDirectMessage(session, inbound);
                default -> throw new BusinessException(
                        ErrorCode.INVALID_REQUEST,
                        "unsupported websocket message type: " + inbound.type()
                );
            }
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

    private void handleSendPublicMessage(WebSocketSession session, WsInboundEnvelope inbound) {
        SendPublicMessagePayload payload = decodePayload(inbound, SendPublicMessagePayload.class);
        assertInboundContext(session, payload.roomId(), payload.senderUserId());
        Message message = messageService.sendPublicMessage(new SendPublicMessageCommand(
                payload.roomId(),
                payload.senderUserId(),
                payload.content()
        ));
        operations.broadcastToRoom(
                payload.roomId(),
                new WsOutboundMessage<>("MESSAGE_CREATED", inbound.requestId(), new MessageCreatedPayload(message))
        );
    }

    private void handleSendDirectMessage(WebSocketSession session, WsInboundEnvelope inbound) {
        SendDirectMessagePayload payload = decodePayload(inbound, SendDirectMessagePayload.class);
        assertInboundContext(session, payload.roomId(), payload.senderUserId());
        Message message = messageService.sendDirectMessage(new SendDirectMessageCommand(
                payload.roomId(),
                payload.senderUserId(),
                payload.targetUserId(),
                payload.content()
        ));

        WsOutboundMessage<MessageCreatedPayload> outbound = new WsOutboundMessage<>(
                "MESSAGE_CREATED",
                inbound.requestId(),
                new MessageCreatedPayload(message)
        );
        for (String userId : directRecipients(message)) {
            operations.pushToUser(payload.roomId(), userId, outbound);
        }
    }

    private Set<String> directRecipients(Message message) {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        recipients.add(message.senderUserId());
        if (message.targetUserId() != null && !message.targetUserId().isBlank()) {
            recipients.add(message.targetUserId());
        }
        return recipients;
    }

    private <T> T decodePayload(WsInboundEnvelope inbound, Class<T> payloadType) {
        if (inbound.payload() == null || inbound.payload().isNull()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "payload must not be null");
        }
        try {
            return jsonCodec.decode(inbound.payload().toString(), payloadType);
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "invalid payload for type: " + inbound.type());
        }
    }

    private void assertInboundContext(WebSocketSession session, String roomId, String userId) {
        String expectedRoomId = requiredAttribute(session, RoomWebSocketAttributes.ROOM_ID);
        String expectedUserId = requiredAttribute(session, RoomWebSocketAttributes.USER_ID);
        if (!expectedRoomId.equals(roomId) || !expectedUserId.equals(userId)) {
            throw new BusinessException(ErrorCode.ROOM_CONTEXT_MISMATCH);
        }
    }

    private String requiredAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "missing websocket attribute: " + key);
    }
}
