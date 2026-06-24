package com.boot.drrr.web.dto.room;

import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import jakarta.validation.constraints.NotNull;

public record HistoryStrategyPayload(
        @NotNull HistoryStrategyType type,
        Integer value
) {
    public HistoryStrategy toDomain() {
        return new HistoryStrategy(type, value);
    }
}
