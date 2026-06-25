package com.boot.drrr.service.message;

import com.boot.drrr.domain.message.Message;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.repository.message.MessageRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomIndexRepository.RoomIndexKey;
import com.boot.drrr.repository.room.RoomRepository;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RoomMessagePersistence {
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final RoomIndexRepository roomIndexRepository;

    public RoomMessagePersistence(
            MessageRepository messageRepository,
            RoomRepository roomRepository,
            RoomIndexRepository roomIndexRepository
    ) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.roomIndexRepository = roomIndexRepository;
    }

    public Message store(Room room, Message message, long now) {
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
                room.status(),
                room.userListVisible(),
                room.historyStrategy(),
                room.allowOwnerConfigChange(),
                room.createdAt(),
                now,
                room.emptySince()
        );
        roomRepository.save(refreshedRoom);
        roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, room.roomId(), now);
        if (room.status() == RoomStatus.EMPTY && room.emptySince() != null) {
            roomIndexRepository.zAdd(RoomIndexKey.EMPTY, room.roomId(), room.emptySince());
        } else {
            roomIndexRepository.zRem(RoomIndexKey.EMPTY, room.roomId());
        }
        return message;
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
}
