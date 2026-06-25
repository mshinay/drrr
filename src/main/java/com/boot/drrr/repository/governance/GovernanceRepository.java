package com.boot.drrr.repository.governance;

import com.boot.drrr.common.redis.RedisKeys;
import com.boot.drrr.domain.governance.BanRecord;
import com.boot.drrr.domain.governance.MuteRecord;
import com.boot.drrr.repository.RedisJsonOperations;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class GovernanceRepository {
    private final RedisJsonOperations redisOps;

    public GovernanceRepository(RedisJsonOperations redisOps) {
        this.redisOps = redisOps;
    }

    public void saveMuteRecord(MuteRecord muteRecord) {
        redisOps.zsets().add(RedisKeys.roomMute(muteRecord.roomId()), muteRecord.userId(), muteRecord.endAt());
        redisOps.values().set(
                RedisKeys.roomMuteDetail(muteRecord.roomId(), muteRecord.userId()),
                redisOps.encode(muteRecord)
        );
    }

    public Optional<MuteRecord> findMuteRecord(String roomId, String userId) {
        return redisOps.decodeOptional(
                redisOps.values().get(RedisKeys.roomMuteDetail(roomId, userId)),
                MuteRecord.class
        );
    }

    public boolean hasMuteIndexEntry(String roomId, String userId) {
        return redisOps.zsets().score(RedisKeys.roomMute(roomId), userId) != null;
    }

    public Set<String> listMutedUserIdsByScore(String roomId, double minScoreInclusive, double maxScoreInclusive) {
        Set<String> userIds = redisOps.zsets().rangeByScore(
                RedisKeys.roomMute(roomId),
                minScoreInclusive,
                maxScoreInclusive
        );
        return userIds == null ? Set.of() : new LinkedHashSet<>(userIds);
    }

    public void clearMute(String roomId, String userId) {
        redisOps.zsets().remove(RedisKeys.roomMute(roomId), userId);
        redisOps.deleteKey(RedisKeys.roomMuteDetail(roomId, userId));
    }

    public void deleteMuteState(String roomId) {
        for (String userId : listMutedUserIdsByScore(roomId, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            redisOps.deleteKey(RedisKeys.roomMuteDetail(roomId, userId));
        }
        redisOps.deleteKey(RedisKeys.roomMute(roomId));
    }

    public void saveBanRecord(BanRecord banRecord) {
        redisOps.sets().add(RedisKeys.roomBan(banRecord.roomId()), banRecord.userId());
        redisOps.values().set(
                RedisKeys.roomBanDetail(banRecord.roomId(), banRecord.userId()),
                redisOps.encode(banRecord)
        );
    }

    public Optional<BanRecord> findBanRecord(String roomId, String userId) {
        return redisOps.decodeOptional(
                redisOps.values().get(RedisKeys.roomBanDetail(roomId, userId)),
                BanRecord.class
        );
    }

    public boolean hasBanIndexEntry(String roomId, String userId) {
        Boolean banned = redisOps.sets().isMember(RedisKeys.roomBan(roomId), userId);
        return Boolean.TRUE.equals(banned);
    }

    public Set<String> listBanUserIds(String roomId) {
        Set<String> userIds = redisOps.sets().members(RedisKeys.roomBan(roomId));
        return userIds == null ? Set.of() : new LinkedHashSet<>(userIds);
    }

    public void clearBan(String roomId, String userId) {
        redisOps.sets().remove(RedisKeys.roomBan(roomId), userId);
        redisOps.deleteKey(RedisKeys.roomBanDetail(roomId, userId));
    }

    public void deleteBanState(String roomId) {
        for (String userId : listBanUserIds(roomId)) {
            redisOps.deleteKey(RedisKeys.roomBanDetail(roomId, userId));
        }
        redisOps.deleteKey(RedisKeys.roomBan(roomId));
    }
}
