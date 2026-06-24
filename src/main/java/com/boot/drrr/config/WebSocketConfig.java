package com.boot.drrr.config;

import com.boot.drrr.ws.RoomWebSocketHandshakeInterceptor;
import com.boot.drrr.ws.RoomWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final DrrrProperties drrrProperties;
    private final RoomWebSocketHandler roomWebSocketHandler;
    private final RoomWebSocketHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(
            DrrrProperties drrrProperties,
            RoomWebSocketHandler roomWebSocketHandler,
            RoomWebSocketHandshakeInterceptor handshakeInterceptor
    ) {
        this.drrrProperties = drrrProperties;
        this.roomWebSocketHandler = roomWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(roomWebSocketHandler, drrrProperties.getWebsocket().getEndpoint() + "/{roomId}")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
