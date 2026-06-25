package com.boot.drrr.web.dto.governance;

import com.boot.drrr.service.governance.KickMemberCommand;
import jakarta.validation.constraints.NotBlank;

public record KickMemberRequest(
        @NotBlank String operatorUserId,
        String reason
) {
    public KickMemberCommand toCommand() {
        return new KickMemberCommand(operatorUserId, reason);
    }
}
