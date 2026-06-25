package com.boot.drrr.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class FoundationConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
