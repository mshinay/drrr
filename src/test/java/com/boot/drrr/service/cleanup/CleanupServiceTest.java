package com.boot.drrr.service.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.common.lock.JvmRoomLock;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.domain.event.RoomEvent;
import com.boot.drrr.domain.event.RoomEventType;
import com.boot.drrr.domain.governance.BanRecord;
import com.boot.drrr.domain.governance.MuteRecord;
import com.boot.drrr.domain.message.Message;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;
import com.boot.drrr.repository.event.RoomEventRepository;
import com.boot.drrr.repository.governance.GovernanceRepository;
import com.boot.drrr.repository.message.MessageRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomIndexRepository.RoomIndexKey;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.repository.user.UserSessionRepository;
import com.boot.drrr.service.event.RoomEventService;
import com.boot.drrr.service.message.RoomMessagePersistence;
import com.boot.drrr.service.owner.OwnerTransferService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class CleanupServiceTest {

    @Test
    void timedOutReconnectingUserBecomesOfflineLeavesRoomAndTransfersOwner() {
        Fixture fixture = new Fixture(1717303200000L);
        fixture.seedActiveRoom("r_cleanup", "Night Talk", "u_owner");
        fixture.addMember("u_owner", "Owner", true, UserStatus.RECONNECTING, false, MemberStatus.RECONNECTING);
        fixture.addMember("u_member", "Member", false, UserStatus.ONLINE, true, MemberStatus.ONLINE);
        fixture.userSessions.saveReconnectingUser("u_owner", fixture.nowMillis - 301_000L);

        int processed = fixture.cleanupService.cleanupReconnectingUsersTimedOut(300_000L);

        assertThat(processed).isEqualTo(1);
        assertThat(fixture.userSessions.findById("u_owner")).hasValueSatisfying(session -> {
            assertThat(session.status()).isEqualTo(UserStatus.OFFLINE);
            assertThat(session.connected()).isFalse();
            assertThat(session.currentRoomId()).isNull();
        });
        assertThat(fixture.userSessions.isReconnectingUser("u_owner")).isFalse();
        assertThat(fixture.members.findMember("r_cleanup", "u_owner")).isEmpty();
        assertThat(fixture.rooms.findById("r_cleanup")).hasValueSatisfying(room -> {
            assertThat(room.status()).isEqualTo(RoomStatus.ACTIVE);
            assertThat(room.ownerUserId()).isEqualTo("u_member");
            assertThat(room.emptySince()).isNull();
        });
        assertThat(fixture.events.listEvents("r_cleanup")).extracting(RoomEvent::type)
                .containsExactly(RoomEventType.USER_LEAVE, RoomEventType.OWNER_TRANSFER);
        assertThat(fixture.messages.listMessages("r_cleanup")).extracting(Message::sourceEventType)
                .containsExactly(RoomEventType.USER_LEAVE, RoomEventType.OWNER_TRANSFER);
    }

    @Test
    void expiredEmptyRoomWritesExpiredEventThenDeletesRuntimeKeysAndNotifiesStaleConnections() {
        Fixture fixture = new Fixture(1717390000000L);
        long emptySince = fixture.nowMillis - 86_401_000L;
        fixture.seedEmptyRoom("r_expired", "Night Talk", "u_owner", emptySince);
        fixture.events.append(new RoomEvent("event_old", "r_expired", RoomEventType.ROOM_EMPTY, null, null, fixture.objectMapper.createObjectNode(), emptySince));
        fixture.messages.append(new Message("msg_old", "r_expired", com.boot.drrr.domain.message.MessageType.SYSTEM, null, "System", null, null, "old", List.of("u_stale"), null, null, emptySince));
        fixture.governance.saveMuteRecord(new MuteRecord("r_expired", "u_muted", "u_owner", emptySince, emptySince + 60_000L, "spam"));
        fixture.governance.saveBanRecord(new BanRecord("r_expired", "u_banned", "u_owner", emptySince, "ban"));
        LocalTestWebSocketSession staleSession = fixture.registerSession("r_expired", "u_stale");

        int processed = fixture.cleanupService.cleanupExpiredEmptyRooms(86_400_000L);

        assertThat(processed).isEqualTo(1);
        assertThat(fixture.events.appendedTypes).contains(RoomEventType.ROOM_EXPIRED);
        assertThat(fixture.rooms.findById("r_expired")).isEmpty();
        assertThat(fixture.members.listMembers("r_expired")).isEmpty();
        assertThat(fixture.events.listEvents("r_expired")).isEmpty();
        assertThat(fixture.messages.listMessages("r_expired")).isEmpty();
        assertThat(fixture.governance.listMutedUserIdsByScore("r_expired", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)).isEmpty();
        assertThat(fixture.governance.listBanUserIds("r_expired")).isEmpty();
        assertThat(fixture.governance.findMuteRecord("r_expired", "u_muted")).isEmpty();
        assertThat(fixture.governance.findBanRecord("r_expired", "u_banned")).isEmpty();
        assertThat(fixture.indexes.zRange(RoomIndexKey.ACTIVE, 0, -1)).doesNotContain("r_expired");
        assertThat(fixture.indexes.zRange(RoomIndexKey.EMPTY, 0, -1)).doesNotContain("r_expired");
        assertThat(staleSession.textMessages()).hasSize(3);
        assertThat(staleSession.textMessages().get(0).getPayload()).contains("ROOM_EVENT_OCCURRED").contains("ROOM_EXPIRED");
        assertThat(staleSession.textMessages().get(1).getPayload()).contains("MESSAGE_CREATED").contains("expired and was removed");
        assertThat(staleSession.textMessages().get(2).getPayload()).contains("ROOM_REMOVED").contains("EXPIRED");
        assertThat(staleSession.isOpen()).isFalse();
    }

    private static final class Fixture {
        private final long nowMillis;
        private final InMemoryUserSessionRepository userSessions = new InMemoryUserSessionRepository();
        private final InMemoryRoomRepository rooms = new InMemoryRoomRepository();
        private final InMemoryRoomMemberRepository members = new InMemoryRoomMemberRepository();
        private final InMemoryRoomIndexRepository indexes = new InMemoryRoomIndexRepository();
        private final InMemoryGovernanceRepository governance = new InMemoryGovernanceRepository();
        private final InMemoryRoomEventRepository events = new InMemoryRoomEventRepository();
        private final InMemoryMessageRepository messages = new InMemoryMessageRepository();
        private final OwnerTransferService ownerTransferService = new OwnerTransferService();
        private final FixedTimeProvider timeProvider;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final CleanupService cleanupService;
        private final RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();

        private Fixture(long nowMillis) {
            this.nowMillis = nowMillis;
            this.timeProvider = new FixedTimeProvider(nowMillis);
            JsonCodec jsonCodec = new JsonCodec(objectMapper);
            RoomWebSocketOperations roomWebSocketOperations = new RoomWebSocketOperations(registry, jsonCodec);
            RoomMessagePersistence roomMessagePersistence = new RoomMessagePersistence(messages, rooms, indexes);
            RoomEventService roomEventService = new RoomEventService(
                    events,
                    members,
                    roomMessagePersistence,
                    roomWebSocketOperations,
                    new TestIdGenerator(),
                    timeProvider,
                    objectMapper
            );
            this.cleanupService = new CleanupService(
                    userSessions,
                    rooms,
                    members,
                    indexes,
                    governance,
                    events,
                    messages,
                    ownerTransferService,
                    roomEventService,
                    roomWebSocketOperations,
                    registry,
                    timeProvider,
                    new JvmRoomLock()
            );
        }

        private void seedActiveRoom(String roomId, String name, String ownerUserId) {
            Room room = new Room(
                    roomId,
                    name,
                    "desc",
                    null,
                    6,
                    ownerUserId,
                    ownerUserId,
                    RoomStatus.ACTIVE,
                    true,
                    new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                    true,
                    nowMillis - 10_000L,
                    nowMillis - 1_000L,
                    null
            );
            rooms.save(room);
            indexes.zAdd(RoomIndexKey.ACTIVE, roomId, room.lastActiveAt());
        }

        private void seedEmptyRoom(String roomId, String name, String ownerUserId, long emptySince) {
            Room room = new Room(
                    roomId,
                    name,
                    "desc",
                    null,
                    6,
                    ownerUserId,
                    ownerUserId,
                    RoomStatus.EMPTY,
                    true,
                    new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                    true,
                    emptySince - 10_000L,
                    emptySince,
                    emptySince
            );
            rooms.save(room);
            indexes.zAdd(RoomIndexKey.ACTIVE, roomId, room.lastActiveAt());
            indexes.zAdd(RoomIndexKey.EMPTY, roomId, emptySince);
        }

        private void addMember(String userId, String nickname, boolean owner, UserStatus userStatus, boolean connected, MemberStatus memberStatus) {
            String roomId = "r_cleanup";
            userSessions.save(new UserSession(
                    userId,
                    nickname,
                    roomId,
                    userStatus,
                    connected,
                    connected ? nowMillis - 500L : nowMillis - 5_000L,
                    connected ? null : nowMillis - 5_000L,
                    nowMillis - 20_000L,
                    nowMillis - 1_000L
            ));
            members.save(new RoomMember(
                    roomId,
                    userId,
                    nickname,
                    memberStatus,
                    nowMillis - (owner ? 2_000L : 1_000L),
                    nowMillis - 500L,
                    owner
            ));
        }

        private LocalTestWebSocketSession registerSession(String roomId, String userId) {
            LocalTestWebSocketSession session = new LocalTestWebSocketSession(roomId + ":" + userId);
            registry.register(roomId, userId, session);
            return session;
        }
    }

    private static final class FixedTimeProvider implements TimeProvider {
        private final Clock clock;

        private FixedTimeProvider(long nowMillis) {
            this.clock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
        }

        @Override
        public Instant now() {
            return clock.instant();
        }

        @Override
        public long nowMillis() {
            return clock.millis();
        }

        @Override
        public Clock clock() {
            return clock;
        }
    }

    private static final class TestIdGenerator implements IdGenerator {
        private int messageSequence;
        private int eventSequence;

        @Override
        public String newUserId() {
            return "u_generated";
        }

        @Override
        public String newRoomId() {
            return "r_generated";
        }

        @Override
        public String newMessageId() {
            messageSequence++;
            return "msg_" + messageSequence;
        }

        @Override
        public String newEventId() {
            eventSequence++;
            return "event_" + eventSequence;
        }
    }

    private static final class InMemoryUserSessionRepository extends UserSessionRepository {
        private final Map<String, UserSession> sessions = new LinkedHashMap<>();
        private final Map<String, Long> reconnectingUsers = new LinkedHashMap<>();

        private InMemoryUserSessionRepository() {
            super(null);
        }

        @Override
        public void save(UserSession userSession) {
            sessions.put(userSession.userId(), userSession);
        }

        @Override
        public Optional<UserSession> findById(String userId) {
            return Optional.ofNullable(sessions.get(userId));
        }

        @Override
        public void saveReconnectingUser(String userId, long lastDisconnectedAt) {
            reconnectingUsers.put(userId, lastDisconnectedAt);
        }

        @Override
        public boolean isReconnectingUser(String userId) {
            return reconnectingUsers.containsKey(userId);
        }

        @Override
        public void removeReconnectingUser(String userId) {
            reconnectingUsers.remove(userId);
        }

        @Override
        public Set<String> listReconnectingUserIdsByScore(double minScoreInclusive, double maxScoreInclusive) {
            LinkedHashSet<String> userIds = new LinkedHashSet<>();
            reconnectingUsers.entrySet().stream()
                    .filter(entry -> entry.getValue() >= minScoreInclusive && entry.getValue() <= maxScoreInclusive)
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(entry -> userIds.add(entry.getKey()));
            return userIds;
        }
    }

    private static final class InMemoryRoomRepository extends RoomRepository {
        private final Map<String, Room> rooms = new LinkedHashMap<>();

        private InMemoryRoomRepository() {
            super(null);
        }

        @Override
        public void save(Room room) {
            rooms.put(room.roomId(), room);
        }

        @Override
        public Optional<Room> findById(String roomId) {
            return Optional.ofNullable(rooms.get(roomId));
        }

        @Override
        public void deleteById(String roomId) {
            rooms.remove(roomId);
        }
    }

    private static final class InMemoryRoomMemberRepository extends RoomMemberRepository {
        private final Map<String, RoomMember> members = new LinkedHashMap<>();

        private InMemoryRoomMemberRepository() {
            super(null);
        }

        @Override
        public void save(RoomMember roomMember) {
            members.put(key(roomMember.roomId(), roomMember.userId()), roomMember);
        }

        @Override
        public Optional<RoomMember> findMember(String roomId, String userId) {
            return Optional.ofNullable(members.get(key(roomId, userId)));
        }

        @Override
        public List<RoomMember> listMembers(String roomId) {
            return members.values().stream()
                    .filter(member -> roomId.equals(member.roomId()))
                    .sorted(Comparator.comparingLong(RoomMember::joinedAt).thenComparing(RoomMember::userId))
                    .toList();
        }

        @Override
        public void removeMember(String roomId, String userId) {
            members.remove(key(roomId, userId));
        }

        @Override
        public void deleteAll(String roomId) {
            members.entrySet().removeIf(entry -> entry.getValue().roomId().equals(roomId));
        }

        private String key(String roomId, String userId) {
            return roomId + ":" + userId;
        }
    }

    private static final class InMemoryRoomIndexRepository extends RoomIndexRepository {
        private final Map<RoomIndexKey, Map<String, Double>> indexes = new HashMap<>();

        private InMemoryRoomIndexRepository() {
            super(null);
            indexes.put(RoomIndexKey.ACTIVE, new LinkedHashMap<>());
            indexes.put(RoomIndexKey.EMPTY, new LinkedHashMap<>());
        }

        @Override
        public void zAdd(RoomIndexKey indexKey, String member, double score) {
            indexes.get(indexKey).put(member, score);
        }

        @Override
        public void zRem(RoomIndexKey indexKey, String member) {
            indexes.get(indexKey).remove(member);
        }

        @Override
        public Set<String> zRange(RoomIndexKey indexKey, long start, long end) {
            return indexes.get(indexKey).entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        }

        @Override
        public Set<String> zRangeByScore(RoomIndexKey indexKey, double minScoreInclusive, double maxScoreInclusive) {
            return indexes.get(indexKey).entrySet().stream()
                    .filter(entry -> entry.getValue() >= minScoreInclusive && entry.getValue() <= maxScoreInclusive)
                    .sorted(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        }
    }

    private static final class InMemoryGovernanceRepository extends GovernanceRepository {
        private final Map<String, MuteRecord> muteRecords = new LinkedHashMap<>();
        private final Map<String, BanRecord> banRecords = new LinkedHashMap<>();
        private final Map<String, Long> muteIndex = new LinkedHashMap<>();
        private final Map<String, LinkedHashSet<String>> banIndex = new LinkedHashMap<>();

        private InMemoryGovernanceRepository() {
            super(null);
        }

        @Override
        public void saveMuteRecord(MuteRecord muteRecord) {
            muteRecords.put(key(muteRecord.roomId(), muteRecord.userId()), muteRecord);
            muteIndex.put(key(muteRecord.roomId(), muteRecord.userId()), muteRecord.endAt());
        }

        @Override
        public Optional<MuteRecord> findMuteRecord(String roomId, String userId) {
            return Optional.ofNullable(muteRecords.get(key(roomId, userId)));
        }

        @Override
        public Set<String> listMutedUserIdsByScore(String roomId, double minScoreInclusive, double maxScoreInclusive) {
            LinkedHashSet<String> userIds = new LinkedHashSet<>();
            muteIndex.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(roomId + ":"))
                    .filter(entry -> entry.getValue() >= minScoreInclusive && entry.getValue() <= maxScoreInclusive)
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(entry -> userIds.add(entry.getKey().substring(roomId.length() + 1)));
            return userIds;
        }

        @Override
        public void deleteMuteState(String roomId) {
            muteRecords.entrySet().removeIf(entry -> entry.getKey().startsWith(roomId + ":"));
            muteIndex.entrySet().removeIf(entry -> entry.getKey().startsWith(roomId + ":"));
        }

        @Override
        public void saveBanRecord(BanRecord banRecord) {
            banRecords.put(key(banRecord.roomId(), banRecord.userId()), banRecord);
            banIndex.computeIfAbsent(banRecord.roomId(), ignored -> new LinkedHashSet<>()).add(banRecord.userId());
        }

        @Override
        public Optional<BanRecord> findBanRecord(String roomId, String userId) {
            return Optional.ofNullable(banRecords.get(key(roomId, userId)));
        }

        @Override
        public Set<String> listBanUserIds(String roomId) {
            return new LinkedHashSet<>(banIndex.getOrDefault(roomId, new LinkedHashSet<>()));
        }

        @Override
        public void deleteBanState(String roomId) {
            banRecords.entrySet().removeIf(entry -> entry.getKey().startsWith(roomId + ":"));
            banIndex.remove(roomId);
        }

        private String key(String roomId, String userId) {
            return roomId + ":" + userId;
        }
    }

    private static final class InMemoryRoomEventRepository extends RoomEventRepository {
        private final Map<String, List<RoomEvent>> eventsByRoom = new LinkedHashMap<>();
        private final List<RoomEventType> appendedTypes = new ArrayList<>();

        private InMemoryRoomEventRepository() {
            super(null);
        }

        @Override
        public void append(RoomEvent roomEvent) {
            eventsByRoom.computeIfAbsent(roomEvent.roomId(), ignored -> new ArrayList<>()).add(roomEvent);
            appendedTypes.add(roomEvent.type());
        }

        @Override
        public List<RoomEvent> listEvents(String roomId) {
            return List.copyOf(eventsByRoom.getOrDefault(roomId, List.of()));
        }

        @Override
        public void deleteAll(String roomId) {
            eventsByRoom.remove(roomId);
        }
    }

    private static final class InMemoryMessageRepository extends MessageRepository {
        private final Map<String, List<Message>> messagesByRoom = new LinkedHashMap<>();

        private InMemoryMessageRepository() {
            super(null);
        }

        @Override
        public void append(Message message) {
            messagesByRoom.computeIfAbsent(message.roomId(), ignored -> new ArrayList<>()).add(message);
        }

        @Override
        public List<Message> listMessages(String roomId) {
            return List.copyOf(messagesByRoom.getOrDefault(roomId, List.of()));
        }

        @Override
        public void trim(String roomId, long start, long end) {
            List<Message> messages = new ArrayList<>(messagesByRoom.getOrDefault(roomId, List.of()));
            if (messages.isEmpty()) {
                return;
            }
            int fromIndex = Math.max(0, (int) start);
            int toIndexExclusive = Math.min(messages.size(), (int) end + 1);
            if (fromIndex >= toIndexExclusive) {
                messagesByRoom.remove(roomId);
                return;
            }
            messagesByRoom.put(roomId, new ArrayList<>(messages.subList(fromIndex, toIndexExclusive)));
        }

        @Override
        public void deleteAll(String roomId) {
            messagesByRoom.remove(roomId);
        }
    }

    private static final class LocalTestWebSocketSession implements WebSocketSession {
        private final String id;
        private final List<TextMessage> textMessages = new ArrayList<>();
        private final Map<String, Object> attributes = new HashMap<>();
        private boolean open = true;

        private LocalTestWebSocketSession(String id) {
            this.id = id;
        }

        private List<TextMessage> textMessages() {
            return textMessages;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws/rooms/test");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            if (message instanceof TextMessage textMessage) {
                textMessages.add(textMessage);
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(CloseStatus status) {
            open = false;
        }
    }
}
