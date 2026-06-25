package com.boot.drrr.service.governance;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.lock.RoomLock;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
import com.boot.drrr.domain.governance.BanRecord;
import com.boot.drrr.domain.governance.MuteRecord;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class GovernanceService {
    private final UserSessionRepository userSessionRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomIndexRepository roomIndexRepository;
    private final GovernanceRepository governanceRepository;
    private final OwnerTransferService ownerTransferService;
    private final RoomEventService roomEventService;
    private final TimeProvider timeProvider;
    private final DrrrProperties drrrProperties;
    private final RoomLock roomLock;

    public GovernanceService(
            UserSessionRepository userSessionRepository,
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            RoomIndexRepository roomIndexRepository,
            GovernanceRepository governanceRepository,
            OwnerTransferService ownerTransferService,
            RoomEventService roomEventService,
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
        this.timeProvider = timeProvider;
        this.drrrProperties = drrrProperties;
        this.roomLock = roomLock;
    }

    public MuteMemberResult muteMember(String roomId, String targetUserId, MuteMemberCommand command) {
        if (command.durationMinutes() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "durationMinutes must be positive");
        }

        return withLocks(command.operatorUserId(), roomId, targetUserId, () -> {
            Room room = loadGovernableRoom(roomId);
            RoomMember operatorMember = requireOwnerMember(room, command.operatorUserId());
            RoomMember targetMember = roomMemberRepository.findMember(roomId, targetUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
            ensureTargetIsNotOwner(room, targetMember.userId());
            loadExistingUser(targetUserId);

            long now = timeProvider.nowMillis();
            long endAt = now + Duration.ofMinutes(command.durationMinutes()).toMillis();
            String reason = normalizeReason(command.reason());
            MuteRecord muteRecord = new MuteRecord(
                    roomId,
                    targetUserId,
                    operatorMember.userId(),
                    now,
                    endAt,
                    reason
            );
            governanceRepository.saveMuteRecord(muteRecord);
            return lockedResult(
                    new MuteMemberResult(true, muteRecord),
                    () -> roomEventService.recordUserMuted(
                            room,
                            operatorMember.userId(),
                            targetMember.userId(),
                            targetMember.nickname(),
                            command.durationMinutes(),
                            endAt,
                            reason
                    )
            );
        });
    }

    public KickMemberResult kickMember(String roomId, String targetUserId, KickMemberCommand command) {
        return withLocks(command.operatorUserId(), roomId, targetUserId, () -> {
            Room room = loadGovernableRoom(roomId);
            RoomMember operatorMember = requireOwnerMember(room, command.operatorUserId());
            UserSession targetSession = loadExistingUser(targetUserId);
            RoomMember targetMember = roomMemberRepository.findMember(roomId, targetUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
            ensureTargetIsNotOwner(room, targetMember.userId());

            long now = timeProvider.nowMillis();
            RemovalOutcome outcome = removeTargetFromRoom(room, targetMember, targetSession, now);
            String reason = normalizeReason(command.reason());
            List<Runnable> sideEffects = new ArrayList<>();
            sideEffects.add(() -> roomEventService.recordUserKicked(
                    outcome.updatedRoom(),
                    operatorMember.userId(),
                    targetMember.userId(),
                    targetMember.nickname(),
                    reason
            ));
            if (outcome.ownerTransferred() && outcome.newOwnerUserId() != null) {
                sideEffects.add(() -> roomEventService.recordOwnerTransfer(
                        outcome.updatedRoom(),
                        targetMember.userId(),
                        outcome.newOwnerUserId()
                ));
            }
            if (outcome.updatedRoom().status() == RoomStatus.EMPTY && outcome.updatedRoom().emptySince() != null) {
                long emptySince = outcome.updatedRoom().emptySince();
                sideEffects.add(() -> roomEventService.recordRoomEmpty(outcome.updatedRoom(), emptySince));
            }
            return new LockedResult<>(
                    new KickMemberResult(
                            true,
                            targetUserId,
                            outcome.updatedRoom().status(),
                            outcome.ownerTransferred(),
                            outcome.newOwnerUserId()
                    ),
                    List.copyOf(sideEffects)
            );
        });
    }

    public BanMemberResult banMember(String roomId, String targetUserId, BanMemberCommand command) {
        return withLocks(command.operatorUserId(), roomId, targetUserId, () -> {
            Room room = loadGovernableRoom(roomId);
            RoomMember operatorMember = requireOwnerMember(room, command.operatorUserId());
            UserSession targetSession = loadExistingUser(targetUserId);
            ensureTargetIsNotOwner(room, targetUserId);

            long now = timeProvider.nowMillis();
            String reason = normalizeReason(command.reason());
            BanRecord banRecord = new BanRecord(roomId, targetUserId, operatorMember.userId(), now, reason);
            governanceRepository.saveBanRecord(banRecord);

            Optional<RoomMember> targetMember = roomMemberRepository.findMember(roomId, targetUserId);
            List<Runnable> sideEffects = new ArrayList<>();
            if (targetMember.isPresent()) {
                RemovalOutcome outcome = removeTargetFromRoom(room, targetMember.get(), targetSession, now);
                sideEffects.add(() -> roomEventService.recordUserBanned(
                        outcome.updatedRoom(),
                        operatorMember.userId(),
                        targetUserId,
                        targetMember.get().nickname(),
                        reason,
                        now
                ));
                if (outcome.ownerTransferred() && outcome.newOwnerUserId() != null) {
                    sideEffects.add(() -> roomEventService.recordOwnerTransfer(
                            outcome.updatedRoom(),
                            targetUserId,
                            outcome.newOwnerUserId()
                    ));
                }
                if (outcome.updatedRoom().status() == RoomStatus.EMPTY && outcome.updatedRoom().emptySince() != null) {
                    long emptySince = outcome.updatedRoom().emptySince();
                    sideEffects.add(() -> roomEventService.recordRoomEmpty(outcome.updatedRoom(), emptySince));
                }
                return new LockedResult<>(
                        new BanMemberResult(true, targetUserId, true),
                        List.copyOf(sideEffects)
                );
            }

            sideEffects.add(() -> roomEventService.recordUserBanned(
                    room,
                    operatorMember.userId(),
                    targetUserId,
                    targetSession.nickname(),
                    reason,
                    now
            ));
            return new LockedResult<>(
                    new BanMemberResult(true, targetUserId, false),
                    List.copyOf(sideEffects)
            );
        });
    }

    private <T> T withLocks(String operatorUserId, String roomId, String targetUserId, Supplier<LockedResult<T>> supplier) {
        LockedResult<T> result = roomLock.supply(lockKeys(operatorUserId, roomId, targetUserId), supplier);
        for (Runnable pendingSideEffect : result.pendingSideEffects()) {
            pendingSideEffect.run();
        }
        return result.value();
    }

    private <T> LockedResult<T> lockedResult(T value, Runnable... pendingSideEffects) {
        return new LockedResult<>(value, List.of(pendingSideEffects));
    }

    private List<String> lockKeys(String operatorUserId, String roomId, String targetUserId) {
        List<String> userLocks = new ArrayList<>();
        if (operatorUserId != null && !operatorUserId.isBlank()) {
            userLocks.add(userLockKey(operatorUserId));
        }
        if (targetUserId != null && !targetUserId.isBlank() && !targetUserId.equals(operatorUserId)) {
            userLocks.add(userLockKey(targetUserId));
        }
        userLocks.sort(Comparator.naturalOrder());
        if (roomId != null && !roomId.isBlank()) {
            userLocks.add(roomLockKey(roomId));
        }
        return userLocks;
    }

    private String userLockKey(String userId) {
        return "user:" + userId;
    }

    private String roomLockKey(String roomId) {
        return "room:" + roomId;
    }

    private Room loadGovernableRoom(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "roomId must not be blank");
        }
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (isExpired(room)) {
            throw new BusinessException(ErrorCode.ROOM_EXPIRED);
        }
        return ensureOwnerInvariant(room, roomMemberRepository.listMembers(roomId));
    }

    private UserSession loadExistingUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "targetUserId must not be blank");
        }
        return userSessionRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private RoomMember requireOwnerMember(Room room, String operatorUserId) {
        loadExistingUser(operatorUserId);
        RoomMember operatorMember = roomMemberRepository.findMember(room.roomId(), operatorUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (!operatorUserId.equals(room.ownerUserId()) || !operatorMember.isOwner()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return operatorMember;
    }

    private void ensureTargetIsNotOwner(Room room, String targetUserId) {
        if (targetUserId != null && targetUserId.equals(room.ownerUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private String normalizeReason(String reason) {
        return reason == null ? null : reason.trim();
    }

    private RemovalOutcome removeTargetFromRoom(Room room, RoomMember targetMember, UserSession targetSession, long now) {
        roomMemberRepository.removeMember(room.roomId(), targetMember.userId());
        userSessionRepository.removeReconnectingUser(targetMember.userId());
        userSessionRepository.save(new UserSession(
                targetSession.userId(),
                targetSession.nickname(),
                null,
                UserStatus.ONLINE,
                targetSession.connected(),
                targetSession.lastConnectedAt(),
                targetSession.lastDisconnectedAt(),
                targetSession.createdAt(),
                now
        ));

        List<RoomMember> remainingMembers = roomMemberRepository.listMembers(room.roomId());
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
            roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, room.roomId(), now);
            roomIndexRepository.zAdd(RoomIndexKey.EMPTY, room.roomId(), now);
        } else {
            if (targetMember.isOwner()) {
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
            roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, room.roomId(), now);
            roomIndexRepository.zRem(RoomIndexKey.EMPTY, room.roomId());
        }

        return new RemovalOutcome(updatedRoom, ownerTransferred, newOwnerUserId);
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

    private record RemovalOutcome(Room updatedRoom, boolean ownerTransferred, String newOwnerUserId) {
    }

    private record LockedResult<T>(T value, List<Runnable> pendingSideEffects) {
    }
}
