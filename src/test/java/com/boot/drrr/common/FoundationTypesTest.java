package com.boot.drrr.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.id.PrefixedIdGenerator;
import com.boot.drrr.common.redis.RedisKeys;
import org.junit.jupiter.api.Test;

class FoundationTypesTest {
    private final IdGenerator idGenerator = new PrefixedIdGenerator();

    @Test
    void idGeneratorUsesDocumentedPrefixes() {
        assertThat(idGenerator.newUserId()).startsWith("u_");
        assertThat(idGenerator.newRoomId()).startsWith("r_");
        assertThat(idGenerator.newMessageId()).startsWith("m_");
        assertThat(idGenerator.newEventId()).startsWith("e_");
    }

    @Test
    void redisKeyFactoryMatchesDocumentedKeys() {
        assertThat(RedisKeys.user("u_1")).isEqualTo("drrr:user:u_1");
        assertThat(RedisKeys.room("r_1")).isEqualTo("drrr:room:r_1");
        assertThat(RedisKeys.roomMembers("r_1")).isEqualTo("drrr:room:members:r_1");
        assertThat(RedisKeys.roomMessages("r_1")).isEqualTo("drrr:room:messages:r_1");
        assertThat(RedisKeys.roomEvents("r_1")).isEqualTo("drrr:room:events:r_1");
        assertThat(RedisKeys.ROOM_ACTIVE).isEqualTo("drrr:room:active");
        assertThat(RedisKeys.ROOM_EMPTY).isEqualTo("drrr:room:empty");
        assertThat(RedisKeys.USER_RECONNECTING).isEqualTo("drrr:user:reconnecting");
        assertThat(RedisKeys.roomMute("r_1")).isEqualTo("drrr:room:mute:r_1");
        assertThat(RedisKeys.roomMuteDetail("r_1", "u_1")).isEqualTo("drrr:room:mute:detail:r_1:u_1");
        assertThat(RedisKeys.roomBan("r_1")).isEqualTo("drrr:room:ban:r_1");
        assertThat(RedisKeys.roomBanDetail("r_1", "u_1")).isEqualTo("drrr:room:ban:detail:r_1:u_1");
        assertThat(RedisKeys.LOBBY_ACTIVE_USERS).isEqualTo("drrr:lobby:active-users");
    }
}
