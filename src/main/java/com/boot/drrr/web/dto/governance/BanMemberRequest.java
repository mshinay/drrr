package com.boot.drrr.web.dto.governance;

import com.boot.drrr.service.governance.BanMemberCommand;
import jakarta.validation.constraints.NotBlank;

public record BanMemberRequest(
        @NotBlank String operatorUserId,
        String reason
) {
    public BanMemberCommand toCommand() {
        return new BanMemberCommand(operatorUserId, reason);
    }
}
