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
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.repository.governance.GovernanceRepository;
import com.boot.drrr.repository.message.MessageRepository;
import com.boot.drrr.repository.room.RoomMemberRepository;
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
    private final RoomMemberRepository roomMemberRepository;
    private final GovernanceRepository governanceRepository;
    private final MessageRepository messageRepository;
    private final RoomMessagePersistence roomMessagePersistence;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final RoomLock roomLock;

    public MessageService(
            UserSessionService userSessionService,
            RoomMemberRepository roomMemberRepository,
            GovernanceRepository governanceRepository,
            MessageRepository messageRepository,
            RoomMessagePersistence roomMessagePersistence,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            RoomLock roomLock
    ) {
        this.userSessionService = userSessionService;
        this.roomMemberRepository = roomMemberRepository;
        this.governanceRepository = governanceRepository;
        this.messageRepository = messageRepository;
        this.roomMessagePersistence = roomMessagePersistence;
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
            roomMessagePersistence.store(context.room(), message, now);
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
            roomMessagePersistence.store(context.room(), message, now);
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
        return switch (message.type()) {
            case PUBLIC -> true;
            case DIRECT -> message.visibleTo() != null && message.visibleTo().contains(viewerUserId);
            case SYSTEM -> message.visibleTo() == null || message.visibleTo().isEmpty() || message.visibleTo().contains(viewerUserId);
        };
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
