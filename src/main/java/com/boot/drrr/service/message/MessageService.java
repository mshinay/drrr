package com.boot.drrr.service.message;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.lock.RoomLock;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.domain.governance.MuteRecord;
import com.boot.drrr.domain.message.Message;
import com.boot.drrr.domain.message.MessageType;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.repository.governance.GovernanceRepository;
import com.boot.drrr.repository.message.MessageRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomIndexRepository.RoomIndexKey;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.service.user.RoomSessionContext;
import com.boot.drrr.service.user.UserSessionService;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class MessageService {
    private final UserSessionService userSessionService;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final GovernanceRepository governanceRepository;
    private final MessageRepository messageRepository;
    private final RoomIndexRepository roomIndexRepository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final RoomLock roomLock;

    public MessageService(
            UserSessionService userSessionService,
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            GovernanceRepository governanceRepository,
            MessageRepository messageRepository,
            RoomIndexRepository roomIndexRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            RoomLock roomLock
    ) {
        this.userSessionService = userSessionService;
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.governanceRepository = governanceRepository;
        this.messageRepository = messageRepository;
        this.roomIndexRepository = roomIndexRepository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.roomLock = roomLock;
    }

    public Message sendPublicMessage(SendPublicMessageCommand command) {
        validateRoomAndSender(command.roomId(), command.senderUserId());
        String content = normalizeContent(command.content());
        return roomLock.supply(lockKey(command.roomId()), () -> {
            RoomSessionContext context = userSessionService.validateRoomConnection(command.senderUserId(), command.roomId());
            ensureSenderCanChat(context.roomId(), context.userId());
            long now = timeProvider.nowMillis();
            Message message = new Message(
                    idGenerator.newMessageId(),
                    context.roomId(),
                    MessageType.PUBLIC,
                    context.userId(),
                    context.roomMember().nickname(),
                    null,
                    null,
                    content,
                    List.of(),
                    null,
                    null,
                    now
            );
            persistMessage(context.room(), message, now);
            return message;
        });
    }

    public Message sendDirectMessage(SendDirectMessageCommand command) {
        validateRoomAndSender(command.roomId(), command.senderUserId());
        if (command.targetUserId() == null || command.targetUserId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "targetUserId must not be blank");
        }
        String content = normalizeContent(command.content());
        return roomLock.supply(lockKey(command.roomId()), () -> {
            RoomSessionContext context = userSessionService.validateRoomConnection(command.senderUserId(), command.roomId());
            ensureSenderCanChat(context.roomId(), context.userId());
            RoomMember targetMember = roomMemberRepository.findMember(command.roomId(), command.targetUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_FOUND));
            long now = timeProvider.nowMillis();
            Message message = new Message(
                    idGenerator.newMessageId(),
                    context.roomId(),
                    MessageType.DIRECT,
                    context.userId(),
                    context.roomMember().nickname(),
                    targetMember.userId(),
                    targetMember.nickname(),
                    content,
                    List.copyOf(visibleUsers(context.userId(), targetMember.userId())),
                    null,
                    null,
                    now
            );
            persistMessage(context.room(), message, now);
            return message;
        });
    }

    public List<Message> readVisibleHistory(String roomId, String viewerUserId) {
        validateRoomAndSender(roomId, viewerUserId);
        RoomSessionContext context = userSessionService.validateRoomConnection(viewerUserId, roomId);
        return filterVisibleMessages(
                applyHistoryStrategy(context.room().historyStrategy(), messageRepository.listMessages(roomId), timeProvider.nowMillis()),
                viewerUserId
        );
    }

    private void validateRoomAndSender(String roomId, String senderUserId) {
        if (roomId == null || roomId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "roomId must not be blank");
        }
        if (senderUserId == null || senderUserId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "senderUserId must not be blank");
        }
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "content must not be blank");
        }
        return content.trim();
    }

    private void ensureSenderCanChat(String roomId, String senderUserId) {
        if (!governanceRepository.hasMuteIndexEntry(roomId, senderUserId)) {
            return;
        }

        long now = timeProvider.nowMillis();
        MuteRecord muteRecord = governanceRepository.findMuteRecord(roomId, senderUserId).orElse(null);
        if (muteRecord == null || muteRecord.endAt() <= now) {
            governanceRepository.clearMute(roomId, senderUserId);
            return;
        }

        throw new BusinessException(ErrorCode.USER_MUTED);
    }

    private void persistMessage(Room room, Message message, long now) {
        messageRepository.append(message);
        trimStoredHistory(room.roomId(), room.historyStrategy(), now);

        Room refreshedRoom = new Room(
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
        roomRepository.save(refreshedRoom);
        roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, room.roomId(), now);
        roomIndexRepository.zRem(RoomIndexKey.EMPTY, room.roomId());
    }

    private void trimStoredHistory(String roomId, HistoryStrategy historyStrategy, long now) {
        List<Message> messages = messageRepository.listMessages(roomId);
        if (messages.isEmpty()) {
            return;
        }

        switch (historyStrategy.type()) {
            case NONE -> messageRepository.deleteAll(roomId);
            case COUNT -> trimCountHistory(roomId, messages, historyStrategy.value());
            case MINUTES -> trimMinuteHistory(roomId, messages, historyStrategy.value(), now);
        }
    }

    private void trimCountHistory(String roomId, List<Message> messages, Integer keepCount) {
        int keep = keepCount == null ? 0 : keepCount;
        if (keep <= 0) {
            messageRepository.deleteAll(roomId);
            return;
        }
        if (messages.size() <= keep) {
            return;
        }
        int start = messages.size() - keep;
        messageRepository.trim(roomId, start, messages.size() - 1L);
    }

    private void trimMinuteHistory(String roomId, List<Message> messages, Integer minutes, long now) {
        int keepMinutes = minutes == null ? 0 : minutes;
        if (keepMinutes <= 0) {
            messageRepository.deleteAll(roomId);
            return;
        }

        long cutoff = now - Duration.ofMinutes(keepMinutes).toMillis();
        int firstVisibleIndex = 0;
        while (firstVisibleIndex < messages.size() && messages.get(firstVisibleIndex).sentAt() < cutoff) {
            firstVisibleIndex++;
        }

        if (firstVisibleIndex <= 0) {
            return;
        }
        if (firstVisibleIndex >= messages.size()) {
            messageRepository.deleteAll(roomId);
            return;
        }
        messageRepository.trim(roomId, firstVisibleIndex, messages.size() - 1L);
    }

    private List<Message> applyHistoryStrategy(HistoryStrategy historyStrategy, List<Message> messages, long now) {
        return switch (historyStrategy.type()) {
            case NONE -> List.of();
            case COUNT -> {
                int keep = historyStrategy.value() == null ? 0 : historyStrategy.value();
                if (keep <= 0 || messages.isEmpty()) {
                    yield List.of();
                }
                int fromIndex = Math.max(0, messages.size() - keep);
                yield messages.subList(fromIndex, messages.size());
            }
            case MINUTES -> {
                int keepMinutes = historyStrategy.value() == null ? 0 : historyStrategy.value();
                if (keepMinutes <= 0 || messages.isEmpty()) {
                    yield List.of();
                }
                long cutoff = now - Duration.ofMinutes(keepMinutes).toMillis();
                yield messages.stream()
                        .filter(message -> message.sentAt() >= cutoff)
                        .toList();
            }
        };
    }

    private List<Message> filterVisibleMessages(List<Message> messages, String viewerUserId) {
        return messages.stream()
                .filter(message -> isVisibleTo(message, viewerUserId))
                .toList();
    }

    private boolean isVisibleTo(Message message, String viewerUserId) {
        if (message.type() != MessageType.DIRECT) {
            return true;
        }
        return message.visibleTo() != null && message.visibleTo().contains(viewerUserId);
    }

    private Set<String> visibleUsers(String senderUserId, String targetUserId) {
        LinkedHashSet<String> visibleUsers = new LinkedHashSet<>();
        visibleUsers.add(senderUserId);
        visibleUsers.add(targetUserId);
        return visibleUsers;
    }

    private String lockKey(String roomId) {
        return "room:" + roomId;
    }
}
