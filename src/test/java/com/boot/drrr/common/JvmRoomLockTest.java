package com.boot.drrr.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.lock.JvmRoomLock;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class JvmRoomLockTest {
    @Test
    void roomLockAutoReleasesAfterExecution() throws Exception {
        JvmRoomLock roomLock = new JvmRoomLock();

        roomLock.execute("r_1", () -> {
        });

        assertThat(lockMap(roomLock)).doesNotContainKey("r_1");
    }

    @Test
    void multiKeySupplyUsesDeterministicOrderingWithoutDeadlock() throws Exception {
        JvmRoomLock roomLock = new JvmRoomLock();
        CountDownLatch insideFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean(false);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> roomLock.execute(List.of("room:r_1", "user:u_1"), () -> {
                insideFirst.countDown();
                try {
                    releaseFirst.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                }
            }));

            assertThat(insideFirst.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> roomLock.execute(List.of("user:u_1", "room:r_1"), () -> secondEntered.set(true)));

            Thread.sleep(100);
            assertThat(secondEntered.get()).isFalse();
            releaseFirst.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertThat(secondEntered.get()).isTrue();
        }

        assertThat(lockMap(roomLock)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, ReentrantLock> lockMap(JvmRoomLock roomLock) throws Exception {
        Field field = JvmRoomLock.class.getDeclaredField("locks");
        field.setAccessible(true);
        return (Map<String, ReentrantLock>) field.get(roomLock);
    }
}

