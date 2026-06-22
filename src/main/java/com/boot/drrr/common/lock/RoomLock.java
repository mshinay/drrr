package com.boot.drrr.common.lock;

import java.util.function.Supplier;

public interface RoomLock {
    void execute(String roomId, Runnable action);

    <T> T supply(String roomId, Supplier<T> supplier);

    void release(String roomId);
}
