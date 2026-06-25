package com.boot.drrr.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
import com.boot.drrr.domain.event.RoomEvent;
import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;
import com.boot.drrr.repository.lobby.LobbyRepository;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.repository.user.UserSessionRepository;
import com.boot.drrr.service.event.RoomEventService;
import com.boot.drrr.ws.RoomWebSocketConnectionRegistry;
import com.boot.drrr.ws.RoomWebSocketOperations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserSessionServiceTest {

    @Test
    void createAnonymousSessionPersistsSessionAndLobbyActivity() {
        RecordingUserSessionRepository userSessionRepository = new RecordingUserSessionRepository();
        RecordingLobbyRepository lobbyRepository = new RecordingLobbyRepository();
        UserSessionService service = new UserSessionService(
                userSessionRepository,
                lobbyRepository,
                new RecordingRoomRepository(),
                new RecordingRoomMemberRepository(),
                new RecordingRoomEventService(),
                new FixedIdGenerator("u_test001"),
                new FixedTimeProvider(1717300200000L),
                new DrrrProperties()
        );

        UserSession created = service.createAnonymousSession(" Alice ");

        assertThat(created).isEqualTo(new UserSession(
                "u_test001",
                "Alice",
                null,
                UserStatus.ONLINE,
                false,
                null,
                null,
                1717300200000L,
                1717300200000L
        ));
        assertThat(userSessionRepository.saved).isEqualTo(created);
        assertThat(lobbyRepository.member).isEqualTo("u_test001");
        assertThat(lobbyRepository.score).isEqualTo(1717300200000L);
    }

    @Test
    void validateRoomConnectionRejectsRoomContextMismatch() {
        RecordingUserSessionRepository userSessionRepository = new RecordingUserSessionRepository();
        userSessionRepository.sessions.put("u-1", new UserSession(
                "u-1",
                "Alice",
                "r-2",
                UserStatus.ONLINE,
                false,
                null,
                null,
                1L,
                2L
        ));
        UserSessionService service = new UserSessionService(
                userSessionRepository,
                new RecordingLobbyRepository(),
                new RecordingRoomRepository(),
                new RecordingRoomMemberRepository(),
                new RecordingRoomEventService(),
                new FixedIdGenerator("unused"),
                new FixedTimeProvider(1717300200000L),
                new DrrrProperties()
        );

        assertThatThrownBy(() -> service.validateRoomConnection("u-1", "r-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ROOM_CONTEXT_MISMATCH);
    }

    @Test
    void markRoomConnectedDoesNotRecordReconnectForFirstWebSocketConnection() {
        RecordingUserSessionRepository userSessionRepository = new RecordingUserSessionRepository();
        userSessionRepository.sessions.put("u-1", new UserSession(
                "u-1",
                "Alice",
                "r-1",
                UserStatus.ONLINE,
                false,
                null,
                null,
                1L,
                2L
        ));
        RecordingRoomRepository roomRepository = new RecordingRoomRepository();
        roomRepository.rooms.put("r-1", new Room(
                "r-1",
                "Room",
                "desc",
                null,
                8,
                "u-1",
                "u-1",
                RoomStatus.ACTIVE,
                true,
                null,
                true,
                1L,
                2L,
                null
        ));
        RecordingRoomMemberRepository roomMemberRepository = new RecordingRoomMemberRepository();
        roomMemberRepository.members.put("r-1:u-1", new RoomMember("r-1", "u-1", "Alice", MemberStatus.ONLINE, 10L, 11L, true));
        RecordingRoomEventService roomEventService = new RecordingRoomEventService();
        UserSessionService service = new UserSessionService(
                userSessionRepository,
                new RecordingLobbyRepository(),
                roomRepository,
                roomMemberRepository,
                roomEventService,
                new FixedIdGenerator("unused"),
                new FixedTimeProvider(1717300200000L),
                new DrrrProperties()
        );

        RoomSessionContext context = service.markRoomConnected("u-1", "r-1");

        assertThat(context.userSession().connected()).isTrue();
        assertThat(userSessionRepository.saved.status()).isEqualTo(UserStatus.ONLINE);
        assertThat(roomMemberRepository.saved.memberStatus()).isEqualTo(MemberStatus.ONLINE);
        assertThat(userSessionRepository.removedReconnectingUserId).isEqualTo("u-1");
        assertThat(roomEventService.reconnectedMember).isNull();
        assertThat(roomEventService.reconnectedAt).isNull();
    }

    @Test
    void markRoomConnectedRecordsReconnectOnlyForRealReconnectState() {
        RecordingUserSessionRepository userSessionRepository = new RecordingUserSessionRepository();
        userSessionRepository.sessions.put("u-1", new UserSession(
                "u-1",
                "Alice",
                "r-1",
                UserStatus.RECONNECTING,
                false,
                111L,
                222L,
                1L,
                2L
        ));
        userSessionRepository.reconnectingUsers.add("u-1");
        RecordingRoomRepository roomRepository = new RecordingRoomRepository();
        roomRepository.rooms.put("r-1", new Room(
                "r-1",
                "Room",
                "desc",
                null,
                8,
                "u-1",
                "u-1",
                RoomStatus.ACTIVE,
                true,
                null,
                true,
                1L,
                2L,
                null
        ));
        RecordingRoomMemberRepository roomMemberRepository = new RecordingRoomMemberRepository();
        roomMemberRepository.members.put("r-1:u-1", new RoomMember("r-1", "u-1", "Alice", MemberStatus.RECONNECTING, 10L, 11L, true));
        RecordingRoomEventService roomEventService = new RecordingRoomEventService();
        UserSessionService service = new UserSessionService(
                userSessionRepository,
                new RecordingLobbyRepository(),
                roomRepository,
                roomMemberRepository,
                roomEventService,
                new FixedIdGenerator("unused"),
                new FixedTimeProvider(1717300200000L),
                new DrrrProperties()
        );

        RoomSessionContext context = service.markRoomConnected("u-1", "r-1");

        assertThat(context.userSession().status()).isEqualTo(UserStatus.ONLINE);
        assertThat(context.roomMember().memberStatus()).isEqualTo(MemberStatus.ONLINE);
        assertThat(userSessionRepository.removedReconnectingUserId).isEqualTo("u-1");
        assertThat(roomEventService.reconnectedMember).isEqualTo(context.roomMember());
        assertThat(roomEventService.reconnectedAt).isEqualTo(1717300200000L);
    }

    @Test
    void markRoomDisconnectedMovesSessionAndMemberIntoReconnectingAndRecordsEvent() {
        RecordingUserSessionRepository userSessionRepository = new RecordingUserSessionRepository();
        userSessionRepository.sessions.put("u-1", new UserSession(
                "u-1",
                "Alice",
                "r-1",
                UserStatus.ONLINE,
                true,
                111L,
                null,
                1L,
                2L
        ));
        RecordingRoomRepository roomRepository = new RecordingRoomRepository();
        roomRepository.rooms.put("r-1", new Room(
                "r-1",
                "Room",
                "desc",
                null,
                8,
                "u-1",
                "u-1",
                RoomStatus.ACTIVE,
                true,
                null,
                true,
                1L,
                2L,
                null
        ));
        RecordingRoomMemberRepository roomMemberRepository = new RecordingRoomMemberRepository();
        roomMemberRepository.members.put("r-1:u-1", new RoomMember("r-1", "u-1", "Alice", MemberStatus.ONLINE, 10L, 11L, true));
        RecordingRoomEventService roomEventService = new RecordingRoomEventService();
        UserSessionService service = new UserSessionService(
                userSessionRepository,
                new RecordingLobbyRepository(),
                roomRepository,
                roomMemberRepository,
                roomEventService,
                new FixedIdGenerator("unused"),
                new FixedTimeProvider(1717300200000L),
                new DrrrProperties()
        );

        service.markRoomDisconnected("u-1", "r-1");

        assertThat(userSessionRepository.saved.status()).isEqualTo(UserStatus.RECONNECTING);
        assertThat(userSessionRepository.saved.connected()).isFalse();
        assertThat(userSessionRepository.reconnectingUserId).isEqualTo("u-1");
        assertThat(userSessionRepository.reconnectingScore).isEqualTo(1717300200000L);
        assertThat(roomMemberRepository.saved.memberStatus()).isEqualTo(MemberStatus.RECONNECTING);
        assertThat(roomEventService.reconnectingMember.userId()).isEqualTo("u-1");
        assertThat(roomEventService.lastDisconnectedAt).isEqualTo(1717300200000L);
    }

    private static final class RecordingUserSessionRepository extends UserSessionRepository {
        private final Map<String, UserSession> sessions = new HashMap<>();
        private final Set<String> reconnectingUsers = new HashSet<>();
        private UserSession saved;
        private String reconnectingUserId;
        private long reconnectingScore;
        private String removedReconnectingUserId;

        private RecordingUserSessionRepository() {
            super(null);
        }

        @Override
        public void save(UserSession userSession) {
            this.saved = userSession;
            this.sessions.put(userSession.userId(), userSession);
        }

        @Override
        public Optional<UserSession> findById(String userId) {
            return Optional.ofNullable(sessions.get(userId));
        }

        @Override
        public void saveReconnectingUser(String userId, long lastDisconnectedAt) {
            this.reconnectingUserId = userId;
            this.reconnectingScore = lastDisconnectedAt;
            this.reconnectingUsers.add(userId);
        }

        @Override
        public boolean isReconnectingUser(String userId) {
            return reconnectingUsers.contains(userId);
        }

        @Override
        public void removeReconnectingUser(String userId) {
            this.removedReconnectingUserId = userId;
            this.reconnectingUsers.remove(userId);
        }
    }

    private static final class RecordingLobbyRepository extends LobbyRepository {
        private String member;
        private double score;

        private RecordingLobbyRepository() {
            super(null);
        }

        @Override
        public void zAdd(String member, double score) {
            this.member = member;
            this.score = score;
        }
    }

    private static final class RecordingRoomRepository extends RoomRepository {
        private final Map<String, Room> rooms = new HashMap<>();

        private RecordingRoomRepository() {
            super(null);
        }

        @Override
        public Optional<Room> findById(String roomId) {
            return Optional.ofNullable(rooms.get(roomId));
        }
    }

    private static final class RecordingRoomMemberRepository extends RoomMemberRepository {
        private final Map<String, RoomMember> members = new HashMap<>();
        private RoomMember saved;

        private RecordingRoomMemberRepository() {
            super(null);
        }

        @Override
        public Optional<RoomMember> findMember(String roomId, String userId) {
            return Optional.ofNullable(members.get(roomId + ":" + userId));
        }

        @Override
        public boolean existsMemberOrder(String roomId, String userId) {
            return members.containsKey(roomId + ":" + userId);
        }

        @Override
        public void save(RoomMember roomMember) {
            this.saved = roomMember;
            this.members.put(roomMember.roomId() + ":" + roomMember.userId(), roomMember);
        }
    }

    private static final class RecordingRoomEventService extends RoomEventService {
        private RoomMember reconnectingMember;
        private long lastDisconnectedAt;
        private RoomMember reconnectedMember;
        private Long reconnectedAt;

        private RecordingRoomEventService() {
            super(null, null, null, new RoomWebSocketOperations(new RoomWebSocketConnectionRegistry(), null), null, null, null);
        }

        @Override
        public RoomEvent recordUserReconnecting(Room room, RoomMember reconnectingMember, long lastDisconnectedAt) {
            this.reconnectingMember = reconnectingMember;
            this.lastDisconnectedAt = lastDisconnectedAt;
            return null;
        }

        @Override
        public RoomEvent recordUserReconnected(Room room, RoomMember reconnectedMember, long reconnectedAt) {
            this.reconnectedMember = reconnectedMember;
            this.reconnectedAt = reconnectedAt;
            return null;
        }
    }

    private record FixedIdGenerator(String value) implements IdGenerator {
        @Override
        public String newUserId() {
            return value;
        }

        @Override
        public String newRoomId() {
            throw new UnsupportedOperationException();
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
