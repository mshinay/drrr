package com.boot.drrr.service.governance;

import com.boot.drrr.domain.governance.MuteRecord;

public record MuteMemberResult(
        boolean muted,
        MuteRecord record
) {
}
