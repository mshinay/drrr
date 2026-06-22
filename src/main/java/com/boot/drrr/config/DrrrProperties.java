package com.boot.drrr.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "drrr")
public class DrrrProperties {
    @Valid
    private final User user = new User();

    @Valid
    private final Lobby lobby = new Lobby();

    @Valid
    private final Room room = new Room();

    @Valid
    private final Websocket websocket = new Websocket();

    public User getUser() {
        return user;
    }

    public Lobby getLobby() {
        return lobby;
    }

    public Room getRoom() {
        return room;
    }

    public Websocket getWebsocket() {
        return websocket;
    }

    public static class User {
        @NotNull
        private Duration reconnectTimeout = Duration.ofMinutes(5);

        public Duration getReconnectTimeout() {
            return reconnectTimeout;
        }

        public void setReconnectTimeout(Duration reconnectTimeout) {
            this.reconnectTimeout = reconnectTimeout;
        }
    }

    public static class Lobby {
        @NotNull
        private Duration activeWindow = Duration.ofMinutes(5);

        public Duration getActiveWindow() {
            return activeWindow;
        }

        public void setActiveWindow(Duration activeWindow) {
            this.activeWindow = activeWindow;
        }
    }

    public static class Room {
        @NotNull
        private Duration emptyExpiry = Duration.ofHours(24);

        @Min(1)
        private int maxMembersMin = 1;

        @Min(1)
        @Max(20)
        private int maxMembersMax = 20;

        public Duration getEmptyExpiry() {
            return emptyExpiry;
        }

        public void setEmptyExpiry(Duration emptyExpiry) {
            this.emptyExpiry = emptyExpiry;
        }

        public int getMaxMembersMin() {
            return maxMembersMin;
        }

        public void setMaxMembersMin(int maxMembersMin) {
            this.maxMembersMin = maxMembersMin;
        }

        public int getMaxMembersMax() {
            return maxMembersMax;
        }

        public void setMaxMembersMax(int maxMembersMax) {
            this.maxMembersMax = maxMembersMax;
        }
    }

    public static class Websocket {
        @NotBlank
        private String endpoint = "/ws/rooms";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }
}
