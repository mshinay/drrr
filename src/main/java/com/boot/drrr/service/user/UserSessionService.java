package com.boot.drrr.service.user;

import com.boot.drrr.common.id.IdGenerator;
import com.boot.drrr.common.time.TimeProvider;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;
import com.boot.drrr.repository.lobby.LobbyRepository;
import com.boot.drrr.repository.user.UserSessionRepository;
import org.springframework.stereotype.Service;

@Service
public class UserSessionService {
    private final UserSessionRepository userSessionRepository;
    private final LobbyRepository lobbyRepository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;

    public UserSessionService(
            UserSessionRepository userSessionRepository,
            LobbyRepository lobbyRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider
    ) {
        this.userSessionRepository = userSessionRepository;
        this.lobbyRepository = lobbyRepository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    public UserSession createAnonymousSession(String nickname) {
        long now = timeProvider.nowMillis();
        UserSession userSession = new UserSession(
                idGenerator.newUserId(),
                nickname.trim(),
                null,
                UserStatus.ONLINE,
                false,
                null,
                null,
                now,
                now
        );
        userSessionRepository.save(userSession);
        lobbyRepository.zAdd(userSession.userId(), now);
        return userSession;
    }
}
