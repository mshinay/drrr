package com.boot.drrr.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.json.JsonCodec;
import com.boot.drrr.domain.event.RoomEvent;
import com.boot.drrr.domain.event.RoomEventType;
import com.boot.drrr.domain.governance.BanRecord;
import com.boot.drrr.domain.governance.MuteRecord;
import com.boot.drrr.domain.message.Message;
import com.boot.drrr.domain.message.MessageType;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class DomainJsonCodecTest {

    @Autowired
    private JsonCodec jsonCodec;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userSessionRoundTripsThroughSharedJsonCodec() {
        UserSession userSession = new UserSession(
                "u_1",
                "Alice",
                "r_1",
                UserStatus.ONLINE,
                true,
                1717300000000L,
                1717300100000L,
                1717299000000L,
                1717300100000L
        );

        String json = jsonCodec.encode(userSession);
        UserSession decoded = jsonCodec.decode(json, UserSession.class);

        assertThat(decoded).isEqualTo(userSession);
        assertThat(json).contains("\"status\":\"ONLINE\"");
        assertThat(json).contains("\"connected\":true");
    }

    @Test
    void roomRoundTripsThroughSharedJsonCodec() {
        Room room = new Room(
                "r_1",
                "深夜电台",
                "匿名闲聊",
                "hashed-password",
                10,
                "u_owner",
                "u_owner",
                RoomStatus.ACTIVE,
                true,
                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                true,
                1717299000000L,
                1717300200000L,
                null
        );

        String json = jsonCodec.encode(room);
        Room decoded = jsonCodec.decode(json, Room.class);

        assertThat(decoded).isEqualTo(room);
        assertThat(json).contains("\"passwordHash\":\"hashed-password\"");
        assertThat(json).contains("\"initialOwnerUserId\":\"u_owner\"");
        assertThat(json).doesNotContain("hasPassword");
        assertThat(json).contains("\"type\":\"COUNT\"");
        assertThat(json).contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void roomMemberRoundTripsThroughSharedJsonCodec() {
        RoomMember roomMember = new RoomMember(
                "r_1",
                "u_1",
                "Alice",
                MemberStatus.RECONNECTING,
                1717299050000L,
                1717300200000L,
                false
        );

        String json = jsonCodec.encode(roomMember);
        RoomMember decoded = jsonCodec.decode(json, RoomMember.class);

        assertThat(decoded).isEqualTo(roomMember);
        assertThat(json).contains("\"memberStatus\":\"RECONNECTING\"");
        assertThat(json).contains("\"lastActiveAt\":1717300200000");
    }

    @Test
    void messageRoundTripsThroughSharedJsonCodecWithSourceEventMetadata() {
        Message message = new Message(
                "m_1",
                "r_1",
                MessageType.SYSTEM,
                "u_owner",
                "Owner",
                "u_target",
                "Target",
                "Owner muted Target",
                List.of("u_owner", "u_target"),
                "e_1",
                RoomEventType.USER_MUTED,
                1717300200000L
        );

        String json = jsonCodec.encode(message);
        Message decoded = jsonCodec.decode(json, Message.class);

        assertThat(decoded).isEqualTo(message);
        assertThat(json).contains("\"sourceEventId\":\"e_1\"");
        assertThat(json).contains("\"sourceEventType\":\"USER_MUTED\"");
    }

    @Test
    void roomEventRoundTripsThroughSharedJsonCodecWithStructuredPayload() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {
                  "durationMinutes": 30,
                  "endAt": 1717302000000,
                  "reason": "owner_action"
                }
                """);
        RoomEvent roomEvent = new RoomEvent(
                "e_1",
                "r_1",
                RoomEventType.USER_MUTED,
                "u_owner",
                "u_target",
                payload,
                1717300200000L
        );

        String json = jsonCodec.encode(roomEvent);
        RoomEvent decoded = jsonCodec.decode(json, RoomEvent.class);

        assertThat(decoded).isEqualTo(roomEvent);
        assertThat(decoded.payload().get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(json).contains("\"type\":\"USER_MUTED\"");
        assertThat(json).contains("\"payload\":{");
    }

    @Test
    void muteRecordRoundTripsThroughSharedJsonCodec() {
        MuteRecord muteRecord = new MuteRecord(
                "r_1",
                "u_target",
                "u_owner",
                1717300200000L,
                1717302000000L,
                "owner_action"
        );

        String json = jsonCodec.encode(muteRecord);
        MuteRecord decoded = jsonCodec.decode(json, MuteRecord.class);

        assertThat(decoded).isEqualTo(muteRecord);
        assertThat(json).contains("\"mutedBy\":\"u_owner\"");
        assertThat(json).contains("\"endAt\":1717302000000");
    }

    @Test
    void banRecordRoundTripsThroughSharedJsonCodec() {
        BanRecord banRecord = new BanRecord(
                "r_1",
                "u_target",
                "u_owner",
                1717300200000L,
                "owner_action"
        );

        String json = jsonCodec.encode(banRecord);
        BanRecord decoded = jsonCodec.decode(json, BanRecord.class);

        assertThat(decoded).isEqualTo(banRecord);
        assertThat(json).contains("\"bannedBy\":\"u_owner\"");
        assertThat(json).contains("\"bannedAt\":1717300200000");
    }
}

