package com.boot.drrr.common.time;

import java.time.Clock;
import java.time.Instant;

public interface TimeProvider {
    Instant now();

    long nowMillis();

    Clock clock();
}
