package com.boot.drrr.repository.lobby;

import com.boot.drrr.common.redis.RedisKeys;
import com.boot.drrr.repository.RedisJsonOperations;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class LobbyRepository {
    private final RedisJsonOperations redisOps;

    public LobbyRepository(RedisJsonOperations redisOps) {
        this.redisOps = redisOps;
    }

    public void zAdd(String member, double score) {
        redisOps.zsets().add(RedisKeys.LOBBY_ACTIVE_USERS, member, score);
    }

    public void zRem(String member) {
        redisOps.zsets().remove(RedisKeys.LOBBY_ACTIVE_USERS, member);
    }

    public long zCountByScore(double minScoreInclusive, double maxScoreInclusive) {
        Long count = redisOps.zsets().count(RedisKeys.LOBBY_ACTIVE_USERS, minScoreInclusive, maxScoreInclusive);
        return count == null ? 0L : count;
    }

    public Set<String> zRangeByScore(double minScoreInclusive, double maxScoreInclusive) {
        Set<String> members = redisOps.zsets().rangeByScore(
                RedisKeys.LOBBY_ACTIVE_USERS,
                minScoreInclusive,
                maxScoreInclusive
        );
        return members == null ? Set.of() : new LinkedHashSet<>(members);
    }
}
