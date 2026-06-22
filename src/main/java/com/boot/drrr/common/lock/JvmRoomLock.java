package com.boot.drrr.common.lock;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class JvmRoomLock implements RoomLock {
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public void execute(String roomId, Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        supply(roomId, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T supply(String roomId, Supplier<T> supplier) {
        Objects.requireNonNull(roomId, "roomId must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");

        ReentrantLock lock = locks.computeIfAbsent(roomId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(String roomId) {
        Objects.requireNonNull(roomId, "roomId must not be null");

        locks.computeIfPresent(roomId, (ignored, lock) -> {
            if (lock.isLocked() || lock.hasQueuedThreads()) {
                return lock;
            }
            return null;
        });
    }
}
