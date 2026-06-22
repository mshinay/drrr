package com.boot.drrr.common.time;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class ClockTimeProvider implements TimeProvider {
    private final Clock clock;

    public ClockTimeProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return Instant.now(clock);
    }

    @Override
    public long nowMillis() {
        return now().toEpochMilli();
    }

    @Override
    public Clock clock() {
        return clock;
    }
}
