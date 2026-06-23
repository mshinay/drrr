package com.boot.drrr.repository.room;

import com.boot.drrr.common.redis.RedisKeys;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.repository.RedisJsonOperations;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class RoomRepository {
    private final RedisJsonOperations redisOps;

    public RoomRepository(RedisJsonOperations redisOps) {
        this.redisOps = redisOps;
    }

    public void save(Room room) {
        redisOps.values().set(RedisKeys.room(room.roomId()), redisOps.encode(room));
    }

    public Optional<Room> findById(String roomId) {
        return redisOps.decodeOptional(redisOps.values().get(RedisKeys.room(roomId)), Room.class);
    }

    public void deleteById(String roomId) {
        redisOps.deleteKey(RedisKeys.room(roomId));
    }
}
