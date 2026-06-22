package com.boot.drrr.common.id;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PrefixedIdGenerator implements IdGenerator {

    @Override
    public String newUserId() {
        return next("u_");
    }

    @Override
    public String newRoomId() {
        return next("r_");
    }

    @Override
    public String newMessageId() {
        return next("m_");
    }

    @Override
    public String newEventId() {
        return next("e_");
    }

    private String next(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
