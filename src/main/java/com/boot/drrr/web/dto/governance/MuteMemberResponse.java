package com.boot.drrr.web.dto.governance;

import com.boot.drrr.domain.governance.MuteRecord;
import com.boot.drrr.service.governance.MuteMemberResult;

public record MuteMemberResponse(
        boolean muted,
        MuteRecord record
) {
    public static MuteMemberResponse from(MuteMemberResult result) {
        return new MuteMemberResponse(result.muted(), result.record());
    }
}
