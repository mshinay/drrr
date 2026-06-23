package com.boot.drrr.repository;

import com.boot.drrr.common.json.JsonCodec;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisJsonOperations {
    private final RedisTemplate<String, String> redisTemplate;
    private final JsonCodec jsonCodec;

    public RedisJsonOperations(RedisTemplate<String, String> redisTemplate, JsonCodec jsonCodec) {
        this.redisTemplate = redisTemplate;
        this.jsonCodec = jsonCodec;
    }

    public ValueOperations<String, String> values() {
        return redisTemplate.opsForValue();
    }

    public HashOperations<String, String, String> hashes() {
        return redisTemplate.opsForHash();
    }

    public ListOperations<String, String> lists() {
        return redisTemplate.opsForList();
    }

    public SetOperations<String, String> sets() {
        return redisTemplate.opsForSet();
    }

    public ZSetOperations<String, String> zsets() {
        return redisTemplate.opsForZSet();
    }

    public void deleteKey(String key) {
        redisTemplate.delete(key);
    }

    public <T> T executeScript(RedisScript<T> script, List<String> keys, Object... args) {
        return redisTemplate.execute(script, keys, args);
    }

    public String encode(Object value) {
        return jsonCodec.encode(value);
    }

    public <T> Optional<T> decodeOptional(String value, Class<T> type) {
        return Optional.ofNullable(value).map(raw -> jsonCodec.decode(raw, type));
    }

    public <T> List<T> decodeList(Collection<String> rawValues, Class<T> type) {
        return rawValues.stream()
                .map(raw -> jsonCodec.decode(raw, type))
                .collect(Collectors.toList());
    }

    public <T> Set<T> decodeSet(Collection<String> rawValues, Class<T> type) {
        return rawValues.stream()
                .map(raw -> jsonCodec.decode(raw, type))
                .collect(Collectors.toSet());
    }
}
