package com.boot.drrr.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.config.DrrrProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
        "drrr.user.reconnect-timeout=7m",
        "drrr.lobby.active-window=6m",
        "drrr.room.empty-expiry=26h",
        "drrr.room.max-members-min=2",
        "drrr.room.max-members-max=12",
        "drrr.websocket.endpoint=/ws/test-rooms"
})
class TimeProviderAndPropertiesTest {

    @Autowired
    private TimeProvider timeProvider;

    @Autowired
    private DrrrProperties properties;

    @Test
    void timeProviderUsesInjectedClock() {
        assertThat(timeProvider.now()).isEqualTo(Instant.parse("2026-06-20T10:15:30Z"));
        assertThat(timeProvider.nowMillis()).isEqualTo(Instant.parse("2026-06-20T10:15:30Z").toEpochMilli());
        assertThat(timeProvider.clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void propertiesBindConfiguredFoundationValues() {
        assertThat(properties.getUser().getReconnectTimeout()).isEqualTo(Duration.ofMinutes(7));
        assertThat(properties.getLobby().getActiveWindow()).isEqualTo(Duration.ofMinutes(6));
        assertThat(properties.getRoom().getEmptyExpiry()).isEqualTo(Duration.ofHours(26));
        assertThat(properties.getRoom().getMaxMembersMin()).isEqualTo(2);
        assertThat(properties.getRoom().getMaxMembersMax()).isEqualTo(12);
        assertThat(properties.getWebsocket().getEndpoint()).isEqualTo("/ws/test-rooms");
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-06-20T10:15:30Z"), ZoneOffset.UTC);
        }
    }
}
