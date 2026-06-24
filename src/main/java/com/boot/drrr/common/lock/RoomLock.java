package com.boot.drrr.common.lock;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public interface RoomLock {
    default void execute(String lockKey, Runnable action) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        execute(List.of(lockKey), action);
    }

    void execute(List<String> lockKeys, Runnable action);

    default <T> T supply(String lockKey, Supplier<T> supplier) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");
        return supply(List.of(lockKey), supplier);
    }

    <T> T supply(List<String> lockKeys, Supplier<T> supplier);

    void release(String lockKey);
}
