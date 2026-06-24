package com.boot.drrr.service.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.lock.JvmRoomLock;
import com.boot.drrr.common.lock.RoomLock;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
import com.boot.drrr.domain.governance.BanRecord;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;
import com.boot.drrr.repository.governance.GovernanceRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomIndexRepository.RoomIndexKey;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.repository.user.UserSessionRepository;
import com.boot.drrr.service.owner.OwnerTransferService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoomServiceTest {

    @Test
    void createRoomPersistsRoomCreatorAndActiveIndex() {
        Fixture fixture = new Fixture(1717300200000L);
        fixture.userSessions.save(fixture.user("u_alice", "Alice", null));

        CreateRoomView created = fixture.service.createRoom(new CreateRoomCommand(
                "u_alice",
                " Night Talk ",
                " anonymous chat ",
                "secret",
                6,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                true
        ));

        Room room = fixture.rooms.findById(created.room().roomId()).orElseThrow();
        RoomMember creator = fixture.members.findMember(created.room().roomId(), "u_alice").orElseThrow();
        UserSession updated = fixture.userSessions.findById("u_alice").orElseThrow();

        assertThat(room.name()).isEqualTo("Night Talk");
        assertThat(room.description()).isEqualTo("anonymous chat");
        assertThat(room.passwordHash()).isNotBlank().isNotEqualTo("secret");
        assertThat(room.ownerUserId()).isEqualTo("u_alice");
        assertThat(room.initialOwnerUserId()).isEqualTo("u_alice");
        assertThat(room.status()).isEqualTo(RoomStatus.ACTIVE);
        assertThat(creator.isOwner()).isTrue();
        assertThat(updated.currentRoomId()).isEqualTo(room.roomId());
        assertThat(fixture.indexes.zRange(RoomIndexKey.ACTIVE, 0, -1)).contains(room.roomId());
        assertThat(fixture.indexes.zRange(RoomIndexKey.EMPTY, 0, -1)).doesNotContain(room.roomId());
    }

    @Test
    void createRoomClearsStaleRoomReferenceBeforeInRoomValidation() {
        Fixture fixture = new Fixture(1717300250000L);
        fixture.userSessions.save(fixture.user("u_alice", "Alice", "r_stale"));
        fixture.rooms.save(new Room(
                "r_stale",
                "Stale",
                "desc",
                null,
                6,
                "u_someone",
                "u_someone",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                true,
                1717300000000L,
                1717300000000L,
                null
        ));

        CreateRoomView created = fixture.service.createRoom(new CreateRoomCommand(
                "u_alice",
                "Fresh Room",
                "desc",
                null,
                6,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                true
        ));

        UserSession updated = fixture.userSessions.findById("u_alice").orElseThrow();
        assertThat(created.room().roomId()).isEqualTo("r_generated");
        assertThat(updated.currentRoomId()).isEqualTo("r_generated");
    }

    @Test
    void joinRoomAddsMemberAndReactivatesEmptyRoom() {
        Fixture fixture = new Fixture(1717300300000L);
        fixture.userSessions.save(fixture.user("u_owner", "Owner", null));
        fixture.userSessions.save(fixture.user("u_bob", "Bob", null));
        String roomId = fixture.createRoomFor("r_001", "u_owner", "Owner", RoomStatus.EMPTY, 1717300000000L, 1717300000000L, "pw");

        JoinRoomView joined = fixture.service.joinRoom(roomId, new JoinRoomCommand("u_bob", "pw"));

        Room room = fixture.rooms.findById(roomId).orElseThrow();
        UserSession bob = fixture.userSessions.findById("u_bob").orElseThrow();

        assertThat(joined.member().userId()).isEqualTo("u_bob");
        assertThat(joined.members()).extracting(RoomMember::userId).containsExactly("u_owner", "u_bob");
        assertThat(room.status()).isEqualTo(RoomStatus.ACTIVE);
        assertThat(room.emptySince()).isNull();
        assertThat(room.initialOwnerUserId()).isEqualTo("u_owner");
        assertThat(bob.currentRoomId()).isEqualTo(roomId);
        assertThat(fixture.indexes.zRange(RoomIndexKey.EMPTY, 0, -1)).doesNotContain(roomId);
    }

    @Test
    void joinRoomRejectsInvalidPassword() {
        Fixture fixture = new Fixture(1717300300000L);
        fixture.userSessions.save(fixture.user("u_owner", "Owner", null));
        fixture.userSessions.save(fixture.user("u_bob", "Bob", null));
        String roomId = fixture.createRoomFor("r_001", "u_owner", "Owner", RoomStatus.ACTIVE, null, 1717300000000L, "pw");

        assertThatThrownBy(() -> fixture.service.joinRoom(roomId, new JoinRoomCommand("u_bob", "wrong")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_INVALID);
    }

    @Test
    void joinRoomRejectsDuplicateNickname() {
        Fixture fixture = new Fixture(1717300300000L);
        fixture.userSessions.save(fixture.user("u_owner", "Alice", null));
        fixture.userSessions.save(fixture.user("u_bob", "Alice", null));
        String roomId = fixture.createRoomFor("r_001", "u_owner", "Alice", RoomStatus.ACTIVE, null, 1717300000000L, null);

        assertThatThrownBy(() -> fixture.service.joinRoom(roomId, new JoinRoomCommand("u_bob", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.NICKNAME_DUPLICATED);
    }

    @Test
    void joinRoomRejectsBannedUserFullRoomAndExpiredRoom() {
        Fixture bannedFixture = new Fixture(1717300300000L);
        bannedFixture.userSessions.save(bannedFixture.user("u_owner", "Owner", null));
        bannedFixture.userSessions.save(bannedFixture.user("u_bob", "Bob", null));
        String bannedRoomId = bannedFixture.createRoomFor("r_001", "u_owner", "Owner", RoomStatus.ACTIVE, null, 1717300000000L, null);
        bannedFixture.governance.saveBanRecord(new BanRecord(bannedRoomId, "u_bob", "u_owner", 1717300100000L, "test"));

        assertThatThrownBy(() -> bannedFixture.service.joinRoom(bannedRoomId, new JoinRoomCommand("u_bob", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.USER_BANNED);

        Fixture fullFixture = new Fixture(1717300300000L);
        fullFixture.properties.getRoom().setMaxMembersMax(2);
        fullFixture.userSessions.save(fullFixture.user("u_owner", "Owner", null));
        fullFixture.userSessions.save(fullFixture.user("u_a", "A", null));
        fullFixture.userSessions.save(fullFixture.user("u_b", "B", null));
        String fullRoomId = fullFixture.createRoomFor("r_001", "u_owner", "Owner", RoomStatus.ACTIVE, null, 1717300000000L, null, 2);
        fullFixture.members.save(new RoomMember(fullRoomId, "u_a", "A", MemberStatus.ONLINE, 1717300001000L, 1717300001000L, false));

        assertThatThrownBy(() -> fullFixture.service.joinRoom(fullRoomId, new JoinRoomCommand("u_b", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.ROOM_FULL);

        Fixture expiredFixture = new Fixture(1717300300000L);
        expiredFixture.userSessions.save(expiredFixture.user("u_owner", "Owner", null));
        expiredFixture.userSessions.save(expiredFixture.user("u_bob", "Bob", null));
        expiredFixture.properties.getRoom().setEmptyExpiry(Duration.ofMinutes(10));
        String expiredRoomId = expiredFixture.createRoomFor("r_001", "u_owner", "Owner", RoomStatus.EMPTY, 1717299000000L, 1717299000000L, null);

        assertThatThrownBy(() -> expiredFixture.service.joinRoom(expiredRoomId, new JoinRoomCommand("u_bob", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.ROOM_EXPIRED);
    }

    @Test
    void joinRoomPreventsSameUserFromEnteringTwoRoomsConcurrently() throws Exception {
        CoordinatedRoomMemberRepository coordinatedMembers = new CoordinatedRoomMemberRepository();
        Fixture fixture = new Fixture(1717300350000L, coordinatedMembers, new JvmRoomLock());
        fixture.userSessions.save(fixture.user("u_owner_1", "Owner1", null));
        fixture.userSessions.save(fixture.user("u_owner_2", "Owner2", null));
        fixture.userSessions.save(fixture.user("u_alice", "Alice", null));
        fixture.createRoomFor("r_001", "u_owner_1", "Owner1", RoomStatus.ACTIVE, null, 1717300000000L, null);
        fixture.createRoomFor("r_002", "u_owner_2", "Owner2", RoomStatus.ACTIVE, null, 1717300000000L, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> attemptJoin(fixture, "r_001", "u_alice"));
            Future<Object> second = executor.submit(() -> attemptJoin(fixture, "r_002", "u_alice"));

            List<Object> outcomes = List.of(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
            long successCount = outcomes.stream().filter(JoinRoomView.class::isInstance).count();
            List<ErrorCode> errorCodes = outcomes.stream()
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .map(BusinessException::getErrorCode)
                    .toList();

            assertThat(successCount).isEqualTo(1);
            assertThat(errorCodes).containsExactly(ErrorCode.USER_ALREADY_IN_ROOM);

            UserSession alice = fixture.userSessions.findById("u_alice").orElseThrow();
            assertThat(alice.currentRoomId()).isIn("r_001", "r_002");
            int joinedRoomCount = (fixture.members.findMember("r_001", "u_alice").isPresent() ? 1 : 0)
                    + (fixture.members.findMember("r_002", "u_alice").isPresent() ? 1 : 0);
            assertThat(joinedRoomCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void leaveRoomTransfersOwnerToEarliestJoinedMember() {
        Fixture fixture = new Fixture(1717300400000L);
        fixture.userSessions.save(fixture.user("u_owner", "Owner", "r_001"));
        fixture.userSessions.save(fixture.user("u_bob", "Bob", "r_001"));
        fixture.userSessions.save(fixture.user("u_claire", "Claire", "r_001"));
        fixture.rooms.save(new Room(
                "r_001",
                "Night Talk",
                "desc",
                null,
                6,
                "u_owner",
                "u_owner",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                true,
                1717300000000L,
                1717300000000L,
                null
        ));
        fixture.members.save(new RoomMember("r_001", "u_owner", "Owner", MemberStatus.ONLINE, 1717300000000L, 1717300000000L, true));
        fixture.members.save(new RoomMember("r_001", "u_bob", "Bob", MemberStatus.ONLINE, 1717300001000L, 1717300001000L, false));
        fixture.members.save(new RoomMember("r_001", "u_claire", "Claire", MemberStatus.ONLINE, 1717300002000L, 1717300002000L, false));

        LeaveRoomResult result = fixture.service.leaveRoom("r_001", new LeaveRoomCommand("u_owner"));

        Room room = fixture.rooms.findById("r_001").orElseThrow();
        RoomMember bob = fixture.members.findMember("r_001", "u_bob").orElseThrow();
        RoomMember claire = fixture.members.findMember("r_001", "u_claire").orElseThrow();
        UserSession owner = fixture.userSessions.findById("u_owner").orElseThrow();

        assertThat(result.ownerTransferred()).isTrue();
        assertThat(result.newOwnerUserId()).isEqualTo("u_bob");
        assertThat(room.ownerUserId()).isEqualTo("u_bob");
        assertThat(room.initialOwnerUserId()).isEqualTo("u_owner");
        assertThat(bob.isOwner()).isTrue();
        assertThat(claire.isOwner()).isFalse();
        assertThat(owner.currentRoomId()).isNull();
    }

    @Test
    void leaveRoomMarksRoomEmptyAndWritesEmptyIndex() {
        Fixture fixture = new Fixture(1717300500000L);
        fixture.userSessions.save(fixture.user("u_owner", "Owner", "r_001"));
        fixture.rooms.save(new Room(
                "r_001",
                "Night Talk",
                "desc",
                null,
                6,
                "u_owner",
                "u_owner",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                true,
                1717300000000L,
                1717300000000L,
                null
        ));
        fixture.members.save(new RoomMember("r_001", "u_owner", "Owner", MemberStatus.ONLINE, 1717300000000L, 1717300000000L, true));

        LeaveRoomResult result = fixture.service.leaveRoom("r_001", new LeaveRoomCommand("u_owner"));

        Room room = fixture.rooms.findById("r_001").orElseThrow();
        assertThat(result.roomStatus()).isEqualTo(RoomStatus.EMPTY);
        assertThat(room.status()).isEqualTo(RoomStatus.EMPTY);
        assertThat(room.emptySince()).isEqualTo(1717300500000L);
        assertThat(fixture.indexes.zRange(RoomIndexKey.EMPTY, 0, -1)).contains("r_001");
        assertThat(fixture.members.findMember("r_001", "u_owner")).isEmpty();
    }

    @Test
    void updateRoomRepairsMissingOwnerInvariantBeforePermissionCheck() {
        Fixture fixture = new Fixture(1717300550000L);
        fixture.userSessions.save(fixture.user("u_bob", "Bob", "r_001"));
        fixture.userSessions.save(fixture.user("u_claire", "Claire", "r_001"));
        fixture.rooms.save(new Room(
                "r_001",
                "Night Talk",
                "desc",
                null,
                6,
                "u_missing",
                "u_bob",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                true,
                1717300000000L,
                1717300000000L,
                null
        ));
        fixture.members.save(new RoomMember("r_001", "u_bob", "Bob", MemberStatus.ONLINE, 1717300000000L, 1717300000000L, false));
        fixture.members.save(new RoomMember("r_001", "u_claire", "Claire", MemberStatus.ONLINE, 1717300001000L, 1717300001000L, false));

        UpdateRoomView updated = fixture.service.updateRoom("r_001", new UpdateRoomCommand(
                "u_bob",
                " Repaired Room ",
                " fixed desc ",
                false,
                new HistoryStrategy(HistoryStrategyType.MINUTES, 30),
                false
        ));

        Room repairedRoom = fixture.rooms.findById("r_001").orElseThrow();
        RoomMember bob = fixture.members.findMember("r_001", "u_bob").orElseThrow();
        RoomMember claire = fixture.members.findMember("r_001", "u_claire").orElseThrow();

        assertThat(updated.room().ownerUserId()).isEqualTo("u_bob");
        assertThat(repairedRoom.ownerUserId()).isEqualTo("u_bob");
        assertThat(repairedRoom.initialOwnerUserId()).isEqualTo("u_bob");
        assertThat(bob.isOwner()).isTrue();
        assertThat(claire.isOwner()).isFalse();
        assertThat(updated.room().name()).isEqualTo("Repaired Room");
    }

    @Test
    void updateRoomAllowsInitialOwnerToPatchMetadataWhenConfigChangeLocked() {
        Fixture fixture = new Fixture(1717300600000L);
        fixture.userSessions.save(fixture.user("u_owner", "Owner", "r_001"));
        fixture.rooms.save(new Room(
                "r_001",
                "Old Name",
                "Old desc",
                null,
                6,
                "u_owner",
                "u_owner",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                false,
                1717300000000L,
                1717300000000L,
                null
        ));
        fixture.members.save(new RoomMember("r_001", "u_owner", "Owner", MemberStatus.ONLINE, 1717300000000L, 1717300000000L, true));

        UpdateRoomView updated = fixture.service.updateRoom("r_001", new UpdateRoomCommand(
                "u_owner",
                " New Name ",
                " new desc ",
                false,
                new HistoryStrategy(HistoryStrategyType.MINUTES, 30),
                false
        ));

        assertThat(updated.room().name()).isEqualTo("New Name");
        assertThat(updated.room().description()).isEqualTo("new desc");
        assertThat(updated.room().userListVisible()).isFalse();
        assertThat(updated.room().historyStrategy()).isEqualTo(new HistoryStrategy(HistoryStrategyType.MINUTES, 30));
        assertThat(updated.room().allowOwnerConfigChange()).isFalse();
    }

    @Test
    void updateRoomAllowsInheritedOwnerWhenConfigChangeEnabled() {
        Fixture fixture = new Fixture(1717300605000L);
        fixture.userSessions.save(fixture.user("u_bob", "Bob", "r_001"));
        fixture.rooms.save(new Room(
                "r_001",
                "Old Name",
                "Old desc",
                null,
                6,
                "u_bob",
                "u_owner",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                true,
                1717300000000L,
                1717300000000L,
                null
        ));
        fixture.members.save(new RoomMember("r_001", "u_bob", "Bob", MemberStatus.ONLINE, 1717300001000L, 1717300001000L, true));

        UpdateRoomView updated = fixture.service.updateRoom("r_001", new UpdateRoomCommand(
                "u_bob",
                " Inherited Name ",
                " new desc ",
                false,
                new HistoryStrategy(HistoryStrategyType.MINUTES, 30),
                true
        ));

        assertThat(updated.room().name()).isEqualTo("Inherited Name");
        assertThat(updated.room().initialOwnerUserId()).isEqualTo("u_owner");
    }

    @Test
    void updateRoomRejectsInheritedOwnerWhenConfigChangeLocked() {
        Fixture fixture = new Fixture(1717300610000L);
        fixture.userSessions.save(fixture.user("u_bob", "Bob", "r_001"));
        fixture.rooms.save(new Room(
                "r_001",
                "Old Name",
                "Old desc",
                null,
                6,
                "u_bob",
                "u_owner",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                false,
                1717300000000L,
                1717300000000L,
                null
        ));
        fixture.members.save(new RoomMember("r_001", "u_bob", "Bob", MemberStatus.ONLINE, 1717300001000L, 1717300001000L, true));

        assertThatThrownBy(() -> fixture.service.updateRoom("r_001", new UpdateRoomCommand(
                "u_bob",
                "Denied Name",
                "new desc",
                false,
                new HistoryStrategy(HistoryStrategyType.MINUTES, 30),
                false
        )))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.CONFIG_LOCKED);
    }

    private static Object attemptJoin(Fixture fixture, String roomId, String userId) {
        try {
            return fixture.service.joinRoom(roomId, new JoinRoomCommand(userId, null));
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private static final class Fixture {
        private final InMemoryUserSessionRepository userSessions = new InMemoryUserSessionRepository();
        private final InMemoryRoomRepository rooms = new InMemoryRoomRepository();
        private final InMemoryRoomMemberRepository members;
        private final InMemoryRoomIndexRepository indexes = new InMemoryRoomIndexRepository();
        private final InMemoryGovernanceRepository governance = new InMemoryGovernanceRepository();
        private final DrrrProperties properties = new DrrrProperties();
        private final RoomService service;

        private Fixture(long nowMillis) {
            this(nowMillis, new InMemoryRoomMemberRepository(), new JvmRoomLock());
        }

        private Fixture(long nowMillis, InMemoryRoomMemberRepository members, RoomLock roomLock) {
            this.members = members;
            this.service = new RoomService(
                    userSessions,
                    rooms,
                    this.members,
                    indexes,
                    governance,
                    new OwnerTransferService(),
                    new RoomPasswordHasher(),
                    new FixedIdGenerator(),
                    new FixedTimeProvider(nowMillis),
                    properties,
                    roomLock
            );
        }

        private UserSession user(String userId, String nickname, String currentRoomId) {
            return new UserSession(
                    userId,
                    nickname,
                    currentRoomId,
                    UserStatus.ONLINE,
                    true,
                    1717300000000L,
                    null,
                    1717300000000L,
                    1717300000000L
            );
        }

        private String createRoomFor(
                String roomId,
                String ownerUserId,
                String ownerNickname,
                RoomStatus roomStatus,
                Long emptySince,
                long lastActiveAt,
                String password
        ) {
            return createRoomFor(roomId, ownerUserId, ownerNickname, roomStatus, emptySince, lastActiveAt, password, 6);
        }

        private String createRoomFor(
                String roomId,
                String ownerUserId,
                String ownerNickname,
                RoomStatus roomStatus,
                Long emptySince,
                long lastActiveAt,
                String password,
                int maxMembers
        ) {
            RoomPasswordHasher hasher = new RoomPasswordHasher();
            rooms.save(new Room(
                    roomId,
                    "Night Talk",
                    "desc",
                    hasher.hashNullable(password),
                    maxMembers,
                    ownerUserId,
                    ownerUserId,
                    roomStatus,
                    true,
                    new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                    true,
                    1717300000000L,
                    lastActiveAt,
                    emptySince
            ));
            members.save(new RoomMember(roomId, ownerUserId, ownerNickname, MemberStatus.ONLINE, 1717300000000L, 1717300000000L, true));
            indexes.zAdd(RoomIndexKey.ACTIVE, roomId, lastActiveAt);
            if (roomStatus == RoomStatus.EMPTY && emptySince != null) {
                indexes.zAdd(RoomIndexKey.EMPTY, roomId, emptySince);
            }
            return roomId;
        }
    }

    private static class InMemoryUserSessionRepository extends UserSessionRepository {
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

    private static class InMemoryRoomMemberRepository extends RoomMemberRepository {
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

        @Override
        public long countMembers(String roomId) {
            return storage.getOrDefault(roomId, Map.of()).size();
        }

        @Override
        public void removeMember(String roomId, String userId) {
            Map<String, RoomMember> members = storage.get(roomId);
            if (members != null) {
                members.remove(userId);
            }
        }
    }

    private static final class CoordinatedRoomMemberRepository extends InMemoryRoomMemberRepository {
        private final AtomicInteger countMembersCalls = new AtomicInteger();
        private final CountDownLatch secondCountAttempt = new CountDownLatch(1);

        @Override
        public long countMembers(String roomId) {
            int attempt = countMembersCalls.incrementAndGet();
            if (attempt == 1) {
                try {
                    secondCountAttempt.await(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                }
            } else {
                secondCountAttempt.countDown();
            }
            return super.countMembers(roomId);
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
            return orderedMembers(indexKey, false);
        }

        @Override
        public Set<String> zReverseRange(RoomIndexKey indexKey, long start, long end) {
            return orderedMembers(indexKey, true);
        }

        private Set<String> orderedMembers(RoomIndexKey indexKey, boolean descending) {
            Comparator<Map.Entry<String, Double>> comparator = Map.Entry.comparingByValue();
            if (descending) {
                comparator = comparator.reversed();
            }
            return indexes.getOrDefault(indexKey, Map.of()).entrySet().stream()
                    .sorted(comparator.thenComparing(Map.Entry::getKey))
                    .collect(LinkedHashSet::new, (set, entry) -> set.add(entry.getKey()), Set::addAll);
        }
    }

    private static final class InMemoryGovernanceRepository extends GovernanceRepository {
        private final Set<String> bans = ConcurrentHashMap.newKeySet();

        private InMemoryGovernanceRepository() {
            super(null);
        }

        @Override
        public void saveBanRecord(BanRecord banRecord) {
            bans.add(banRecord.roomId() + ":" + banRecord.userId());
        }

        @Override
        public boolean hasBanIndexEntry(String roomId, String userId) {
            return bans.contains(roomId + ":" + userId);
        }
    }

    private static final class FixedIdGenerator implements IdGenerator {
        @Override
        public String newUserId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String newRoomId() {
            return "r_generated";
        }

        @Override
        public String newMessageId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String newEventId() {
            throw new UnsupportedOperationException();
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
}

