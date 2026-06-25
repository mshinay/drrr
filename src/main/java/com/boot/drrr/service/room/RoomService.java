package com.boot.drrr.service.room;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.lock.RoomLock;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
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
import com.boot.drrr.service.event.RoomEventService;
import com.boot.drrr.service.owner.OwnerTransferService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class RoomService {
    private final UserSessionRepository userSessionRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomIndexRepository roomIndexRepository;
    private final GovernanceRepository governanceRepository;
    private final OwnerTransferService ownerTransferService;
    private final RoomEventService roomEventService;
    private final RoomPasswordHasher roomPasswordHasher;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final DrrrProperties drrrProperties;
    private final RoomLock roomLock;

    public RoomService(
            UserSessionRepository userSessionRepository,
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            RoomIndexRepository roomIndexRepository,
            GovernanceRepository governanceRepository,
            OwnerTransferService ownerTransferService,
            RoomEventService roomEventService,
            RoomPasswordHasher roomPasswordHasher,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            DrrrProperties drrrProperties,
            RoomLock roomLock
    ) {
        this.userSessionRepository = userSessionRepository;
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.roomIndexRepository = roomIndexRepository;
        this.governanceRepository = governanceRepository;
        this.ownerTransferService = ownerTransferService;
        this.roomEventService = roomEventService;
        this.roomPasswordHasher = roomPasswordHasher;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.drrrProperties = drrrProperties;
        this.roomLock = roomLock;
    }

    public CreateRoomView createRoom(CreateRoomCommand command) {
        validateHistoryStrategy(command.historyStrategy());
        validateRoomName(command.name());
        validateMaxMembers(command.maxMembers());

        return withLocks(command.userId(), null, () -> {
            UserSession userSession = loadUser(command.userId());
            assertUserNotInRoom(userSession);

            long now = timeProvider.nowMillis();
            String roomId = idGenerator.newRoomId();
            Room room = new Room(
                    roomId,
                    normalizeRequired(command.name()),
                    normalizeOptional(command.description()),
                    roomPasswordHasher.hashNullable(command.password()),
                    command.maxMembers(),
                    userSession.userId(),
                    userSession.userId(),
                    RoomStatus.ACTIVE,
                    command.userListVisible(),
                    normalizeHistoryStrategy(command.historyStrategy()),
                    command.allowOwnerConfigChange(),
                    now,
                    now,
                    null
            );
            RoomMember creator = new RoomMember(
                    roomId,
                    userSession.userId(),
                    userSession.nickname(),
                    MemberStatus.ONLINE,
                    now,
                    now,
                    true
            );
            UserSession updatedUserSession = new UserSession(
                    userSession.userId(),
                    userSession.nickname(),
                    roomId,
                    UserStatus.ONLINE,
                    userSession.connected(),
                    userSession.lastConnectedAt(),
                    userSession.lastDisconnectedAt(),
                    userSession.createdAt(),
                    now
            );

            roomRepository.save(room);
            roomMemberRepository.save(creator);
            userSessionRepository.save(updatedUserSession);
            roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, roomId, now);
            roomIndexRepository.zRem(RoomIndexKey.EMPTY, roomId);
            return lockedResult(new CreateRoomView(room, creator));
        });
    }

    public JoinRoomView joinRoom(String roomId, JoinRoomCommand command) {
        return withLocks(command.userId(), roomId, () -> {
            UserSession userSession = loadUser(command.userId());
            assertUserNotInRoom(userSession);
            Room room = loadJoinableRoom(roomId);
            long now = timeProvider.nowMillis();

            if (governanceRepository.hasBanIndexEntry(roomId, userSession.userId())) {
                throw new BusinessException(ErrorCode.USER_BANNED);
            }
            assertPasswordMatches(room, command.password());

            long currentMembers = roomMemberRepository.countMembers(roomId);
            if (currentMembers >= room.maxMembers()) {
                throw new BusinessException(ErrorCode.ROOM_FULL);
            }

            ensureNicknameNotDuplicated(roomId, userSession.nickname());

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
            RoomMember newMember = new RoomMember(
                    roomId,
                    userSession.userId(),
                    userSession.nickname(),
                    MemberStatus.ONLINE,
                    now,
                    now,
                    false
            );
            UserSession updatedUserSession = new UserSession(
                    userSession.userId(),
                    userSession.nickname(),
                    roomId,
                    UserStatus.ONLINE,
                    userSession.connected(),
                    userSession.lastConnectedAt(),
                    userSession.lastDisconnectedAt(),
                    userSession.createdAt(),
                    now
            );

            roomRepository.save(reactivatedRoom);
            roomMemberRepository.save(newMember);
            userSessionRepository.save(updatedUserSession);
            roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, roomId, now);
            roomIndexRepository.zRem(RoomIndexKey.EMPTY, roomId);

            List<RoomMember> members = roomMemberRepository.listMembers(roomId);
            return lockedResult(
                    new JoinRoomView(reactivatedRoom, newMember, members),
                    () -> roomEventService.recordUserJoin(reactivatedRoom, newMember)
            );
        });
    }

    public LeaveRoomResult leaveRoom(String roomId, LeaveRoomCommand command) {
        return withLocks(command.userId(), roomId, () -> {
            UserSession userSession = loadUser(command.userId());
            if (userSession.currentRoomId() == null || !userSession.currentRoomId().equals(roomId)) {
                throw new BusinessException(ErrorCode.ROOM_CONTEXT_MISMATCH);
            }

            Room room = loadExistingRoom(roomId);
            RoomMember leavingMember = roomMemberRepository.findMember(roomId, command.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            roomMemberRepository.removeMember(roomId, command.userId());
            long now = timeProvider.nowMillis();
            UserSession clearedSession = new UserSession(
                    userSession.userId(),
                    userSession.nickname(),
                    null,
                    UserStatus.ONLINE,
                    userSession.connected(),
                    userSession.lastConnectedAt(),
                    userSession.lastDisconnectedAt(),
                    userSession.createdAt(),
                    now
            );
            userSessionRepository.save(clearedSession);

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
                if (leavingMember.isOwner()) {
                    RoomMember newOwner = ownerTransferService.selectNextOwner(remainingMembers)
                            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "owner transfer candidate missing"));
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

            List<Runnable> pendingSideEffects = new ArrayList<>();
            pendingSideEffects.add(() -> roomEventService.recordUserLeave(updatedRoom, leavingMember));
            if (ownerTransferred && newOwnerUserId != null) {
                String ownerUserId = newOwnerUserId;
                pendingSideEffects.add(() -> roomEventService.recordOwnerTransfer(updatedRoom, leavingMember.userId(), ownerUserId));
            }
            if (updatedRoom.status() == RoomStatus.EMPTY && updatedRoom.emptySince() != null) {
                long emptySince = updatedRoom.emptySince();
                pendingSideEffects.add(() -> roomEventService.recordRoomEmpty(updatedRoom, emptySince));
            }
            return new LockedResult<>(
                    new LeaveRoomResult(true, ownerTransferred, newOwnerUserId, updatedRoom.status()),
                    pendingSideEffects
            );
        });
    }

    public UpdateRoomView updateRoom(String roomId, UpdateRoomCommand command) {
        validateHistoryStrategy(command.historyStrategy());
        validateRoomName(command.name());

        return withLocks(command.operatorUserId(), roomId, () -> {
            UserSession userSession = loadUser(command.operatorUserId());
            if (userSession.currentRoomId() == null || !userSession.currentRoomId().equals(roomId)) {
                throw new BusinessException(ErrorCode.ROOM_CONTEXT_MISMATCH);
            }

            Room room = loadExistingRoom(roomId);
            roomMemberRepository.findMember(roomId, command.operatorUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
            if (!command.operatorUserId().equals(room.ownerUserId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            if (!command.operatorUserId().equals(room.initialOwnerUserId()) && !room.allowOwnerConfigChange()) {
                throw new BusinessException(ErrorCode.CONFIG_LOCKED);
            }

            long now = timeProvider.nowMillis();
            Room updatedRoom = new Room(
                    room.roomId(),
                    normalizeRequired(command.name()),
                    normalizeOptional(command.description()),
                    room.passwordHash(),
                    room.maxMembers(),
                    room.ownerUserId(),
                    room.initialOwnerUserId(),
                    room.status(),
                    command.userListVisible(),
                    normalizeHistoryStrategy(command.historyStrategy()),
                    command.allowOwnerConfigChange(),
                    room.createdAt(),
                    now,
                    room.emptySince()
            );
            roomRepository.save(updatedRoom);
            roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, roomId, now);
            return lockedResult(
                    new UpdateRoomView(updatedRoom),
                    () -> roomEventService.createRoomConfigSystemMessage(
                            updatedRoom,
                            command.operatorUserId(),
                            userSession.nickname()
                    )
            );
        });
    }

    private <T> T withLocks(String userId, String roomId, Supplier<LockedResult<T>> supplier) {
        LockedResult<T> result = roomLock.supply(lockKeys(userId, roomId), supplier);
        for (Runnable pendingSideEffect : result.pendingSideEffects()) {
            pendingSideEffect.run();
        }
        return result.value();
    }

    private <T> LockedResult<T> lockedResult(T value, Runnable... pendingSideEffects) {
        return new LockedResult<>(value, List.of(pendingSideEffects));
    }

    private List<String> lockKeys(String userId, String roomId) {
        List<String> keys = new ArrayList<>();
        keys.add(userLockKey(userId));
        if (roomId != null && !roomId.isBlank()) {
            keys.add(roomLockKey(roomId));
        }
        return keys;
    }

    private String userLockKey(String userId) {
        return "user:" + userId;
    }

    private String roomLockKey(String roomId) {
        return "room:" + roomId;
    }

    private UserSession loadUser(String userId) {
        UserSession userSession = userSessionRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (userSession.currentRoomId() == null || userSession.currentRoomId().isBlank()) {
            return userSession;
        }

        String currentRoomId = userSession.currentRoomId();
        boolean roomMissing = roomRepository.findById(currentRoomId).isEmpty();
        boolean memberMissing = !roomMissing && roomMemberRepository.findMember(currentRoomId, userId).isEmpty();
        if (!roomMissing && !memberMissing) {
            return userSession;
        }

        UserSession clearedSession = new UserSession(
                userSession.userId(),
                userSession.nickname(),
                null,
                userSession.status(),
                userSession.connected(),
                userSession.lastConnectedAt(),
                userSession.lastDisconnectedAt(),
                userSession.createdAt(),
                timeProvider.nowMillis()
        );
        userSessionRepository.save(clearedSession);
        return clearedSession;
    }

    private Room loadExistingRoom(String roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (isExpired(room)) {
            throw new BusinessException(ErrorCode.ROOM_EXPIRED);
        }
        return ensureOwnerInvariant(room, roomMemberRepository.listMembers(roomId));
    }

    private Room loadJoinableRoom(String roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (room.status() == RoomStatus.EXPIRED || isExpired(room)) {
            throw new BusinessException(ErrorCode.ROOM_EXPIRED);
        }
        return ensureOwnerInvariant(room, roomMemberRepository.listMembers(roomId));
    }

    private Room ensureOwnerInvariant(Room room, List<RoomMember> members) {
        if (members == null || members.isEmpty()) {
            return room;
        }

        List<RoomMember> ownerMembers = members.stream()
                .filter(RoomMember::isOwner)
                .toList();
        if (ownerMembers.size() == 1 && ownerMembers.get(0).userId().equals(room.ownerUserId())) {
            return room;
        }

        RoomMember fallbackOwner = ownerMembers.size() == 1
                ? ownerMembers.get(0)
                : ownerTransferService.selectNextOwner(members)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "owner invariant candidate missing"));

        for (RoomMember member : ownerTransferService.applyOwnerFlag(members, fallbackOwner.userId())) {
            roomMemberRepository.save(member);
        }

        Room repairedRoom = new Room(
                room.roomId(),
                room.name(),
                room.description(),
                room.passwordHash(),
                room.maxMembers(),
                fallbackOwner.userId(),
                room.initialOwnerUserId(),
                room.status(),
                room.userListVisible(),
                room.historyStrategy(),
                room.allowOwnerConfigChange(),
                room.createdAt(),
                room.lastActiveAt(),
                room.emptySince()
        );
        roomRepository.save(repairedRoom);
        return repairedRoom;
    }

    private boolean isExpired(Room room) {
        return room.status() == RoomStatus.EXPIRED
                || (room.status() == RoomStatus.EMPTY
                && room.emptySince() != null
                && room.emptySince() + drrrProperties.getRoom().getEmptyExpiry().toMillis() <= timeProvider.nowMillis());
    }

    private void assertUserNotInRoom(UserSession userSession) {
        if (userSession.currentRoomId() != null && !userSession.currentRoomId().isBlank()) {
            throw new BusinessException(ErrorCode.USER_ALREADY_IN_ROOM);
        }
    }

    private void assertPasswordMatches(Room room, String rawPassword) {
        if (room.passwordHash() == null || room.passwordHash().isBlank()) {
            return;
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException(ErrorCode.PASSWORD_REQUIRED);
        }
        if (!roomPasswordHasher.matches(rawPassword, room.passwordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_INVALID);
        }
    }

    private void ensureNicknameNotDuplicated(String roomId, String nickname) {
        boolean duplicated = roomMemberRepository.listMembers(roomId).stream()
                .map(RoomMember::nickname)
                .anyMatch(nickname::equals);
        if (duplicated) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
        }
    }

    private void validateRoomName(String roomName) {
        if (roomName == null || roomName.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "room name must not be blank");
        }
    }

    private void validateMaxMembers(int maxMembers) {
        int min = drrrProperties.getRoom().getMaxMembersMin();
        int max = drrrProperties.getRoom().getMaxMembersMax();
        if (maxMembers < min || maxMembers > max) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "maxMembers must be between " + min + " and " + max
            );
        }
    }

    private void validateHistoryStrategy(HistoryStrategy historyStrategy) {
        if (historyStrategy == null || historyStrategy.type() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "historyStrategy.type must not be null");
        }
        HistoryStrategyType type = historyStrategy.type();
        Integer value = historyStrategy.value();
        if ((type == HistoryStrategyType.COUNT || type == HistoryStrategyType.MINUTES)
                && (value == null || value <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "historyStrategy.value must be positive");
        }
    }

    private HistoryStrategy normalizeHistoryStrategy(HistoryStrategy historyStrategy) {
        if (historyStrategy.type() == HistoryStrategyType.NONE) {
            return new HistoryStrategy(HistoryStrategyType.NONE, null);
        }
        return new HistoryStrategy(historyStrategy.type(), historyStrategy.value());
    }

    private String normalizeRequired(String value) {
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private record LockedResult<T>(T value, List<Runnable> pendingSideEffects) {
    }
}
