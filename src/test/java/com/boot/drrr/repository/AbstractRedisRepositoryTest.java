package com.boot.drrr.repository;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;

abstract class AbstractRedisRepositoryTest {

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void clearRedis() {
        try (RedisConnection connection = RedisConnectionUtils.getConnection(redisConnectionFactory)) {
            connection.serverCommands().flushDb();
        } catch (Exception exception) {
            assumeTrue(false, "Redis test instance is not reachable at localhost:6379: " + exception.getMessage());
        }
    }
}
