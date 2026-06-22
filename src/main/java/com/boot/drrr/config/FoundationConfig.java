package com.boot.drrr.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FoundationConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
