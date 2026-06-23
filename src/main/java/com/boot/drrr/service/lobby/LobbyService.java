package com.boot.drrr.service.lobby;

import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.repository.lobby.LobbyRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.repository.room.RoomIndexRepository.RoomIndexKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LobbyService {
    private final LobbyRepository lobbyRepository;
    private final RoomIndexRepository roomIndexRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final TimeProvider timeProvider;
    private final DrrrProperties drrrProperties;

    public LobbyService(
            LobbyRepository lobbyRepository,
            RoomIndexRepository roomIndexRepository,
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            TimeProvider timeProvider,
            DrrrProperties drrrProperties
    ) {
        this.lobbyRepository = lobbyRepository;
        this.roomIndexRepository = roomIndexRepository;
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.timeProvider = timeProvider;
        this.drrrProperties = drrrProperties;
    }

    public LobbyView getLobby(LobbySort sort) {
        LobbySort effectiveSort = sort == null ? LobbySort.LAST_ACTIVE : sort;
        long now = timeProvider.nowMillis();
        long minScore = now - drrrProperties.getLobby().getActiveWindow().toMillis();
        long activeUsers = lobbyRepository.zCountByScore(minScore, now);
        return new LobbyView(activeUsers, loadRoomSummaries(effectiveSort));
    }

    private List<LobbyView.LobbyRoomSummary> loadRoomSummaries(LobbySort sort) {
        List<String> orderedRoomIds = loadOrderedRoomIds(sort);
        if (orderedRoomIds.isEmpty()) {
            return List.of();
        }

        Map<String, Room> roomsById = new LinkedHashMap<>();
        for (Room room : roomRepository.findByIds(orderedRoomIds)) {
            roomsById.put(room.roomId(), room);
        }

        Map<String, Long> memberCounts = roomMemberRepository.countMembersByRoomIds(orderedRoomIds);
        List<LobbyView.LobbyRoomSummary> summaries = new ArrayList<>();
        for (String roomId : orderedRoomIds) {
            Room room = roomsById.get(roomId);
            if (room == null) {
                continue;
            }
            summaries.add(new LobbyView.LobbyRoomSummary(
                    room.roomId(),
                    room.name(),
                    room.description(),
                    memberCounts.getOrDefault(room.roomId(), 0L),
                    room.maxMembers(),
                    room.lastActiveAt(),
                    room.createdAt()
            ));
        }

        if (sort == LobbySort.LAST_ACTIVE) {
            return summaries;
        }

        summaries.sort(comparatorFor(sort));
        return summaries;
    }

    private List<String> loadOrderedRoomIds(LobbySort sort) {
        return switch (sort) {
            case SURVIVAL_TIME -> new ArrayList<>(roomIndexRepository.zRange(RoomIndexKey.ACTIVE, 0, -1));
            case LAST_ACTIVE, MEMBER_COUNT -> new ArrayList<>(roomIndexRepository.zReverseRange(RoomIndexKey.ACTIVE, 0, -1));
        };
    }

    private Comparator<LobbyView.LobbyRoomSummary> comparatorFor(LobbySort sort) {
        return switch (sort) {
            case MEMBER_COUNT -> Comparator
                    .comparingLong(LobbyView.LobbyRoomSummary::currentMembers)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(LobbyView.LobbyRoomSummary::lastActiveAt).reversed())
                    .thenComparing(LobbyView.LobbyRoomSummary::roomId);
            case SURVIVAL_TIME -> Comparator
                    .comparingLong(LobbyView.LobbyRoomSummary::createdAt)
                    .thenComparing(Comparator.comparingLong(LobbyView.LobbyRoomSummary::lastActiveAt).reversed())
                    .thenComparing(LobbyView.LobbyRoomSummary::roomId);
            case LAST_ACTIVE -> throw new IllegalArgumentException("LAST_ACTIVE relies on Redis ZSet order");
        };
    }
}
