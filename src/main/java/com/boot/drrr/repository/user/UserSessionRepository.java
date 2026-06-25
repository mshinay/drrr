package com.boot.drrr.repository.user;

import com.boot.drrr.common.redis.RedisKeys;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.repository.RedisJsonOperations;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class UserSessionRepository {
    private final RedisJsonOperations redisOps;

    public UserSessionRepository(RedisJsonOperations redisOps) {
        this.redisOps = redisOps;
    }

    public void save(UserSession userSession) {
        redisOps.values().set(RedisKeys.user(userSession.userId()), redisOps.encode(userSession));
    }

    public Optional<UserSession> findById(String userId) {
        return redisOps.decodeOptional(redisOps.values().get(RedisKeys.user(userId)), UserSession.class);
    }

    public boolean exists(String userId) {
        return Boolean.TRUE.equals(redisOps.values().getOperations().hasKey(RedisKeys.user(userId)));
    }

    public void deleteById(String userId) {
        redisOps.deleteKey(RedisKeys.user(userId));
    }

    public void saveReconnectingUser(String userId, long lastDisconnectedAt) {
        redisOps.zsets().add(RedisKeys.USER_RECONNECTING, userId, lastDisconnectedAt);
    }

    public boolean isReconnectingUser(String userId) {
        return redisOps.zsets().score(RedisKeys.USER_RECONNECTING, userId) != null;
    }

    public void removeReconnectingUser(String userId) {
        redisOps.zsets().remove(RedisKeys.USER_RECONNECTING, userId);
    }

    public Set<String> listReconnectingUserIdsByScore(double minScoreInclusive, double maxScoreInclusive) {
        Set<String> userIds = redisOps.zsets().rangeByScore(
                RedisKeys.USER_RECONNECTING,
                minScoreInclusive,
                maxScoreInclusive
        );
        return userIds == null ? Set.of() : new LinkedHashSet<>(userIds);
    }
}
