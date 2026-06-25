package com.boot.drrr.service.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.lock.JvmRoomLock;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
import com.boot.drrr.domain.governance.MuteRecord;
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
import com.boot.drrr.repository.governance.GovernanceRepository;
import com.boot.drrr.repository.message.MessageRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomIndexRepository.RoomIndexKey;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.repository.user.UserSessionRepository;
import com.boot.drrr.service.user.UserSessionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class MessageServiceTest {

    @Test
    void sendPublicMessagePersistsMessageAndRefreshesRoomActivity() {
        Fixture fixture = new Fixture(1717301000000L, HistoryStrategyType.COUNT, 20);
        fixture.addMember("u_alice", "Alice", true);
        fixture.addMember("u_bob", "Bob", false);

        Message message = fixture.service.sendPublicMessage(new SendPublicMessageCommand(
                fixture.roomId,
                "u_alice",
                " hello room "
        ));

        List<Message> storedMessages = fixture.messages.listMessages(fixture.roomId);
        Room updatedRoom = fixture.rooms.findById(fixture.roomId).orElseThrow();

        assertThat(message.type()).isEqualTo(MessageType.PUBLIC);
        assertThat(message.content()).isEqualTo("hello room");
        assertThat(message.senderNickname()).isEqualTo("Alice");
        assertThat(storedMessages).containsExactly(message);
        assertThat(updatedRoom.lastActiveAt()).isEqualTo(1717301000000L);
        assertThat(updatedRoom.status()).isEqualTo(RoomStatus.ACTIVE);
        assertThat(fixture.indexes.zRange(RoomIndexKey.ACTIVE, 0, -1)).contains(fixture.roomId);
        assertThat(fixture.service.readVisibleHistory(fixture.roomId, "u_bob")).containsExactly(message);
    }

    @Test
    void sendDirectMessageStoresSharedFlowAndFiltersVisibilityByViewer() {
        Fixture fixture = new Fixture(1717301100000L, HistoryStrategyType.COUNT, 20);
        fixture.addMember("u_alice", "Alice", true);
        fixture.addMember("u_bob", "Bob", false);
        fixture.addMember("u_claire", "Claire", false);
        fixture.service.sendPublicMessage(new SendPublicMessageCommand(fixture.roomId, "u_alice", "welcome"));

        Message directMessage = fixture.service.sendDirectMessage(new SendDirectMessageCommand(
                fixture.roomId,
                "u_alice",
                "u_bob",
                "private hi"
        ));

        List<Message> senderHistory = fixture.service.readVisibleHistory(fixture.roomId, "u_alice");
        List<Message> targetHistory = fixture.service.readVisibleHistory(fixture.roomId, "u_bob");
        List<Message> outsiderHistory = fixture.service.readVisibleHistory(fixture.roomId, "u_claire");

        assertThat(directMessage.type()).isEqualTo(MessageType.DIRECT);
        assertThat(directMessage.targetNickname()).isEqualTo("Bob");
        assertThat(directMessage.visibleTo()).containsExactly("u_alice", "u_bob");
        assertThat(senderHistory).extracting(Message::messageId).contains(directMessage.messageId());
        assertThat(targetHistory).extracting(Message::messageId).contains(directMessage.messageId());
        assertThat(outsiderHistory).extracting(Message::messageId).doesNotContain(directMessage.messageId());
        assertThat(outsiderHistory).extracting(Message::type).containsExactly(MessageType.PUBLIC);
    }

    @Test
    void mutedSenderCannotSendMessagesUntilMuteExpires() {
        Fixture activeMuteFixture = new Fixture(1717301200000L, HistoryStrategyType.COUNT, 20);
        activeMuteFixture.addMember("u_alice", "Alice", true);
        activeMuteFixture.addMember("u_bob", "Bob", false);
        activeMuteFixture.governance.saveMuteRecord(new MuteRecord(
                activeMuteFixture.roomId,
                "u_alice",
                "u_owner",
                1717301190000L,
                1717301800000L,
                "test"
        ));

        assertThatThrownBy(() -> activeMuteFixture.service.sendPublicMessage(new SendPublicMessageCommand(
                activeMuteFixture.roomId,
                "u_alice",
                "blocked"
        )))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.USER_MUTED);

        Fixture expiredMuteFixture = new Fixture(1717301200000L, HistoryStrategyType.COUNT, 20);
        expiredMuteFixture.addMember("u_alice", "Alice", true);
        expiredMuteFixture.addMember("u_bob", "Bob", false);
        expiredMuteFixture.governance.saveMuteRecord(new MuteRecord(
                expiredMuteFixture.roomId,
                "u_alice",
                "u_owner",
                1717300000000L,
                1717301100000L,
                "expired"
        ));

        Message message = expiredMuteFixture.service.sendDirectMessage(new SendDirectMessageCommand(
                expiredMuteFixture.roomId,
                "u_alice",
                "u_bob",
                "allowed again"
        ));

        assertThat(message.type()).isEqualTo(MessageType.DIRECT);
        assertThat(expiredMuteFixture.governance.hasMuteIndexEntry(expiredMuteFixture.roomId, "u_alice")).isFalse();
    }

    @Test
    void sendDirectMessageRejectsTargetOutsideRoom() {
        Fixture fixture = new Fixture(1717301300000L, HistoryStrategyType.COUNT, 20);
        fixture.addMember("u_alice", "Alice", true);

        assertThatThrownBy(() -> fixture.service.sendDirectMessage(new SendDirectMessageCommand(
                fixture.roomId,
                "u_alice",
                "u_missing",
                "hello"
        )))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.TARGET_NOT_FOUND);
    }

    @Test
    void historyStrategyNoneClearsStoredMessagesAfterSend() {
        Fixture fixture = new Fixture(1717301400000L, HistoryStrategyType.NONE, null);
        fixture.addMember("u_alice", "Alice", true);
        fixture.addMember("u_bob", "Bob", false);

        Message message = fixture.service.sendPublicMessage(new SendPublicMessageCommand(
                fixture.roomId,
                "u_alice",
                "ephemeral"
        ));

        assertThat(message.type()).isEqualTo(MessageType.PUBLIC);
        assertThat(fixture.messages.listMessages(fixture.roomId)).isEmpty();
        assertThat(fixture.service.readVisibleHistory(fixture.roomId, "u_alice")).isEmpty();
    }

    @Test
    void historyStrategyMinutesCropsMessagesOutsideWindow() {
        Fixture fixture = new Fixture(1717301500000L, HistoryStrategyType.MINUTES, 5);
        fixture.addMember("u_alice", "Alice", true);
        fixture.addMember("u_bob", "Bob", false);
        fixture.time.setNowMillis(1717301000000L);
        fixture.service.sendPublicMessage(new SendPublicMessageCommand(fixture.roomId, "u_alice", "old"));

        fixture.time.setNowMillis(1717301500000L);
        Message current = fixture.service.sendPublicMessage(new SendPublicMessageCommand(fixture.roomId, "u_alice", "current"));

        List<Message> storedMessages = fixture.messages.listMessages(fixture.roomId);

        assertThat(storedMessages).containsExactly(current);
        assertThat(fixture.service.readVisibleHistory(fixture.roomId, "u_bob")).containsExactly(current);
    }

    private static final class Fixture {
        private final String roomId = "r_room";
        private final InMemoryUserSessionRepository userSessions = new InMemoryUserSessionRepository();
        private final InMemoryRoomRepository rooms = new InMemoryRoomRepository();
        private final InMemoryRoomMemberRepository members = new InMemoryRoomMemberRepository();
        private final InMemoryGovernanceRepository governance = new InMemoryGovernanceRepository();
        private final InMemoryMessageRepository messages = new InMemoryMessageRepository();
        private final InMemoryRoomIndexRepository indexes = new InMemoryRoomIndexRepository();
        private final MutableTimeProvider time;
        private final MessageService service;
        private final FixedIdGenerator ids = new FixedIdGenerator();

        private Fixture(long nowMillis, HistoryStrategyType historyStrategyType, Integer historyValue) {
            this.time = new MutableTimeProvider(nowMillis);
            DrrrProperties properties = new DrrrProperties();
            UserSessionService userSessionService = new UserSessionService(
                    userSessions,
                    null,
                    rooms,
                    members,
                    ids,
                    time,
                    properties
            );
            this.service = new MessageService(
                    userSessionService,
                    rooms,
                    members,
                    governance,
                    messages,
                    indexes,
                    ids,
                    time,
                    new JvmRoomLock()
            );
            rooms.save(new Room(
                    roomId,
                    "Room",
                    "desc",
                    null,
                    8,
                    "u_alice",
                    "u_alice",
                    RoomStatus.ACTIVE,
                    true,
                    new HistoryStrategy(historyStrategyType, historyValue),
                    true,
                    nowMillis,
                    nowMillis,
                    null
            ));
            indexes.zAdd(RoomIndexKey.ACTIVE, roomId, nowMillis);
        }

        private void addMember(String userId, String nickname, boolean owner) {
            long now = time.nowMillis();
            userSessions.save(new UserSession(
                    userId,
                    nickname,
                    roomId,
                    UserStatus.ONLINE,
                    true,
                    now,
                    null,
                    now,
                    now
            ));
            members.save(new RoomMember(roomId, userId, nickname, MemberStatus.ONLINE, now, now, owner));
        }
    }

    private static final class InMemoryUserSessionRepository extends UserSessionRepository {
        private final Map<String, UserSession> storage = new ConcurrentHashMap<>();

        private InMemoryUserSessionRepository() {
            super(null);
        }

        @Override
        public void save(UserSession userSession) {
            storage.put(userSession.userId(), userSession);
        }

        @Override
        public Optional<UserSession> findById(String userId) {
            return Optional.ofNullable(storage.get(userId));
        }

        @Override
        public void saveReconnectingUser(String userId, long disconnectedAt) {
        }

        @Override
        public void removeReconnectingUser(String userId) {
        }
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
        public boolean existsMemberOrder(String roomId, String userId) {
            return storage.getOrDefault(roomId, Map.of()).containsKey(userId);
        }

        @Override
        public List<RoomMember> listMembers(String roomId) {
            return storage.getOrDefault(roomId, Map.of()).values().stream()
                    .sorted(Comparator.comparingLong(RoomMember::joinedAt).thenComparing(RoomMember::userId))
                    .toList();
        }
    }

    private static final class InMemoryGovernanceRepository extends GovernanceRepository {
        private final Map<String, MuteRecord> mutes = new ConcurrentHashMap<>();

        private InMemoryGovernanceRepository() {
            super(null);
        }

        @Override
        public void saveMuteRecord(MuteRecord muteRecord) {
            mutes.put(key(muteRecord.roomId(), muteRecord.userId()), muteRecord);
        }

        @Override
        public Optional<MuteRecord> findMuteRecord(String roomId, String userId) {
            return Optional.ofNullable(mutes.get(key(roomId, userId)));
        }

        @Override
        public boolean hasMuteIndexEntry(String roomId, String userId) {
            return mutes.containsKey(key(roomId, userId));
        }

        @Override
        public void clearMute(String roomId, String userId) {
            mutes.remove(key(roomId, userId));
        }

        private String key(String roomId, String userId) {
            return roomId + ":" + userId;
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
            List<Message> messages = storage.get(roomId);
            if (messages == null || messages.isEmpty()) {
                return;
            }
            int fromIndex = (int) Math.max(0, start);
            int toIndexExclusive = (int) Math.min(messages.size(), end + 1);
            if (fromIndex >= toIndexExclusive) {
                storage.remove(roomId);
                return;
            }
            storage.put(roomId, new ArrayList<>(messages.subList(fromIndex, toIndexExclusive)));
        }

        @Override
        public void deleteAll(String roomId) {
            storage.remove(roomId);
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

        @Override
        public Set<String> zRange(RoomIndexKey indexKey, long start, long end) {
            return indexes.getOrDefault(indexKey, Map.of()).entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry::getKey))
                    .collect(LinkedHashSet::new, (set, entry) -> set.add(entry.getKey()), Set::addAll);
        }
    }

    private static final class FixedIdGenerator implements IdGenerator {
        private int nextId = 1;

        @Override
        public String newUserId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String newRoomId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String newMessageId() {
            return "m_" + nextId++;
        }

        @Override
        public String newEventId() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MutableTimeProvider implements TimeProvider {
        private long nowMillis;

        private MutableTimeProvider(long nowMillis) {
            this.nowMillis = nowMillis;
        }

        private void setNowMillis(long nowMillis) {
            this.nowMillis = nowMillis;
        }

        @Override
        public Instant now() {
            return Instant.ofEpochMilli(nowMillis);
        }

        @Override
        public long nowMillis() {
            return nowMillis;
        }

        @Override
        public Clock clock() {
            return Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
        }
    }
}
