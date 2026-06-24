package com.boot.drrr.common.lock;

import java.util.Comparator;
import java.util.List;
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
    public void execute(List<String> lockKeys, Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        supply(lockKeys, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T supply(List<String> lockKeys, Supplier<T> supplier) {
        Objects.requireNonNull(lockKeys, "lockKeys must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");

        List<String> normalizedKeys = lockKeys.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (normalizedKeys.isEmpty()) {
            throw new IllegalArgumentException("lockKeys must not be empty");
        }

        List<ReentrantLock> acquiredLocks = normalizedKeys.stream()
                .map(lockKey -> locks.computeIfAbsent(lockKey, ignored -> new ReentrantLock()))
                .toList();

        for (ReentrantLock lock : acquiredLocks) {
            lock.lock();
        }
        try {
            return supplier.get();
        } finally {
            for (int index = acquiredLocks.size() - 1; index >= 0; index--) {
                acquiredLocks.get(index).unlock();
            }
            for (String lockKey : normalizedKeys) {
                release(lockKey);
            }
        }
    }

    @Override
    public void release(String lockKey) {
        Objects.requireNonNull(lockKey, "lockKey must not be null");

        locks.computeIfPresent(lockKey, (ignored, lock) -> {
            if (lock.isLocked() || lock.hasQueuedThreads()) {
                return lock;
            }
            return null;
        });
    }
}
