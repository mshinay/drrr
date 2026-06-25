package com.boot.drrr.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.common.lock.JvmRoomLock;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
import com.boot.drrr.domain.event.RoomEvent;
import com.boot.drrr.domain.event.RoomEventType;
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
import com.boot.drrr.repository.lobby.LobbyRepository;
import com.boot.drrr.repository.message.MessageRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomIndexRepository.RoomIndexKey;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.repository.user.UserSessionRepository;
import com.boot.drrr.service.event.RoomEventService;
import com.boot.drrr.service.message.MessageService;
import com.boot.drrr.service.message.RoomMessagePersistence;
import com.boot.drrr.service.message.SendDirectMessageCommand;
import com.boot.drrr.service.message.SendPublicMessageCommand;
import com.boot.drrr.service.owner.OwnerTransferService;
import com.boot.drrr.service.room.JoinRoomCommand;
import com.boot.drrr.service.room.JoinRoomView;
import com.boot.drrr.service.room.RoomPasswordHasher;
import com.boot.drrr.service.room.RoomService;
import com.boot.drrr.service.user.UserSessionService;
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

class GovernanceServiceTest {

    @Test
    void nonOwnerGovernanceRequestsFailWithForbidden() {
        Fixture fixture = new Fixture(1717303000000L);
        fixture.addMember("u_owner", "Owner", true, UserStatus.ONLINE, true);
        fixture.addMember("u_member", "Member", false, UserStatus.ONLINE, true);
        fixture.addMember("u_target", "Target", false, UserStatus.ONLINE, true);

        assertForbidden(() -> fixture.governanceService.muteMember(
                fixture.roomId,
                "u_target",
                new MuteMemberCommand("u_member", 30, "owner_only")
        ));
        assertForbidden(() -> fixture.governanceService.kickMember(
                fixture.roomId,
                "u_target",
                new KickMemberCommand("u_member", "owner_only")
        ));
        assertForbidden(() -> fixture.governanceService.banMember(
                fixture.roomId,
                "u_target",
                new BanMemberCommand("u_member", "owner_only")
        ));
    }

    @Test
    void muteMemberPersistsRecordEmitsEventAndBlocksChat() {
        Fixture fixture = new Fixture(1717303100000L);
        fixture.addMember("u_owner", "Owner", true, UserStatus.ONLINE, true);
        fixture.addMember("u_target", "Target", false, UserStatus.ONLINE, true);

        MuteMemberResult result = fixture.governanceService.muteMember(
                fixture.roomId,
                "u_target",
                new MuteMemberCommand("u_owner", 30, "spam")
        );

        assertThat(result.muted()).isTrue();
        assertThat(result.record().roomId()).isEqualTo(fixture.roomId);
        assertThat(result.record().userId()).isEqualTo("u_target");
        assertThat(result.record().mutedBy()).isEqualTo("u_owner");
        assertThat(result.record().endAt()).isEqualTo(1717304900000L);
        assertThat(fixture.governanceRepository.findMuteRecord(fixture.roomId, "u_target")).contains(result.record());
        assertThat(fixture.events.listEvents(fixture.roomId)).extracting(RoomEvent::type).containsExactly(RoomEventType.USER_MUTED);
        assertThat(fixture.messages.listMessages(fixture.roomId)).singleElement().satisfies(message -> {
            assertThat(message.sourceEventType()).isEqualTo(RoomEventType.USER_MUTED);
            assertThat(message.content()).contains("muted for 30 minutes");
        });

        assertThatThrownBy(() -> fixture.messageService.sendPublicMessage(new SendPublicMessageCommand(
                fixture.roomId,
                "u_target",
                "blocked"
        )))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.USER_MUTED);

        assertThatThrownBy(() -> fixture.messageService.sendDirectMessage(new SendDirectMessageCommand(
                fixture.roomId,
                "u_target",
                "u_owner",
                "blocked"
        )))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.USER_MUTED);
    }

    @Test
    void kickMemberClearsRoomStateNotifiesTargetAndAllowsRejoin() {
        Fixture fixture = new Fixture(1717303200000L);
        fixture.addMember("u_owner", "Owner", true, UserStatus.ONLINE, true);
        fixture.addMember("u_target", "Target", false, UserStatus.RECONNECTING, false);
        fixture.userSessions.saveReconnectingUser("u_target", 1717303199000L);
        LocalTestWebSocketSession ownerSession = fixture.registerSession("u_owner");
        LocalTestWebSocketSession targetSession = fixture.registerSession("u_target");

        KickMemberResult result = fixture.governanceService.kickMember(
                fixture.roomId,
                "u_target",
                new KickMemberCommand("u_owner", "cleanup")
        );

        assertThat(result.kicked()).isTrue();
        assertThat(result.roomStatus()).isEqualTo(RoomStatus.ACTIVE);
        assertThat(fixture.members.findMember(fixture.roomId, "u_target")).isEmpty();
        assertThat(fixture.userSessions.findById("u_target").orElseThrow().currentRoomId()).isNull();
        assertThat(fixture.userSessions.isReconnectingUser("u_target")).isFalse();
        assertThat(fixture.events.listEvents(fixture.roomId)).extracting(RoomEvent::type).containsExactly(RoomEventType.USER_KICKED);
        assertThat(fixture.messages.listMessages(fixture.roomId)).singleElement().satisfies(message -> {
            assertThat(message.sourceEventType()).isEqualTo(RoomEventType.USER_KICKED);
            assertThat(message.visibleTo()).containsExactly("u_owner", "u_target");
        });
        assertThat(ownerSession.textMessages()).hasSize(2);
        assertThat(targetSession.textMessages()).hasSize(2);
        assertThat(targetSession.textMessages().get(0).getPayload()).contains("USER_KICKED");
        assertThat(targetSession.textMessages().get(1).getPayload()).contains("MESSAGE_CREATED").contains("kicked");

        JoinRoomView rejoined = fixture.roomService.joinRoom(fixture.roomId, new JoinRoomCommand("u_target", null));
        assertThat(rejoined.member().userId()).isEqualTo("u_target");
    }

    @Test
    void banMemberRemovesPresentTargetAndPreventsFutureJoinIncludingRepeatBan() {
        Fixture fixture = new Fixture(1717303300000L);
        fixture.addMember("u_owner", "Owner", true, UserStatus.ONLINE, true);
        fixture.addMember("u_target", "Target", false, UserStatus.ONLINE, true);
        LocalTestWebSocketSession targetSession = fixture.registerSession("u_target");

        BanMemberResult first = fixture.governanceService.banMember(
                fixture.roomId,
                "u_target",
                new BanMemberCommand("u_owner", "rule_violation")
        );

        assertThat(first.banned()).isTrue();
        assertThat(first.kicked()).isTrue();
        assertThat(fixture.governanceRepository.hasBanIndexEntry(fixture.roomId, "u_target")).isTrue();
        assertThat(fixture.userSessions.findById("u_target").orElseThrow().currentRoomId()).isNull();
        assertThat(fixture.members.findMember(fixture.roomId, "u_target")).isEmpty();
        assertThat(fixture.events.listEvents(fixture.roomId)).extracting(RoomEvent::type).containsExactly(RoomEventType.USER_BANNED);
        assertThat(fixture.messages.listMessages(fixture.roomId)).singleElement().satisfies(message -> {
            assertThat(message.sourceEventType()).isEqualTo(RoomEventType.USER_BANNED);
            assertThat(message.visibleTo()).containsExactly("u_owner", "u_target");
        });
        assertThat(targetSession.textMessages()).hasSize(2);
        assertThat(targetSession.textMessages().get(0).getPayload()).contains("USER_BANNED");

        assertThatThrownBy(() -> fixture.roomService.joinRoom(fixture.roomId, new JoinRoomCommand("u_target", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.USER_BANNED);

        BanMemberResult second = fixture.governanceService.banMember(
                fixture.roomId,
                "u_target",
                new BanMemberCommand("u_owner", "repeat")
        );
        assertThat(second.banned()).isTrue();
        assertThat(second.kicked()).isFalse();
        assertThat(fixture.events.listEvents(fixture.roomId)).extracting(RoomEvent::type)
                .containsExactly(RoomEventType.USER_BANNED, RoomEventType.USER_BANNED);
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private static final class Fixture {
        private final String roomId = "r_room";
        private final InMemoryUserSessionRepository userSessions = new InMemoryUserSessionRepository();
        private final InMemoryRoomRepository rooms = new InMemoryRoomRepository();
        private final InMemoryRoomMemberRepository members = new InMemoryRoomMemberRepository();
        private final InMemoryRoomIndexRepository indexes = new InMemoryRoomIndexRepository();
        private final InMemoryGovernanceRepository governanceRepository = new InMemoryGovernanceRepository();
        private final InMemoryMessageRepository messages = new InMemoryMessageRepository();
        private final InMemoryRoomEventRepository events = new InMemoryRoomEventRepository();
        private final RoomWebSocketConnectionRegistry registry = new RoomWebSocketConnectionRegistry();
        private final MutableTimeProvider timeProvider;
        private final RoomEventService roomEventService;
        private final GovernanceService governanceService;
        private final MessageService messageService;
        private final RoomService roomService;

        private Fixture(long nowMillis) {
            this.timeProvider = new MutableTimeProvider(nowMillis);
            DrrrProperties properties = new DrrrProperties();
            rooms.save(new Room(
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
                    nowMillis,
                    nowMillis,
                    null
            ));
            indexes.zAdd(RoomIndexKey.ACTIVE, roomId, nowMillis);
            RoomMessagePersistence roomMessagePersistence = new RoomMessagePersistence(messages, rooms, indexes);
            roomEventService = new RoomEventService(
                    events,
                    members,
                    roomMessagePersistence,
                    new RoomWebSocketOperations(registry, new JsonCodec(new ObjectMapper())),
                    new FixedIdGenerator(),
                    timeProvider,
                    new ObjectMapper()
            );
            UserSessionService userSessionService = new UserSessionService(
                    userSessions,
                    new NoOpLobbyRepository(),
                    rooms,
                    members,
                    roomEventService,
                    new FixedIdGenerator(),
                    timeProvider,
                    properties
            );
            governanceService = new GovernanceService(
                    userSessions,
                    rooms,
                    members,
                    indexes,
                    governanceRepository,
                    new OwnerTransferService(),
                    roomEventService,
                    timeProvider,
                    properties,
                    new JvmRoomLock()
            );
            messageService = new MessageService(
                    userSessionService,
                    members,
                    governanceRepository,
                    messages,
                    roomMessagePersistence,
                    new FixedIdGenerator(),
                    timeProvider,
                    new JvmRoomLock()
            );
            roomService = new RoomService(
                    userSessions,
                    rooms,
                    members,
                    indexes,
                    governanceRepository,
                    new OwnerTransferService(),
                    roomEventService,
                    new RoomPasswordHasher(),
                    new FixedIdGenerator(),
                    timeProvider,
                    properties,
                    new JvmRoomLock()
            );
        }

        private void addMember(String userId, String nickname, boolean owner, UserStatus status, boolean connected) {
            long now = timeProvider.nowMillis();
            userSessions.save(new UserSession(
                    userId,
                    nickname,
                    roomId,
                    status,
                    connected,
                    now,
                    status == UserStatus.RECONNECTING ? now - 1_000L : null,
                    now,
                    now
            ));
            members.save(new RoomMember(
                    roomId,
                    userId,
                    nickname,
                    status == UserStatus.RECONNECTING ? MemberStatus.RECONNECTING : MemberStatus.ONLINE,
                    now,
                    now,
                    owner
            ));
        }

        private LocalTestWebSocketSession registerSession(String userId) {
            LocalTestWebSocketSession session = new LocalTestWebSocketSession(
                    "s-" + userId,
                    URI.create("ws://localhost/ws/rooms/" + roomId + "?userId=" + userId)
            );
            registry.register(roomId, userId, session);
            return session;
        }
    }

    private static final class InMemoryUserSessionRepository extends UserSessionRepository {
        private final Map<String, UserSession> storage = new ConcurrentHashMap<>();
        private final Set<String> reconnectingUsers = ConcurrentHashMap.newKeySet();

        private InMemoryUserSessionRepository() {
            super(null);
        }

        @Override public void save(UserSession userSession) { storage.put(userSession.userId(), userSession); }
        @Override public Optional<UserSession> findById(String userId) { return Optional.ofNullable(storage.get(userId)); }
        @Override public void saveReconnectingUser(String userId, long lastDisconnectedAt) { reconnectingUsers.add(userId); }
        @Override public boolean isReconnectingUser(String userId) { return reconnectingUsers.contains(userId); }
        @Override public void removeReconnectingUser(String userId) { reconnectingUsers.remove(userId); }
    }

    private static final class InMemoryRoomRepository extends RoomRepository {
        private final Map<String, Room> storage = new ConcurrentHashMap<>();
        private InMemoryRoomRepository() { super(null); }
        @Override public void save(Room room) { storage.put(room.roomId(), room); }
        @Override public Optional<Room> findById(String roomId) { return Optional.ofNullable(storage.get(roomId)); }
    }

    private static final class InMemoryRoomMemberRepository extends RoomMemberRepository {
        private final Map<String, Map<String, RoomMember>> storage = new ConcurrentHashMap<>();
        private InMemoryRoomMemberRepository() { super(null); }
        @Override public void save(RoomMember roomMember) { storage.computeIfAbsent(roomMember.roomId(), ignored -> new ConcurrentHashMap<>()).put(roomMember.userId(), roomMember); }
        @Override public Optional<RoomMember> findMember(String roomId, String userId) { return Optional.ofNullable(storage.getOrDefault(roomId, Map.of()).get(userId)); }
        @Override public boolean existsMemberOrder(String roomId, String userId) { return storage.getOrDefault(roomId, Map.of()).containsKey(userId); }
        @Override public List<RoomMember> listMembers(String roomId) { return storage.getOrDefault(roomId, Map.of()).values().stream().sorted(Comparator.comparingLong(RoomMember::joinedAt).thenComparing(RoomMember::userId)).toList(); }
        @Override public long countMembers(String roomId) { return storage.getOrDefault(roomId, Map.of()).size(); }
        @Override public void removeMember(String roomId, String userId) { Map<String, RoomMember> members = storage.get(roomId); if (members != null) { members.remove(userId); } }
    }

    private static final class InMemoryRoomIndexRepository extends RoomIndexRepository {
        private final Map<RoomIndexKey, Map<String, Double>> indexes = new ConcurrentHashMap<>();
        private InMemoryRoomIndexRepository() { super(null); }
        @Override public void zAdd(RoomIndexKey indexKey, String member, double score) { indexes.computeIfAbsent(indexKey, ignored -> new ConcurrentHashMap<>()).put(member, score); }
        @Override public void zRem(RoomIndexKey indexKey, String member) { indexes.computeIfAbsent(indexKey, ignored -> new ConcurrentHashMap<>()).remove(member); }
        @Override public Set<String> zRange(RoomIndexKey indexKey, long start, long end) { return indexes.getOrDefault(indexKey, Map.of()).entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry::getKey)).collect(LinkedHashSet::new, (set, entry) -> set.add(entry.getKey()), Set::addAll); }
    }

    private static final class InMemoryGovernanceRepository extends GovernanceRepository {
        private final Map<String, com.boot.drrr.domain.governance.MuteRecord> mutes = new ConcurrentHashMap<>();
        private final Map<String, com.boot.drrr.domain.governance.BanRecord> bans = new ConcurrentHashMap<>();
        private InMemoryGovernanceRepository() { super(null); }
        @Override public void saveMuteRecord(com.boot.drrr.domain.governance.MuteRecord muteRecord) { mutes.put(key(muteRecord.roomId(), muteRecord.userId()), muteRecord); }
        @Override public Optional<com.boot.drrr.domain.governance.MuteRecord> findMuteRecord(String roomId, String userId) { return Optional.ofNullable(mutes.get(key(roomId, userId))); }
        @Override public boolean hasMuteIndexEntry(String roomId, String userId) { return mutes.containsKey(key(roomId, userId)); }
        @Override public void clearMute(String roomId, String userId) { mutes.remove(key(roomId, userId)); }
        @Override public void saveBanRecord(com.boot.drrr.domain.governance.BanRecord banRecord) { bans.put(key(banRecord.roomId(), banRecord.userId()), banRecord); }
        @Override public Optional<com.boot.drrr.domain.governance.BanRecord> findBanRecord(String roomId, String userId) { return Optional.ofNullable(bans.get(key(roomId, userId))); }
        @Override public boolean hasBanIndexEntry(String roomId, String userId) { return bans.containsKey(key(roomId, userId)); }
        private String key(String roomId, String userId) { return roomId + ":" + userId; }
    }

    private static final class InMemoryMessageRepository extends MessageRepository {
        private final Map<String, List<Message>> storage = new ConcurrentHashMap<>();
        private InMemoryMessageRepository() { super(null); }
        @Override public void append(Message message) { storage.computeIfAbsent(message.roomId(), ignored -> new ArrayList<>()).add(message); }
        @Override public List<Message> listMessages(String roomId) { return List.copyOf(storage.getOrDefault(roomId, List.of())); }
        @Override public void trim(String roomId, long start, long end) { List<Message> values = storage.get(roomId); if (values == null || values.isEmpty()) { return; } int fromIndex = (int) Math.max(0, start); int toIndexExclusive = end < 0 ? values.size() : (int) Math.min(values.size(), end + 1); storage.put(roomId, new ArrayList<>(values.subList(fromIndex, toIndexExclusive))); }
        @Override public void deleteAll(String roomId) { storage.remove(roomId); }
    }

    private static final class InMemoryRoomEventRepository extends RoomEventRepository {
        private final Map<String, List<RoomEvent>> storage = new ConcurrentHashMap<>();
        private InMemoryRoomEventRepository() { super(null); }
        @Override public void append(RoomEvent roomEvent) { storage.computeIfAbsent(roomEvent.roomId(), ignored -> new ArrayList<>()).add(roomEvent); }
        @Override public List<RoomEvent> listEvents(String roomId) { return List.copyOf(storage.getOrDefault(roomId, List.of())); }
    }

    private static final class NoOpLobbyRepository extends LobbyRepository {
        private NoOpLobbyRepository() { super(null); }
        @Override public void zAdd(String userId, double score) { }
    }

    private static final class FixedIdGenerator implements IdGenerator {
        private int roomCounter = 1;
        private int messageCounter = 1;
        private int eventCounter = 1;
        @Override public String newUserId() { return "u_generated"; }
        @Override public String newRoomId() { return "r_" + roomCounter++; }
        @Override public String newMessageId() { return "m_" + messageCounter++; }
        @Override public String newEventId() { return "e_" + eventCounter++; }
    }

    private static final class MutableTimeProvider implements TimeProvider {
        private final long nowMillis;
        private MutableTimeProvider(long nowMillis) { this.nowMillis = nowMillis; }
        @Override public Instant now() { return Instant.ofEpochMilli(nowMillis); }
        @Override public long nowMillis() { return nowMillis; }
        @Override public Clock clock() { return Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC); }
    }

    private static final class LocalTestWebSocketSession implements WebSocketSession {
        private final String id;
        private final URI uri;
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<TextMessage> textMessages = new ArrayList<>();
        private boolean open = true;
        private LocalTestWebSocketSession(String id, URI uri) { this.id = id; this.uri = uri; }
        private List<TextMessage> textMessages() { return textMessages; }
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
}
