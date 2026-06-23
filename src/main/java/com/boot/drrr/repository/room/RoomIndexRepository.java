package com.boot.drrr.repository.room;

import com.boot.drrr.common.redis.RedisKeys;
import com.boot.drrr.repository.RedisJsonOperations;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class RoomIndexRepository {
    public enum RoomIndexKey {
        ACTIVE(RedisKeys.ROOM_ACTIVE),
        EMPTY(RedisKeys.ROOM_EMPTY);

        private final String redisKey;

        RoomIndexKey(String redisKey) {
            this.redisKey = redisKey;
        }

        String redisKey() {
            return redisKey;
        }
    }

    private final RedisJsonOperations redisOps;

    public RoomIndexRepository(RedisJsonOperations redisOps) {
        this.redisOps = redisOps;
    }

    public void zAdd(RoomIndexKey indexKey, String member, double score) {
        redisOps.zsets().add(indexKey.redisKey(), member, score);
    }

    public void zRem(RoomIndexKey indexKey, String member) {
        redisOps.zsets().remove(indexKey.redisKey(), member);
    }

    public Set<String> zRange(RoomIndexKey indexKey, long start, long end) {
        Set<String> members = redisOps.zsets().range(indexKey.redisKey(), start, end);
        return members == null ? Set.of() : new LinkedHashSet<>(members);
    }

    public Set<String> zReverseRange(RoomIndexKey indexKey, long start, long end) {
        Set<String> members = redisOps.zsets().reverseRange(indexKey.redisKey(), start, end);
        return members == null ? Set.of() : new LinkedHashSet<>(members);
    }

    public Set<String> zRangeByScore(RoomIndexKey indexKey, double minScoreInclusive, double maxScoreInclusive) {
        Set<String> members = redisOps.zsets().rangeByScore(
                indexKey.redisKey(),
                minScoreInclusive,
                maxScoreInclusive
        );
        return members == null ? Set.of() : new LinkedHashSet<>(members);
    }
}
