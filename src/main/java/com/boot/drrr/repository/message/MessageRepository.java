package com.boot.drrr.repository.message;

import com.boot.drrr.common.redis.RedisKeys;
import com.boot.drrr.domain.message.Message;
import com.boot.drrr.repository.RedisJsonOperations;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
    private final RedisJsonOperations redisOps;

    public MessageRepository(RedisJsonOperations redisOps) {
        this.redisOps = redisOps;
    }

    public void append(Message message) {
        redisOps.lists().rightPush(RedisKeys.roomMessages(message.roomId()), redisOps.encode(message));
    }

    public List<Message> listMessages(String roomId) {
        List<String> values = redisOps.lists().range(RedisKeys.roomMessages(roomId), 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return redisOps.decodeList(values, Message.class);
    }

    public void trim(String roomId, long start, long end) {
        redisOps.lists().trim(RedisKeys.roomMessages(roomId), start, end);
    }

    public void deleteAll(String roomId) {
        redisOps.deleteKey(RedisKeys.roomMessages(roomId));
    }
}
