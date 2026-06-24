package com.boot.drrr.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.redis.RedisKeys;
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
import com.boot.drrr.repository.event.RoomEventRepository;
import com.boot.drrr.repository.governance.GovernanceRepository;
import com.boot.drrr.repository.lobby.LobbyRepository;
import com.boot.drrr.repository.message.MessageRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomIndexRepository.RoomIndexKey;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.repository.user.UserSessionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class RedisRepositoryIntegrationTest extends AbstractRedisRepositoryTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private LobbyRepository lobbyRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private RoomIndexRepository roomIndexRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private RoomEventRepository roomEventRepository;

    @Autowired
    private GovernanceRepository governanceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userAndLobbyRepositoriesPersistJsonStringsAndZsetIndexes() {
        UserSession userSession = new UserSession(
                "u_1",
                "Alice",
                null,
                UserStatus.ONLINE,
                true,
                1717300200000L,
                null,
                1717300000000L,
                1717300200000L
        );

        userSessionRepository.save(userSession);
        userSessionRepository.saveReconnectingUser("u_1", 1717300300000L);
        userSessionRepository.saveReconnectingUser("u_2", 1717300400000L);
        lobbyRepository.zAdd("u_1", 1717300500000L);
        lobbyRepository.zAdd("u_2", 1717300100000L);

        assertThat(userSessionRepository.findById("u_1")).contains(userSession);
        assertThat(userSessionRepository.exists("u_1")).isTrue();
        assertThat(redisTemplate.opsForValue().get(RedisKeys.user("u_1")))
                .contains("\"nickname\":\"Alice\"");
        assertThat(userSessionRepository.listReconnectingUserIdsByScore(Double.NEGATIVE_INFINITY, 1717300350000L))
                .containsExactly("u_1");
        assertThat(lobbyRepository.zCountByScore(1717300200000L, Double.POSITIVE_INFINITY)).isEqualTo(1);
        assertThat(lobbyRepository.zRangeByScore(1717300000000L, Double.POSITIVE_INFINITY))
                .containsExactly("u_2", "u_1");

        userSessionRepository.removeReconnectingUser("u_1");
        userSessionRepository.deleteById("u_1");

        assertThat(userSessionRepository.listReconnectingUserIdsByScore(Double.NEGATIVE_INFINITY, 1717300500000L))
                .containsExactly("u_2");
        assertThat(userSessionRepository.findById("u_1")).isEmpty();
    }

    @Test
    void roomRepositoriesOwnRoomMemberAndIndexKeys() {
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
        RoomMember owner = new RoomMember(
                "r_1",
                "u_owner",
                "Owner",
                MemberStatus.ONLINE,
                1717299000000L,
                1717300200000L,
                true
        );
        RoomMember member = new RoomMember(
                "r_1",
                "u_2",
                "Bob",
                MemberStatus.RECONNECTING,
                1717299100000L,
                1717300250000L,
                false
        );

        roomRepository.save(room);
        roomMemberRepository.save(owner);
        roomMemberRepository.save(member);
        roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, "r_1", 1717300200000L);
        roomIndexRepository.zAdd(RoomIndexKey.ACTIVE, "r_2", 1717300100000L);
        roomIndexRepository.zAdd(RoomIndexKey.EMPTY, "r_1", 1717300300000L);
        roomIndexRepository.zAdd(RoomIndexKey.EMPTY, "r_3", 1717300400000L);

        assertThat(roomRepository.findById("r_1")).contains(room);
        assertThat(roomMemberRepository.findMember("r_1", "u_owner")).contains(owner);
        assertThat(roomMemberRepository.countMembers("r_1")).isEqualTo(2);
        assertThat(redisTemplate.opsForZSet().range(RedisKeys.roomMembers("r_1"), 0, -1))
                .containsExactly("u_owner", "u_2");
        assertThat(redisTemplate.opsForHash().get(RedisKeys.roomMemberDetail("r_1"), "u_owner"))
                .isEqualTo(objectMapper.writeValueAsString(owner));
        assertThat(roomMemberRepository.listMembers("r_1"))
                .extracting(RoomMember::userId)
                .containsExactly("u_owner", "u_2");
        assertThat(roomIndexRepository.zReverseRange(RoomIndexKey.ACTIVE, 0, -1))
                .containsExactly("r_1", "r_2");
        assertThat(roomIndexRepository.zRangeByScore(RoomIndexKey.EMPTY, Double.NEGATIVE_INFINITY, 1717300350000L))
                .containsExactly("r_1");

        roomMemberRepository.removeMember("r_1", "u_owner");
        roomIndexRepository.zRem(RoomIndexKey.ACTIVE, "r_2");
        roomIndexRepository.zRem(RoomIndexKey.EMPTY, "r_1");
        roomRepository.deleteById("r_1");

        assertThat(roomMemberRepository.findMember("r_1", "u_owner")).isEmpty();
        assertThat(redisTemplate.opsForHash().hasKey(RedisKeys.roomMemberDetail("r_1"), "u_owner")).isFalse();
        assertThat(roomIndexRepository.zReverseRange(RoomIndexKey.ACTIVE, 0, -1)).containsExactly("r_1");
        assertThat(roomIndexRepository.zRangeByScore(RoomIndexKey.EMPTY, Double.NEGATIVE_INFINITY, 1717300500000L))
                .containsExactly("r_3");
        assertThat(roomRepository.findById("r_1")).isEmpty();
    }

    @Test
    void messageAndEventRepositoriesPreserveListOrderAndSupportTrim() throws Exception {
        Message firstMessage = new Message(
                "m_1",
                "r_1",
                MessageType.PUBLIC,
                "u_1",
                "Alice",
                null,
                null,
                "hello",
                List.of(),
                null,
                null,
                1717300200000L
        );
        Message secondMessage = new Message(
                "m_2",
                "r_1",
                MessageType.SYSTEM,
                "u_owner",
                "Owner",
                null,
                null,
                "joined",
                List.of("u_1", "u_owner"),
                "e_1",
                RoomEventType.USER_JOIN,
                1717300300000L
        );
        RoomEvent firstEvent = new RoomEvent(
                "e_1",
                "r_1",
                RoomEventType.USER_JOIN,
                "u_owner",
                "u_1",
                objectMapper.readTree("{\"nickname\":\"Alice\"}"),
                1717300200000L
        );
        RoomEvent secondEvent = new RoomEvent(
                "e_2",
                "r_1",
                RoomEventType.USER_MUTED,
                "u_owner",
                "u_2",
                objectMapper.readTree("{\"durationMinutes\":30}"),
                1717300400000L
        );

        messageRepository.append(firstMessage);
        messageRepository.append(secondMessage);
        roomEventRepository.append(firstEvent);
        roomEventRepository.append(secondEvent);

        assertThat(messageRepository.listMessages("r_1"))
                .extracting(Message::messageId)
                .containsExactly("m_1", "m_2");
        assertThat(roomEventRepository.listEvents("r_1"))
                .extracting(RoomEvent::eventId)
                .containsExactly("e_1", "e_2");

        messageRepository.trim("r_1", 1, -1);
        roomEventRepository.deleteAll("r_1");

        assertThat(messageRepository.listMessages("r_1"))
                .extracting(Message::messageId)
                .containsExactly("m_2");
        assertThat(roomEventRepository.listEvents("r_1")).isEmpty();
    }

    @Test
    void governanceRepositoryPersistsMuteAndBanKeys() {
        MuteRecord muteRecord = new MuteRecord(
                "r_1",
                "u_2",
                "u_owner",
                1717300200000L,
                1717302000000L,
                "owner_action"
        );
        BanRecord banRecord = new BanRecord(
                "r_1",
                "u_3",
                "u_owner",
                1717300300000L,
                "owner_action"
        );

        governanceRepository.saveMuteRecord(muteRecord);
        governanceRepository.saveBanRecord(banRecord);

        assertThat(governanceRepository.findMuteRecord("r_1", "u_2")).contains(muteRecord);
        assertThat(governanceRepository.hasMuteIndexEntry("r_1", "u_2")).isTrue();
        assertThat(governanceRepository.listMutedUserIdsByScore("r_1", Double.NEGATIVE_INFINITY, 1717302000000L))
                .containsExactly("u_2");
        assertThat(governanceRepository.findBanRecord("r_1", "u_3")).contains(banRecord);
        assertThat(governanceRepository.hasBanIndexEntry("r_1", "u_3")).isTrue();
        assertThat(governanceRepository.listBanUserIds("r_1")).containsExactly("u_3");

        governanceRepository.clearMute("r_1", "u_2");
        governanceRepository.clearBan("r_1", "u_3");

        assertThat(governanceRepository.findMuteRecord("r_1", "u_2")).isEmpty();
        assertThat(governanceRepository.hasMuteIndexEntry("r_1", "u_2")).isFalse();
        assertThat(governanceRepository.findBanRecord("r_1", "u_3")).isEmpty();
        assertThat(governanceRepository.hasBanIndexEntry("r_1", "u_3")).isFalse();
    }
}




