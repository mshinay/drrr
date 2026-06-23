package com.boot.drrr.service.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;
import com.boot.drrr.repository.lobby.LobbyRepository;
import com.boot.drrr.repository.user.UserSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UserSessionServiceTest {

    @Test
    void createAnonymousSessionPersistsSessionAndLobbyActivity() {
        RecordingUserSessionRepository userSessionRepository = new RecordingUserSessionRepository();
        RecordingLobbyRepository lobbyRepository = new RecordingLobbyRepository();
        UserSessionService service = new UserSessionService(
                userSessionRepository,
                lobbyRepository,
                new FixedIdGenerator("u_test001"),
                new FixedTimeProvider(1717300200000L)
        );

        UserSession created = service.createAnonymousSession(" Alice ");

        assertThat(created).isEqualTo(new UserSession(
                "u_test001",
                "Alice",
                null,
                UserStatus.ONLINE,
                false,
                null,
                null,
                1717300200000L,
                1717300200000L
        ));
        assertThat(userSessionRepository.saved).isEqualTo(created);
        assertThat(lobbyRepository.member).isEqualTo("u_test001");
        assertThat(lobbyRepository.score).isEqualTo(1717300200000L);
    }

    private static final class RecordingUserSessionRepository extends UserSessionRepository {
        private UserSession saved;

        private RecordingUserSessionRepository() {
            super(null);
        }

        @Override
        public void save(UserSession userSession) {
            this.saved = userSession;
        }
    }

    private static final class RecordingLobbyRepository extends LobbyRepository {
        private String member;
        private double score;

        private RecordingLobbyRepository() {
            super(null);
        }

        @Override
        public void zAdd(String member, double score) {
            this.member = member;
            this.score = score;
        }
    }

    private record FixedIdGenerator(String value) implements IdGenerator {
        @Override
        public String newUserId() {
            return value;
        }

        @Override
        public String newRoomId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String newMessageId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String newEventId() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FixedTimeProvider implements TimeProvider {
        private final Clock clock;

        private FixedTimeProvider(long nowMillis) {
            this.clock = Clock.fixed(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
        }

        @Override
        public Instant now() {
            return clock.instant();
        }

        @Override
        public long nowMillis() {
            return clock.millis();
        }

        @Override
        public Clock clock() {
            return clock;
        }
    }
}
