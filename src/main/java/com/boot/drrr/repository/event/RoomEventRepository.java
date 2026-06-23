package com.boot.drrr.repository.event;

import com.boot.drrr.common.redis.RedisKeys;
import com.boot.drrr.domain.event.RoomEvent;
import com.boot.drrr.repository.RedisJsonOperations;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class RoomEventRepository {
    private final RedisJsonOperations redisOps;

    public RoomEventRepository(RedisJsonOperations redisOps) {
        this.redisOps = redisOps;
    }

    public void append(RoomEvent roomEvent) {
        redisOps.lists().rightPush(RedisKeys.roomEvents(roomEvent.roomId()), redisOps.encode(roomEvent));
    }

    public List<RoomEvent> listEvents(String roomId) {
        List<String> values = redisOps.lists().range(RedisKeys.roomEvents(roomId), 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return redisOps.decodeList(values, RoomEvent.class);
    }

    public void deleteAll(String roomId) {
        redisOps.deleteKey(RedisKeys.roomEvents(roomId));
    }
}
