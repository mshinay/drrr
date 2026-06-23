package com.boot.drrr.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank String nickname
) {
}
