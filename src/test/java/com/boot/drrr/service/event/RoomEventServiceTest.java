package com.boot.drrr.service.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.domain.event.RoomEvent;
import com.boot.drrr.domain.event.RoomEventType;
import com.boot.drrr.domain.message.Message;
import com.boot.drrr.domain.message.MessageType;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.repository.event.RoomEventRepository;
import com.boot.drrr.repository.message.MessageRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomIndexRepository.RoomIndexKey;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.service.message.RoomMessagePersistence;
import com.boot.drrr.ws.RoomWebSocketConnectionRegistry;
import com.boot.drrr.ws.RoomWebSocketOperations;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class RoomEventServiceTest {

    @Test
    void recordUserJoinPersistsEventGeneratesSystemMessageAndPushesBothEnvelopes() {
        Fixture fixture = new Fixture(1717302000000L);
        fixture.addOnlineMember("u_owner", "Owner", true, 1L);
        RoomMember joined = fixture.addOnlineMember("u_bob", "Bob", false, 2L);
        LocalTestWebSocketSession ownerSession = fixture.registerSession("u_owner");
        LocalTestWebSocketSession bobSession = fixture.registerSession("u_bob");

        RoomEvent event = fixture.service.recordUserJoin(fixture.room, joined);

        assertThat(event.type()).isEqualTo(RoomEventType.USER_JOIN);
        assertThat(event.payload().toString()).isEqualTo("{\"nickname\":\"Bob\"}");
        assertThat(fixture.events.listEvents(fixture.room.roomId())).extracting(RoomEvent::eventId).containsExactly("e_1");
        Message systemMessage = fixture.messages.listMessages(fixture.room.roomId()).get(0);
        assertThat(systemMessage.type()).isEqualTo(MessageType.SYSTEM);
        assertThat(systemMessage.sourceEventId()).isEqualTo("e_1");
        assertThat(systemMessage.sourceEventType()).isEqualTo(RoomEventType.USER_JOIN);
        assertThat(systemMessage.visibleTo()).containsExactly("u_owner", "u_bob");
        assertThat(ownerSession.textMessages()).hasSize(2);
        assertThat(ownerSession.textMessages().get(0).getPayload()).contains("ROOM_EVENT_OCCURRED").contains("USER_JOIN");
        assertThat(ownerSession.textMessages().get(1).getPayload()).contains("MESSAGE_CREATED").contains("joined the room");
        assertThat(bobSession.textMessages()).hasSize(2);
    }

    @Test
    void readEventsPreservesAppendOrder() {
        Fixture fixture = new Fixture(1717302100000L);
        fixture.addOnlineMember("u_owner", "Owner", true, 1L);
        fixture.addOnlineMember("u_bob", "Bob", false, 2L);

        fixture.service.recordUserJoin(fixture.room, fixture.members.findMember(fixture.room.roomId(), "u_bob").orElseThrow());
        fixture.service.recordOwnerTransfer(fixture.room, "u_owner", "u_bob");

        assertThat(fixture.service.readEvents(fixture.room.roomId()))
                .extracting(RoomEvent::type)
                .containsExactly(RoomEventType.USER_JOIN, RoomEventType.OWNER_TRANSFER);
    }

    @Test
    void recordRoomEmptySkipsSystemMessageAndPushWhenNobodyCanSeeIt() {
        Fixture fixture = new Fixture(1717302200000L);
        Room emptyRoom = new Room(
                fixture.room.roomId(),
                fixture.room.name(),
                fixture.room.description(),
                fixture.room.passwordHash(),
                fixture.room.maxMembers(),
                fixture.room.ownerUserId(),
                fixture.room.initialOwnerUserId(),
                RoomStatus.EMPTY,
                fixture.room.userListVisible(),
                fixture.room.historyStrategy(),
                fixture.room.allowOwnerConfigChange(),
                fixture.room.createdAt(),
                fixture.room.lastActiveAt(),
                1717302200000L
        );
        fixture.rooms.save(emptyRoom);

        fixture.service.recordRoomEmpty(emptyRoom, 1717302200000L);

        assertThat(fixture.events.listEvents(fixture.room.roomId())).extracting(RoomEvent::type).containsExactly(RoomEventType.ROOM_EMPTY);
        assertThat(fixture.messages.listMessages(fixture.room.roomId())).isEmpty();
    }

    @Test
    void createRoomConfigSystemMessageLeavesSourceEventFieldsNull() {
        Fixture fixture = new Fixture(1717302300000L);
        fixture.addOnlineMember("u_owner", "Owner", true, 1L);
        LocalTestWebSocketSession ownerSession = fixture.registerSession("u_owner");

        Message message = fixture.service.createRoomConfigSystemMessage(fixture.room, "u_owner", "Owner");

        assertThat(message.type()).isEqualTo(MessageType.SYSTEM);
        assertThat(message.sourceEventId()).isNull();
        assertThat(message.sourceEventType()).isNull();
        assertThat(message.content()).contains("updated the room configuration");
        assertThat(ownerSession.textMessages()).hasSize(1);
        assertThat(ownerSession.textMessages().get(0).getPayload()).contains("MESSAGE_CREATED");
    }

    private static final class Fixture {
        private final String roomId = "r_1";
        private final InMemoryRoomRepository rooms = new InMemoryRoomRepository();
        private final InMemoryRoomMemberRepository members = new InMemoryRoomMemberRepository();
        private final InMemoryRoomIndexRepository indexes = new InMemoryRoomIndexRepository();
        private final InMemoryMessageRepository messages = new InMemoryMessageRepository();
        private final InMemoryRoomEventRepository events = new InMemoryRoomEventRepository();
        private final RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();
        private final Room room;
        private final RoomEventService service;

        private Fixture(long nowMillis) {
            room = new Room(
                    roomId,
                    "Room",
                    "desc",
                    null,
                    8,
                    "u_owner",
                    "u_owner",
                    RoomStatus.ACTIVE,
                    true,
                    new HistoryStrategy(HistoryStrategyType.COUNT, 20),
                    true,
                    1L,
                    nowMillis,
                    null
            );
            rooms.save(room);
            RoomMessagePersistence persistence = new RoomMessagePersistence(messages, rooms, indexes);
            RoomWebSocketOperations operations = new RoomWebSocketOperations(registry, new JsonCodec(new ObjectMapper()));
            service = new RoomEventService(
                    events,
                    members,
                    persistence,
                    operations,
                    new FixedIdGenerator(),
                    new FixedTimeProvider(nowMillis),
                    new ObjectMapper()
            );
        }

        private RoomMember addOnlineMember(String userId, String nickname, boolean owner, long joinedAt) {
            RoomMember member = new RoomMember(roomId, userId, nickname, MemberStatus.ONLINE, joinedAt, joinedAt, owner);
            members.save(member);
            return member;
        }

        private LocalTestWebSocketSession registerSession(String userId) {
            LocalTestWebSocketSession session = new LocalTestWebSocketSession("s-" + userId, URI.create("ws://localhost/ws/rooms/" + roomId + "?userId=" + userId));
            registry.register(roomId, userId, session);
            return session;
        }
    }

    private static final class LocalTestWebSocketSession implements WebSocketSession {
        private final String id;
        private final URI uri;
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<TextMessage> textMessages = new ArrayList<>();
        private boolean open = true;

        private LocalTestWebSocketSession(String id, URI uri) {
            this.id = id;
            this.uri = uri;
        }

        private List<TextMessage> textMessages() {
            return textMessages;
        }

        @Override public String getId() { return id; }
        @Override public URI getUri() { return uri; }
        @Override public HttpHeaders getHandshakeHeaders() { return HttpHeaders.EMPTY; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) { }
        @Override public int getTextMessageSizeLimit() { return 65536; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) { }
        @Override public int getBinaryMessageSizeLimit() { return 65536; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public void sendMessage(WebSocketMessage<?> message) { if (message instanceof TextMessage textMessage) { textMessages.add(textMessage); } }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        @Override public void close(CloseStatus status) { open = false; }
    }

    private static final class InMemoryRoomRepository extends RoomRepository {
        private final Map<String, Room> storage = new ConcurrentHashMap<>();

        private InMemoryRoomRepository() {
            super(null);
        }

        @Override
        public void save(Room room) {
            storage.put(room.roomId(), room);
        }

        @Override
        public Optional<Room> findById(String roomId) {
            return Optional.ofNullable(storage.get(roomId));
        }
    }

    private static final class InMemoryRoomMemberRepository extends RoomMemberRepository {
        private final Map<String, Map<String, RoomMember>> storage = new ConcurrentHashMap<>();

        private InMemoryRoomMemberRepository() {
            super(null);
        }

        @Override
        public void save(RoomMember roomMember) {
            storage.computeIfAbsent(roomMember.roomId(), ignored -> new ConcurrentHashMap<>())
                    .put(roomMember.userId(), roomMember);
        }

        @Override
        public Optional<RoomMember> findMember(String roomId, String userId) {
            return Optional.ofNullable(storage.getOrDefault(roomId, Map.of()).get(userId));
        }

        @Override
        public List<RoomMember> listMembers(String roomId) {
            return storage.getOrDefault(roomId, Map.of()).values().stream()
                    .sorted(Comparator.comparingLong(RoomMember::joinedAt).thenComparing(RoomMember::userId))
                    .toList();
        }
    }

    private static final class InMemoryRoomIndexRepository extends RoomIndexRepository {
        private final Map<RoomIndexKey, Map<String, Double>> indexes = new ConcurrentHashMap<>();

        private InMemoryRoomIndexRepository() {
            super(null);
        }

        @Override
        public void zAdd(RoomIndexKey indexKey, String member, double score) {
            indexes.computeIfAbsent(indexKey, ignored -> new ConcurrentHashMap<>()).put(member, score);
        }

        @Override
        public void zRem(RoomIndexKey indexKey, String member) {
            indexes.computeIfAbsent(indexKey, ignored -> new ConcurrentHashMap<>()).remove(member);
        }
    }

    private static final class InMemoryMessageRepository extends MessageRepository {
        private final Map<String, List<Message>> storage = new ConcurrentHashMap<>();

        private InMemoryMessageRepository() {
            super(null);
        }

        @Override
        public void append(Message message) {
            storage.computeIfAbsent(message.roomId(), ignored -> new ArrayList<>()).add(message);
        }

        @Override
        public List<Message> listMessages(String roomId) {
            return List.copyOf(storage.getOrDefault(roomId, List.of()));
        }

        @Override
        public void trim(String roomId, long start, long end) {
            List<Message> values = storage.get(roomId);
            if (values == null || values.isEmpty()) {
                return;
            }
            int fromIndex = (int) Math.max(0, start);
            int toIndexExclusive = end < 0 ? values.size() : (int) Math.min(values.size(), end + 1);
            storage.put(roomId, new ArrayList<>(values.subList(fromIndex, toIndexExclusive)));
        }

        @Override
        public void deleteAll(String roomId) {
            storage.remove(roomId);
        }
    }

    private static final class InMemoryRoomEventRepository extends RoomEventRepository {
        private final Map<String, List<RoomEvent>> storage = new ConcurrentHashMap<>();

        private InMemoryRoomEventRepository() {
            super(null);
        }

        @Override
        public void append(RoomEvent roomEvent) {
            storage.computeIfAbsent(roomEvent.roomId(), ignored -> new ArrayList<>()).add(roomEvent);
        }

        @Override
        public List<RoomEvent> listEvents(String roomId) {
            return List.copyOf(storage.getOrDefault(roomId, List.of()));
        }
    }

    private static final class FixedIdGenerator implements IdGenerator {
        private int messageCounter = 1;
        private int eventCounter = 1;

        @Override public String newUserId() { throw new UnsupportedOperationException(); }
        @Override public String newRoomId() { throw new UnsupportedOperationException(); }
        @Override public String newMessageId() { return "m_" + messageCounter++; }
        @Override public String newEventId() { return "e_" + eventCounter++; }
    }

    private static final class FixedTimeProvider implements TimeProvider {
        private final Clock clock;

        private FixedTimeProvider(long nowMillis) {
            this.clock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
        }

        @Override public Instant now() { return clock.instant(); }
        @Override public long nowMillis() { return clock.millis(); }
        @Override public Clock clock() { return clock; }
    }
}
