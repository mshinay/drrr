package com.boot.drrr.service.user;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
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
import org.springframework.stereotype.Service;

@Service
public class UserSessionService {
    private final UserSessionRepository userSessionRepository;
    private final LobbyRepository lobbyRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomEventService roomEventService;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final DrrrProperties drrrProperties;

    public UserSessionService(
            UserSessionRepository userSessionRepository,
            LobbyRepository lobbyRepository,
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            RoomEventService roomEventService,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            DrrrProperties drrrProperties
    ) {
        this.userSessionRepository = userSessionRepository;
        this.lobbyRepository = lobbyRepository;
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.roomEventService = roomEventService;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.drrrProperties = drrrProperties;
    }

    public UserSession createAnonymousSession(String nickname) {
        long now = timeProvider.nowMillis();
        UserSession userSession = new UserSession(
                idGenerator.newUserId(),
                nickname.trim(),
                null,
                UserStatus.ONLINE,
                false,
                null,
                null,
                now,
                now
        );
        userSessionRepository.save(userSession);
        lobbyRepository.zAdd(userSession.userId(), now);
        return userSession;
    }

    public RoomSessionContext validateRoomConnection(String userId, String roomId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "userId must not be blank");
        }
        if (roomId == null || roomId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "roomId must not be blank");
        }

        UserSession userSession = userSessionRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!roomId.equals(userSession.currentRoomId())) {
            throw new BusinessException(ErrorCode.ROOM_CONTEXT_MISMATCH);
        }

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (isExpired(room)) {
            throw new BusinessException(ErrorCode.ROOM_EXPIRED);
        }
        if (!roomMemberRepository.existsMemberOrder(roomId, userId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }

        RoomMember roomMember = roomMemberRepository.findMember(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        return new RoomSessionContext(roomId, userId, userSession, room, roomMember);
    }

    public RoomSessionContext markRoomConnected(String userId, String roomId) {
        RoomSessionContext context = validateRoomConnection(userId, roomId);
        boolean shouldRecordReconnect = context.userSession().status() == UserStatus.RECONNECTING
                || context.roomMember().memberStatus() == MemberStatus.RECONNECTING
                || userSessionRepository.isReconnectingUser(userId);
        long now = timeProvider.nowMillis();

        UserSession updatedSession = new UserSession(
                context.userSession().userId(),
                context.userSession().nickname(),
                context.userSession().currentRoomId(),
                UserStatus.ONLINE,
                true,
                now,
                context.userSession().lastDisconnectedAt(),
                context.userSession().createdAt(),
                now
        );
        RoomMember updatedMember = new RoomMember(
                context.roomMember().roomId(),
                context.roomMember().userId(),
                context.roomMember().nickname(),
                MemberStatus.ONLINE,
                context.roomMember().joinedAt(),
                now,
                context.roomMember().isOwner()
        );

        userSessionRepository.save(updatedSession);
        userSessionRepository.removeReconnectingUser(userId);
        roomMemberRepository.save(updatedMember);
        if (shouldRecordReconnect) {
            roomEventService.recordUserReconnected(context.room(), updatedMember, now);
        }
        return new RoomSessionContext(roomId, userId, updatedSession, context.room(), updatedMember);
    }

    public void markRoomDisconnected(String userId, String roomId) {
        if (userId == null || userId.isBlank() || roomId == null || roomId.isBlank()) {
            return;
        }

        UserSession userSession = userSessionRepository.findById(userId).orElse(null);
        if (userSession == null || !roomId.equals(userSession.currentRoomId())) {
            return;
        }

        long now = timeProvider.nowMillis();
        UserSession reconnectingSession = new UserSession(
                userSession.userId(),
                userSession.nickname(),
                userSession.currentRoomId(),
                UserStatus.RECONNECTING,
                false,
                userSession.lastConnectedAt(),
                now,
                userSession.createdAt(),
                now
        );
        userSessionRepository.save(reconnectingSession);
        userSessionRepository.saveReconnectingUser(userId, now);

        RoomMember updatedMember = roomMemberRepository.findMember(roomId, userId)
                .map(member -> {
                    RoomMember reconnectingMember = new RoomMember(
                            member.roomId(),
                            member.userId(),
                            member.nickname(),
                            MemberStatus.RECONNECTING,
                            member.joinedAt(),
                            now,
                            member.isOwner()
                    );
                    roomMemberRepository.save(reconnectingMember);
                    return reconnectingMember;
                })
                .orElse(null);

        Room room = roomRepository.findById(roomId).orElse(null);
        if (updatedMember != null && room != null && !isExpired(room)) {
            roomEventService.recordUserReconnecting(room, updatedMember, now);
        }
    }

    private boolean isExpired(Room room) {
        return room.status() == RoomStatus.EXPIRED
                || (room.status() == RoomStatus.EMPTY
                && room.emptySince() != null
                && room.emptySince() + drrrProperties.getRoom().getEmptyExpiry().toMillis() <= timeProvider.nowMillis());
    }
}
