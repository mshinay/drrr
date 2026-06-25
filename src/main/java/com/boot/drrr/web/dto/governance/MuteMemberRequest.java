package com.boot.drrr.web.dto.governance;

import com.boot.drrr.service.governance.MuteMemberCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record MuteMemberRequest(
        @NotBlank String operatorUserId,
        @Min(1) int durationMinutes,
        String reason
) {
    public MuteMemberCommand toCommand() {
        return new MuteMemberCommand(operatorUserId, durationMinutes, reason);
    }
}
