package com.boot.drrr.service.cleanup;

import com.boot.drrr.common.lock.RoomLock;
import com.boot.drrr.common.time.TimeProvider;
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
import com.boot.drrr.service.owner.OwnerTransferService;
import com.boot.drrr.ws.RoomRemovedPayload;
import com.boot.drrr.ws.RoomWebSocketConnectionRegistry;
import com.boot.drrr.ws.RoomWebSocketOperations;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CleanupService {
    private static final String ROOM_REMOVED_REASON_EXPIRED = "EXPIRED";

    private final UserSessionRepository userSessionRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomIndexRepository roomIndexRepository;
    private final GovernanceRepository governanceRepository;
    private final RoomEventRepository roomEventRepository;
    private final MessageRepository messageRepository;
    private final OwnerTransferService ownerTransferService;
    private final RoomEventService roomEventService;
    private final RoomWebSocketOperations roomWebSocketOperations;
    private final RoomWebSocketConnectionRegistry roomWebSocketConnectionRegistry;
    private final TimeProvider timeProvider;
    private final RoomLock roomLock;

    public CleanupService(
            UserSessionRepository userSessionRepository,
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            RoomIndexRepository roomIndexRepository,
            GovernanceRepository governanceRepository,
            RoomEventRepository roomEventRepository,
            MessageRepository messageRepository,
            OwnerTransferService ownerTransferService,
            RoomEventService roomEventService,
            RoomWebSocketOperations roomWebSocketOperations,
            RoomWebSocketConnectionRegistry roomWebSocketConnectionRegistry,
            TimeProvider timeProvider,
            RoomLock roomLock
    ) {
        this.userSessionRepository = userSessionRepository;
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.roomIndexRepository = roomIndexRepository;
        this.governanceRepository = governanceRepository;
        this.roomEventRepository = roomEventRepository;
        this.messageRepository = messageRepository;
        this.ownerTransferService = ownerTransferService;
        this.roomEventService = roomEventService;
        this.roomWebSocketOperations = roomWebSocketOperations;
        this.roomWebSocketConnectionRegistry = roomWebSocketConnectionRegistry;
        this.timeProvider = timeProvider;
        this.roomLock = roomLock;
    }

    public int cleanupReconnectingUsersTimedOut(long reconnectTimeoutMillis) {
        long now = timeProvider.nowMillis();
        long cutoff = now - reconnectTimeoutMillis;
        Set<String> timedOutUserIds = userSessionRepository.listReconnectingUserIdsByScore(Double.NEGATIVE_INFINITY, cutoff);
        for (String userId : timedOutUserIds) {
            cleanupTimedOutReconnectingUser(userId, now);
        }
        return timedOutUserIds.size();
    }

    public int cleanupExpiredEmptyRooms(long emptyRoomExpiryMillis) {
        long now = timeProvider.nowMillis();
        long cutoff = now - emptyRoomExpiryMillis;
        Set<String> expiredRoomIds = roomIndexRepository.zRangeByScore(RoomIndexKey.EMPTY, Double.NEGATIVE_INFINITY, cutoff);
        for (String roomId : expiredRoomIds) {
            cleanupExpiredEmptyRoom(roomId, now, emptyRoomExpiryMillis);
        }
        return expiredRoomIds.size();
    }

    private void cleanupTimedOutReconnectingUser(String userId, long now) {
        UserSession snapshot = userSessionRepository.findById(userId).orElse(null);
        roomLock.execute(lockKeys(userId, snapshot == null ? null : snapshot.currentRoomId()), () -> {
            if (!userSessionRepository.isReconnectingUser(userId)) {
                return;
            }

            UserSession userSession = userSessionRepository.findById(userId).orElse(null);
            if (userSession == null) {
                userSessionRepository.removeReconnectingUser(userId);
                return;
            }

            String roomId = userSession.currentRoomId();
            if (roomId == null || roomId.isBlank()) {
                userSessionRepository.removeReconnectingUser(userId);
                userSessionRepository.save(toOfflineSession(userSession, null, now));
                return;
            }

            Room room = roomRepository.findById(roomId).orElse(null);
            RoomMember reconnectingMember = roomMemberRepository.findMember(roomId, userId).orElse(null);
            if (room == null || reconnectingMember == null) {
                userSessionRepository.removeReconnectingUser(userId);
                userSessionRepository.save(toOfflineSession(userSession, null, now));
                return;
            }

            roomMemberRepository.removeMember(roomId, userId);
            userSessionRepository.removeReconnectingUser(userId);
            userSessionRepository.save(toOfflineSession(userSession, null, now));

            List<RoomMember> remainingMembers = roomMemberRepository.listMembers(roomId);
            boolean ownerTransferred = false;
            String newOwnerUserId = null;
            Room updatedRoom;

            if (remainingMembers.isEmpty()) {
                updatedRoom = new Room(
                        room.roomId(),
                        room.name(),
                        room.description(),
                        room.passwordHash(),
                        room.maxMembers(),
                        room.ownerUserId(),
                        room.initialOwnerUserId(),
                        RoomStatus.EMPTY,
                        room.userListVisible(),
                        room.historyStrategy(),
                        room.allowOwnerConfigChange(),
                        room.createdAt(),
                        now,
                        now
                );
                roomRepository.save(updatedRoom);
                roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, roomId, now);
                roomIndexRepository.zAdd(RoomIndexKey.EMPTY, roomId, now);
            } else {
                if (reconnectingMember.isOwner()) {
                    RoomMember newOwner = ownerTransferService.selectNextOwner(remainingMembers)
                            .orElseThrow(() -> new IllegalStateException("owner transfer candidate missing during cleanup"));
                    newOwnerUserId = newOwner.userId();
                    ownerTransferred = true;
                    for (RoomMember member : ownerTransferService.applyOwnerFlag(remainingMembers, newOwnerUserId)) {
                        roomMemberRepository.save(member);
                    }
                }

                updatedRoom = new Room(
                        room.roomId(),
                        room.name(),
                        room.description(),
                        room.passwordHash(),
                        room.maxMembers(),
                        ownerTransferred ? newOwnerUserId : room.ownerUserId(),
                        room.initialOwnerUserId(),
                        RoomStatus.ACTIVE,
                        room.userListVisible(),
                        room.historyStrategy(),
                        room.allowOwnerConfigChange(),
                        room.createdAt(),
                        now,
                        null
                );
                roomRepository.save(updatedRoom);
                roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, roomId, now);
                roomIndexRepository.zRem(RoomIndexKey.EMPTY, roomId);
            }

            roomEventService.recordUserLeave(updatedRoom, reconnectingMember);
            if (ownerTransferred && newOwnerUserId != null) {
                roomEventService.recordOwnerTransfer(updatedRoom, reconnectingMember.userId(), newOwnerUserId);
            }
            if (updatedRoom.status() == RoomStatus.EMPTY && updatedRoom.emptySince() != null) {
                roomEventService.recordRoomEmpty(updatedRoom, updatedRoom.emptySince());
            }
        });
    }

    private void cleanupExpiredEmptyRoom(String roomId, long now, long emptyRoomExpiryMillis) {
        roomLock.execute(lockKeys(null, roomId), () -> {
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                roomIndexRepository.zRem(RoomIndexKey.ACTIVE, roomId);
                roomIndexRepository.zRem(RoomIndexKey.EMPTY, roomId);
                return;
            }

            if (room.status() == RoomStatus.EXPIRED) {
                expireRoomRuntime(room, now);
                return;
            }

            if (room.status() != RoomStatus.EMPTY || room.emptySince() == null) {
                roomIndexRepository.zRem(RoomIndexKey.EMPTY, roomId);
                return;
            }

            List<RoomMember> members = roomMemberRepository.listMembers(roomId);
            if (!members.isEmpty()) {
                Room reactivatedRoom = new Room(
                        room.roomId(),
                        room.name(),
                        room.description(),
                        room.passwordHash(),
                        room.maxMembers(),
                        room.ownerUserId(),
                        room.initialOwnerUserId(),
                        RoomStatus.ACTIVE,
                        room.userListVisible(),
                        room.historyStrategy(),
                        room.allowOwnerConfigChange(),
                        room.createdAt(),
                        now,
                        null
                );
                roomRepository.save(reactivatedRoom);
                roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, roomId, now);
                roomIndexRepository.zRem(RoomIndexKey.EMPTY, roomId);
                return;
            }

            if (room.emptySince() + emptyRoomExpiryMillis > now) {
                return;
            }

            expireRoomRuntime(room, now);
        });
    }

    private void expireRoomRuntime(Room room, long now) {
        Room expiredRoom = new Room(
                room.roomId(),
                room.name(),
                room.description(),
                room.passwordHash(),
                room.maxMembers(),
                room.ownerUserId(),
                room.initialOwnerUserId(),
                RoomStatus.EXPIRED,
                room.userListVisible(),
                room.historyStrategy(),
                room.allowOwnerConfigChange(),
                room.createdAt(),
                now,
                room.emptySince()
        );
        roomRepository.save(expiredRoom);

        List<String> connectedUserIds = roomWebSocketConnectionRegistry.listRoomUserIds(room.roomId());
        if (!connectedUserIds.isEmpty()) {
            roomEventService.recordRoomExpired(expiredRoom, now, connectedUserIds);
        }

        roomRepository.deleteById(room.roomId());
        roomMemberRepository.deleteAll(room.roomId());
        messageRepository.deleteAll(room.roomId());
        roomEventRepository.deleteAll(room.roomId());
        governanceRepository.deleteMuteState(room.roomId());
        governanceRepository.deleteBanState(room.roomId());
        roomIndexRepository.zRem(RoomIndexKey.ACTIVE, room.roomId());
        roomIndexRepository.zRem(RoomIndexKey.EMPTY, room.roomId());

        if (!connectedUserIds.isEmpty()) {
            roomWebSocketOperations.broadcastToRoom(
                    room.roomId(),
                    "ROOM_REMOVED",
                    null,
                    new RoomRemovedPayload(room.roomId(), ROOM_REMOVED_REASON_EXPIRED)
            );
            roomWebSocketOperations.closeRoomSessions(room.roomId());
        }
    }

    private UserSession toOfflineSession(UserSession session, String currentRoomId, long now) {
        Long disconnectedAt = session.lastDisconnectedAt() == null ? now : session.lastDisconnectedAt();
        return new UserSession(
                session.userId(),
                session.nickname(),
                currentRoomId,
                UserStatus.OFFLINE,
                false,
                session.lastConnectedAt(),
                disconnectedAt,
                session.createdAt(),
                now
        );
    }

    private List<String> lockKeys(String userId, String roomId) {
        List<String> keys = new ArrayList<>();
        if (userId != null && !userId.isBlank()) {
            keys.add("user:" + userId);
        }
        if (roomId != null && !roomId.isBlank()) {
            keys.add("room:" + roomId);
        }
        return keys;
    }
}
