package com.boot.drrr.ws;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class RoomWebSocketConnectionRegistry {
    private final ConcurrentMap<String, ConcurrentMap<String, WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RoomWebSocketSessionContext> sessionContexts = new ConcurrentHashMap<>();

    public void register(String roomId, String userId, WebSocketSession session) {
        roomSessions.compute(roomId, (ignored, existing) -> {
            ConcurrentMap<String, WebSocketSession> sessions = existing == null
                    ? new ConcurrentHashMap<>()
                    : existing;
            WebSocketSession replaced = sessions.put(userId, session);
            if (replaced != null && !replaced.getId().equals(session.getId())) {
                sessionContexts.remove(replaced.getId());
            }
            return sessions;
        });
        sessionContexts.put(session.getId(), new RoomWebSocketSessionContext(session.getId(), roomId, userId));
    }

    public Optional<RoomWebSocketSessionContext> unregister(String sessionId) {
        RoomWebSocketSessionContext context = sessionContexts.remove(sessionId);
        if (context == null) {
            return Optional.empty();
        }
        roomSessions.computeIfPresent(context.roomId(), (ignored, sessions) -> {
            sessions.remove(context.userId());
            return sessions.isEmpty() ? null : sessions;
        });
        return Optional.of(context);
    }

    public Optional<RoomWebSocketSessionContext> findBySessionId(String sessionId) {
        return Optional.ofNullable(sessionContexts.get(sessionId));
    }

    public Optional<WebSocketSession> findRoomUserSession(String roomId, String userId) {
        ConcurrentMap<String, WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(userId));
    }

    public List<WebSocketSession> listRoomSessions(String roomId) {
        ConcurrentMap<String, WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(sessions.values());
    }

    public List<String> listRoomUserIds(String roomId) {
        ConcurrentMap<String, WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(sessions.keySet());
    }
}
