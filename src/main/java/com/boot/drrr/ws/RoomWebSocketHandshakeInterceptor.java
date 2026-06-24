package com.boot.drrr.ws;

import com.boot.drrr.common.error.BusinessException;
import com.boot.drrr.common.error.ErrorCode;
import com.boot.drrr.service.user.UserSessionService;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class RoomWebSocketHandshakeInterceptor implements HandshakeInterceptor {
    private final UserSessionService userSessionService;

    public RoomWebSocketHandshakeInterceptor(UserSessionService userSessionService) {
        this.userSessionService = userSessionService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        try {
            String roomId = resolveRoomId(request);
            String userId = resolveUserId(request);
            userSessionService.validateRoomConnection(userId, roomId);
            attributes.put(RoomWebSocketAttributes.ROOM_ID, roomId);
            attributes.put(RoomWebSocketAttributes.USER_ID, userId);
            return true;
        } catch (BusinessException exception) {
            response.setStatusCode(exception.getErrorCode().httpStatus());
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
    }

    private String resolveRoomId(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        int separator = path.lastIndexOf('/');
        if (separator < 0 || separator == path.length() - 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "roomId path variable is required");
        }
        String roomId = path.substring(separator + 1);
        if (roomId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "roomId path variable is required");
        }
        return roomId;
    }

    private String resolveUserId(ServerHttpRequest request) {
        MultiValueMap<String, String> queryParams = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String userId = queryParams.getFirst("userId");
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "userId query parameter is required");
        }
        return userId;
    }
}
