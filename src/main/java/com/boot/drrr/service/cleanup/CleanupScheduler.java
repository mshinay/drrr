package com.boot.drrr.service.cleanup;

import com.boot.drrr.config.DrrrProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CleanupScheduler {
    private final CleanupService cleanupService;
    private final DrrrProperties drrrProperties;

    public CleanupScheduler(CleanupService cleanupService, DrrrProperties drrrProperties) {
        this.cleanupService = cleanupService;
        this.drrrProperties = drrrProperties;
    }

    @Scheduled(fixedDelayString = "${drrr.cleanup.reconnect-scan-fixed-delay-ms:60000}")
    public void cleanupReconnectingUsers() {
        cleanupService.cleanupReconnectingUsersTimedOut(drrrProperties.getUser().getReconnectTimeout().toMillis());
    }

    @Scheduled(fixedDelayString = "${drrr.cleanup.empty-room-scan-fixed-delay-ms:60000}")
    public void cleanupExpiredEmptyRooms() {
        cleanupService.cleanupExpiredEmptyRooms(drrrProperties.getRoom().getEmptyExpiry().toMillis());
    }
}
