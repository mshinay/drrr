package com.boot.drrr.repository.room;

import com.boot.drrr.common.redis.RedisKeys;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.repository.RedisJsonOperations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RoomMemberRepository {
    private static final DefaultRedisScript<Long> SAVE_MEMBER_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1]); "
                    + "redis.call('HSET', KEYS[2], ARGV[1], ARGV[3]); "
                    + "return 1;",
            Long.class
    );

    private static final DefaultRedisScript<Long> REMOVE_MEMBER_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREM', KEYS[1], ARGV[1]); "
                    + "redis.call('HDEL', KEYS[2], ARGV[1]); "
                    + "return 1;",
            Long.class
    );

    private static final DefaultRedisScript<List> COUNT_MEMBERS_SCRIPT = new DefaultRedisScript<>(
            "local counts = {} "
                    + "for i, key in ipairs(KEYS) do "
                    + "counts[i] = redis.call('ZCARD', key) "
                    + "end "
                    + "return counts;",
            List.class
    );

    private final RedisJsonOperations redisOps;

    public RoomMemberRepository(RedisJsonOperations redisOps) {
        this.redisOps = redisOps;
    }

    public void save(RoomMember roomMember) {
        String memberIndexKey = RedisKeys.roomMembers(roomMember.roomId());
        String memberDetailKey = RedisKeys.roomMemberDetail(roomMember.roomId());
        String encodedMember = redisOps.encode(roomMember);

        redisOps.executeScript(
                SAVE_MEMBER_SCRIPT,
                List.of(memberIndexKey, memberDetailKey),
                roomMember.userId(),
                Double.toString(roomMember.joinedAt()),
                encodedMember
        );
    }

    public Optional<RoomMember> findMember(String roomId, String userId) {
        Object rawValue = redisOps.hashes().get(RedisKeys.roomMemberDetail(roomId), userId);
        return redisOps.decodeOptional((String) rawValue, RoomMember.class);
    }

    public boolean existsMemberOrder(String roomId, String userId) {
        return redisOps.zsets().score(RedisKeys.roomMembers(roomId), userId) != null;
    }

    public List<RoomMember> listMembers(String roomId) {
        var userIds = redisOps.zsets().range(RedisKeys.roomMembers(roomId), 0, -1);
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        List<String> orderedUserIds = new ArrayList<>(userIds);
        List<String> rawMembers = redisOps.hashes().multiGet(RedisKeys.roomMemberDetail(roomId), orderedUserIds);
        if (rawMembers == null || rawMembers.isEmpty()) {
            return List.of();
        }

        List<RoomMember> members = new ArrayList<>();
        for (int i = 0; i < orderedUserIds.size() && i < rawMembers.size(); i++) {
            String rawMember = rawMembers.get(i);
            if (rawMember == null) {
                continue;
            }
            redisOps.decodeOptional(rawMember, RoomMember.class).ifPresent(members::add);
        }
        return members;
    }

    public long countMembers(String roomId) {
        Long size = redisOps.zsets().zCard(RedisKeys.roomMembers(roomId));
        return size == null ? 0L : size;
    }

    public Map<String, Long> countMembersByRoomIds(List<String> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return Map.of();
        }

        List<String> keys = roomIds.stream()
                .map(RedisKeys::roomMembers)
                .toList();
        List<?> rawCounts = redisOps.executeScript(COUNT_MEMBERS_SCRIPT, keys);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < roomIds.size(); i++) {
            Object rawCount = rawCounts != null && i < rawCounts.size() ? rawCounts.get(i) : null;
            long count = rawCount instanceof Number number ? number.longValue() : 0L;
            counts.put(roomIds.get(i), count);
        }
        return counts;
    }

    public void removeMember(String roomId, String userId) {
        String memberIndexKey = RedisKeys.roomMembers(roomId);
        String memberDetailKey = RedisKeys.roomMemberDetail(roomId);

        redisOps.executeScript(
                REMOVE_MEMBER_SCRIPT,
                List.of(memberIndexKey, memberDetailKey),
                userId
        );
    }

    public void deleteAll(String roomId) {
        redisOps.deleteKey(RedisKeys.roomMembers(roomId));
        redisOps.deleteKey(RedisKeys.roomMemberDetail(roomId));
    }
}
