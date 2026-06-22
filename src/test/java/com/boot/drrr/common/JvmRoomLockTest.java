package com.boot.drrr.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.lock.JvmRoomLock;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class JvmRoomLockTest {
    @Test
    void roomLockStaysRegisteredAfterExecutionUntilReleased() throws Exception {
        JvmRoomLock roomLock = new JvmRoomLock();

        roomLock.execute("r_1", () -> {
        });

        assertThat(lockMap(roomLock)).containsKey("r_1");

        roomLock.release("r_1");

        assertThat(lockMap(roomLock)).doesNotContainKey("r_1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, ReentrantLock> lockMap(JvmRoomLock roomLock) throws Exception {
        Field field = JvmRoomLock.class.getDeclaredField("locks");
        field.setAccessible(true);
        return (Map<String, ReentrantLock>) field.get(roomLock);
    }
}
