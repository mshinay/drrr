package com.boot.drrr.common.api;

public record ApiError(
        String code,
        String message
) {
}
