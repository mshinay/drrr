package com.boot.drrr.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.repository.event.RoomEventRepository;
import com.boot.drrr.repository.governance.GovernanceRepository;
import com.boot.drrr.repository.lobby.LobbyRepository;
import com.boot.drrr.repository.message.MessageRepository;
import com.boot.drrr.repository.room.RoomIndexRepository;
import com.boot.drrr.repository.room.RoomMemberRepository;
import com.boot.drrr.repository.room.RoomRepository;
import com.boot.drrr.repository.user.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@SpringBootTest
class RedisRepositoryConfigurationTest {

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

    @Test
    void redisTemplateUsesStringSerializersForDocumentedJsonStorage() {
        assertThat(redisTemplate.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(redisTemplate.getValueSerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(redisTemplate.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(redisTemplate.getHashValueSerializer()).isInstanceOf(StringRedisSerializer.class);
    }

    @Test
    void repositoryOwnersAreRegisteredInTheApplicationContext() {
        assertThat(userSessionRepository).isNotNull();
        assertThat(lobbyRepository).isNotNull();
        assertThat(roomRepository).isNotNull();
        assertThat(roomMemberRepository).isNotNull();
        assertThat(roomIndexRepository).isNotNull();
        assertThat(messageRepository).isNotNull();
        assertThat(roomEventRepository).isNotNull();
        assertThat(governanceRepository).isNotNull();
    }
}
