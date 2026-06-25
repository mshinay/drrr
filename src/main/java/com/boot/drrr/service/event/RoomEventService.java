package com.boot.drrr.service.event;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.common.ws.WsOutboundMessage;
import com.boot.drrr.domain.event.RoomEvent;
import com.boot.drrr.domain.event.RoomEventType;
import com.boot.drrr.domain.message.Message;
import com.boot.drrr.domain.message.MessageType;
import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.repository.event.RoomEventRepository;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.service.message.RoomMessagePersistence;
import com.boot.drrr.ws.RoomWebSocketOperations;
import com.boot.drrr.ws.event.RoomEventOccurredPayload;
import com.boot.drrr.ws.message.MessageCreatedPayload;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RoomEventService {
    private static final String SYSTEM_NICKNAME = "System";

    private final RoomEventRepository roomEventRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomMessagePersistence roomMessagePersistence;
    private final RoomWebSocketOperations webSocketOperations;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    public RoomEventService(
            RoomEventRepository roomEventRepository,
            RoomMemberRepository roomMemberRepository,
            RoomMessagePersistence roomMessagePersistence,
            RoomWebSocketOperations webSocketOperations,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            ObjectMapper objectMapper
    ) {
        this.roomEventRepository = roomEventRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.roomMessagePersistence = roomMessagePersistence;
        this.webSocketOperations = webSocketOperations;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    public RoomEvent recordUserJoin(Room room, RoomMember joinedMember) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("nickname", joinedMember.nickname());
        return recordEvent(room, RoomEventType.USER_JOIN, joinedMember.userId(), joinedMember.userId(), payload);
    }

    public RoomEvent recordUserLeave(Room room, RoomMember leavingMember) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("nickname", leavingMember.nickname());
        return recordEvent(room, RoomEventType.USER_LEAVE, leavingMember.userId(), leavingMember.userId(), payload);
    }

    public RoomEvent recordUserReconnecting(Room room, RoomMember reconnectingMember, long lastDisconnectedAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("nickname", reconnectingMember.nickname());
        payload.put("lastDisconnectedAt", lastDisconnectedAt);
        return recordEvent(room, RoomEventType.USER_RECONNECTING, reconnectingMember.userId(), reconnectingMember.userId(), payload);
    }

    public RoomEvent recordUserReconnected(Room room, RoomMember reconnectedMember, long reconnectedAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("nickname", reconnectedMember.nickname());
        payload.put("reconnectedAt", reconnectedAt);
        return recordEvent(room, RoomEventType.USER_RECONNECTED, reconnectedMember.userId(), reconnectedMember.userId(), payload);
    }

    public RoomEvent recordOwnerTransfer(Room room, String fromUserId, String toUserId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fromUserId", fromUserId);
        payload.put("toUserId", toUserId);
        return recordEvent(room, RoomEventType.OWNER_TRANSFER, fromUserId, toUserId, payload);
    }

    public RoomEvent recordRoomEmpty(Room room, long emptySince) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("emptySince", emptySince);
        return recordEvent(room, RoomEventType.ROOM_EMPTY, null, null, payload);
    }

    public Message createRoomConfigSystemMessage(Room room, String operatorUserId, String operatorNickname) {
        validateRoom(room);
        long now = timeProvider.nowMillis();
        List<String> visibleTo = resolveOnlineRecipients(room.roomId());
        Message message = new Message(
                idGenerator.newMessageId(),
                room.roomId(),
                MessageType.SYSTEM,
                blankToNull(operatorUserId),
                SYSTEM_NICKNAME,
                null,
                null,
                buildConfigChangedMessage(operatorNickname),
                visibleTo,
                null,
                null,
                now
        );
        Message stored = roomMessagePersistence.store(room, message, now);
        pushSystemMessage(stored);
        return stored;
    }

    public List<RoomEvent> readEvents(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "roomId must not be blank");
        }
        return roomEventRepository.listEvents(roomId);
    }

    private RoomEvent recordEvent(Room room, RoomEventType type, String operatorUserId, String targetUserId, JsonNode payload) {
        validateRoom(room);
        long now = timeProvider.nowMillis();
        RoomEvent event = new RoomEvent(
                idGenerator.newEventId(),
                room.roomId(),
                type,
                blankToNull(operatorUserId),
                blankToNull(targetUserId),
                payload == null ? objectMapper.createObjectNode() : payload.deepCopy(),
                now
        );
        roomEventRepository.append(event);

        List<String> visibleTo = resolveVisibleRecipients(room.roomId(), type);
        pushEvent(event, visibleTo);

        if (shouldCreateSystemMessage(type, visibleTo)) {
            Message systemMessage = new Message(
                    idGenerator.newMessageId(),
                    room.roomId(),
                    MessageType.SYSTEM,
                    blankToNull(event.operatorUserId()),
                    SYSTEM_NICKNAME,
                    blankToNull(event.targetUserId()),
                    null,
                    buildEventMessage(event),
                    visibleTo,
                    event.eventId(),
                    event.type(),
                    now
            );
            Message stored = roomMessagePersistence.store(room, systemMessage, now);
            pushSystemMessage(stored);
        }
        return event;
    }

    private List<String> resolveVisibleRecipients(String roomId, RoomEventType type) {
        List<String> onlineRecipients = resolveOnlineRecipients(roomId);
        if (type == RoomEventType.ROOM_EMPTY) {
            return onlineRecipients;
        }
        return onlineRecipients;
    }

    private List<String> resolveOnlineRecipients(String roomId) {
        LinkedHashSet<String> recipients = roomMemberRepository.listMembers(roomId).stream()
                .filter(member -> member.memberStatus() == MemberStatus.ONLINE)
                .map(RoomMember::userId)
                .filter(Objects::nonNull)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        return List.copyOf(recipients);
    }

    private boolean shouldCreateSystemMessage(RoomEventType type, List<String> visibleTo) {
        if (type == RoomEventType.ROOM_EMPTY) {
            return !visibleTo.isEmpty();
        }
        return true;
    }

    private void pushEvent(RoomEvent event, List<String> recipients) {
        if (recipients.isEmpty()) {
            return;
        }
        webSocketOperations.pushToUsers(
                event.roomId(),
                recipients,
                new WsOutboundMessage<>("ROOM_EVENT_OCCURRED", null, new RoomEventOccurredPayload(event))
        );
    }

    private void pushSystemMessage(Message message) {
        if (message.visibleTo() == null || message.visibleTo().isEmpty()) {
            return;
        }
        webSocketOperations.pushToUsers(
                message.roomId(),
                message.visibleTo(),
                new WsOutboundMessage<>("MESSAGE_CREATED", null, new MessageCreatedPayload(message))
        );
    }

    private String buildEventMessage(RoomEvent event) {
        return switch (event.type()) {
            case USER_JOIN -> nicknameFromPayload(event.payload(), event.targetUserId()) + " joined the room.";
            case USER_LEAVE -> nicknameFromPayload(event.payload(), event.targetUserId()) + " left the room.";
            case USER_RECONNECTING -> nicknameFromPayload(event.payload(), event.targetUserId()) + " is reconnecting.";
            case USER_RECONNECTED -> nicknameFromPayload(event.payload(), event.targetUserId()) + " reconnected.";
            case OWNER_TRANSFER -> "Room ownership transferred from "
                    + textValue(event.payload(), "fromUserId", event.operatorUserId())
                    + " to "
                    + textValue(event.payload(), "toUserId", event.targetUserId())
                    + ".";
            case ROOM_EMPTY -> "The room is now empty.";
            case USER_MUTED, USER_KICKED, USER_BANNED, ROOM_EXPIRED -> throw new IllegalArgumentException(
                    "event type is outside current card scope: " + event.type()
            );
        };
    }

    private String buildConfigChangedMessage(String operatorNickname) {
        String actor = operatorNickname == null || operatorNickname.isBlank() ? "A room member" : operatorNickname.trim();
        return actor + " updated the room configuration.";
    }

    private String nicknameFromPayload(JsonNode payload, String fallbackUserId) {
        String nickname = textValue(payload, "nickname", null);
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        return fallbackUserId == null || fallbackUserId.isBlank() ? "A room member" : fallbackUserId;
    }

    private String textValue(JsonNode payload, String fieldName, String fallback) {
        if (payload != null && payload.hasNonNull(fieldName)) {
            String value = payload.get(fieldName).asText();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private void validateRoom(Room room) {
        if (room == null || room.roomId() == null || room.roomId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "room must exist before recording events");
        }
    }
}

