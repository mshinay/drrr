package com.boot.drrr.service.lobby;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.repository.lobby.LobbyRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LobbyServiceTest {

    @Test
    void getLobbyReturnsLastActiveViewAndFiltersDirtyRoomIds() {
        StubLobbyRepository lobbyRepository = new StubLobbyRepository(2L);
        StubRoomIndexRepository roomIndexRepository = new StubRoomIndexRepository(
                new LinkedHashSet<>(List.of("r_missing", "r_2", "r_1")),
                new LinkedHashSet<>(List.of("r_1", "r_2", "r_missing"))
        );
        StubRoomRepository roomRepository = new StubRoomRepository(Map.of(
                "r_1", room("r_1", "Night", 10, 1717299000000L, 1717300200000L),
                "r_2", room("r_2", "Morning", 8, 1717298000000L, 1717300100000L)
        ));
        StubRoomMemberRepository roomMemberRepository = new StubRoomMemberRepository(Map.of(
                "r_1", 5L,
                "r_2", 2L
        ));
        LobbyService service = new LobbyService(
                lobbyRepository,
                roomIndexRepository,
                roomRepository,
                roomMemberRepository,
                new FixedTimeProvider(1717300300000L),
                propertiesWithWindow(Duration.ofMinutes(5))
        );

        LobbyView lobbyView = service.getLobby(LobbySort.LAST_ACTIVE);

        assertThat(lobbyView.activeUsersLast5Minutes()).isEqualTo(2L);
        assertThat(lobbyRepository.minScore).isEqualTo(1717300000000L);
        assertThat(lobbyRepository.maxScore).isEqualTo(1717300300000L);
        assertThat(roomRepository.requestedRoomIds).containsExactly("r_missing", "r_2", "r_1");
        assertThat(roomMemberRepository.requestedRoomIds).containsExactly("r_missing", "r_2", "r_1");
        assertThat(lobbyView.rooms()).extracting(LobbyView.LobbyRoomSummary::roomId)
                .containsExactly("r_2", "r_1");
        assertThat(lobbyView.rooms()).extracting(LobbyView.LobbyRoomSummary::currentMembers)
                .containsExactly(2L, 5L);
    }

    @Test
    void getLobbySortsByMemberCountDescending() {
        LobbyService service = new LobbyService(
                new StubLobbyRepository(0L),
                new StubRoomIndexRepository(new LinkedHashSet<>(List.of("r_2", "r_1")), new LinkedHashSet<>(List.of("r_1", "r_2"))),
                new StubRoomRepository(Map.of(
                        "r_1", room("r_1", "Night", 10, 1717299000000L, 1717300100000L),
                        "r_2", room("r_2", "Morning", 8, 1717298000000L, 1717300200000L)
                )),
                new StubRoomMemberRepository(Map.of(
                        "r_1", 1L,
                        "r_2", 7L
                )),
                new FixedTimeProvider(1717300300000L),
                propertiesWithWindow(Duration.ofMinutes(5))
        );

        LobbyView lobbyView = service.getLobby(LobbySort.MEMBER_COUNT);

        assertThat(lobbyView.rooms()).extracting(LobbyView.LobbyRoomSummary::roomId)
                .containsExactly("r_2", "r_1");
    }

    @Test
    void getLobbySortsBySurvivalTimeAscending() {
        LobbyService service = new LobbyService(
                new StubLobbyRepository(0L),
                new StubRoomIndexRepository(new LinkedHashSet<>(List.of("r_2", "r_1")), new LinkedHashSet<>(List.of("r_2", "r_1"))),
                new StubRoomRepository(Map.of(
                        "r_1", room("r_1", "Night", 10, 1717299000000L, 1717300100000L),
                        "r_2", room("r_2", "Morning", 8, 1717297000000L, 1717300200000L)
                )),
                new StubRoomMemberRepository(Map.of(
                        "r_1", 1L,
                        "r_2", 7L
                )),
                new FixedTimeProvider(1717300300000L),
                propertiesWithWindow(Duration.ofMinutes(5))
        );

        LobbyView lobbyView = service.getLobby(LobbySort.SURVIVAL_TIME);

        assertThat(lobbyView.rooms()).extracting(LobbyView.LobbyRoomSummary::roomId)
                .containsExactly("r_2", "r_1");
    }

    private static Room room(String roomId, String name, int maxMembers, long createdAt, long lastActiveAt) {
        return new Room(
                roomId,
                name,
                name + " desc",
                null,
                maxMembers,
                "u_owner",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 20),
                true,
                createdAt,
                lastActiveAt,
                null
        );
    }

    private static DrrrProperties propertiesWithWindow(Duration window) {
        DrrrProperties properties = new DrrrProperties();
        properties.getLobby().setActiveWindow(window);
        return properties;
    }

    private static final class StubLobbyRepository extends LobbyRepository {
        private final long count;
        private double minScore;
        private double maxScore;

        private StubLobbyRepository(long count) {
            super(null);
            this.count = count;
        }

        @Override
        public long zCountByScore(double minScoreInclusive, double maxScoreInclusive) {
            this.minScore = minScoreInclusive;
            this.maxScore = maxScoreInclusive;
            return count;
        }
    }

    private static final class StubRoomIndexRepository extends RoomIndexRepository {
        private final Set<String> reverseRange;
        private final Set<String> range;

        private StubRoomIndexRepository(Set<String> reverseRange, Set<String> range) {
            super(null);
            this.reverseRange = new LinkedHashSet<>(reverseRange);
            this.range = new LinkedHashSet<>(range);
        }

        @Override
        public Set<String> zRange(RoomIndexKey indexKey, long start, long end) {
            return range;
        }

        @Override
        public Set<String> zReverseRange(RoomIndexKey indexKey, long start, long end) {
            return reverseRange;
        }
    }

    private static final class StubRoomRepository extends RoomRepository {
        private final Map<String, Room> rooms;
        private List<String> requestedRoomIds = List.of();

        private StubRoomRepository(Map<String, Room> rooms) {
            super(null);
            this.rooms = rooms;
        }

        @Override
        public List<Room> findByIds(List<String> roomIds) {
            this.requestedRoomIds = List.copyOf(roomIds);
            List<Room> result = new java.util.ArrayList<>();
            for (String roomId : roomIds) {
                Room room = rooms.get(roomId);
                if (room != null) {
                    result.add(room);
                }
            }
            return result;
        }
    }

    private static final class StubRoomMemberRepository extends RoomMemberRepository {
        private final Map<String, Long> counts;
        private List<String> requestedRoomIds = List.of();

        private StubRoomMemberRepository(Map<String, Long> counts) {
            super(null);
            this.counts = counts;
        }

        @Override
        public Map<String, Long> countMembersByRoomIds(List<String> roomIds) {
            this.requestedRoomIds = List.copyOf(roomIds);
            Map<String, Long> ordered = new LinkedHashMap<>();
            for (String roomId : roomIds) {
                ordered.put(roomId, counts.getOrDefault(roomId, 0L));
            }
            return ordered;
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

