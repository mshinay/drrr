package com.boot.drrr.ws;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

final class TestWebSocketSession implements WebSocketSession {
    private final String id;
    private final URI uri;
    private final Map<String, Object> attributes = new HashMap<>();
    private final List<TextMessage> textMessages = new ArrayList<>();
    private boolean open = true;
    private CloseStatus closeStatus;
    private int textMessageSizeLimit = 65536;
    private int binaryMessageSizeLimit = 65536;

    TestWebSocketSession(String id, URI uri) {
        this.id = id;
        this.uri = uri;
    }

    List<TextMessage> textMessages() {
        return textMessages;
    }

    CloseStatus closeStatus() {
        return closeStatus;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public URI getUri() {
        return uri;
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
        return HttpHeaders.EMPTY;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return null;
    }

    @Override
    public String getAcceptedProtocol() {
        return null;
    }

    @Override
    public void setTextMessageSizeLimit(int messageSizeLimit) {
        this.textMessageSizeLimit = messageSizeLimit;
    }

    @Override
    public int getTextMessageSizeLimit() {
        return textMessageSizeLimit;
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        this.binaryMessageSizeLimit = messageSizeLimit;
    }

    @Override
    public int getBinaryMessageSizeLimit() {
        return binaryMessageSizeLimit;
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        return List.of();
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) {
        if (message instanceof TextMessage textMessage) {
            textMessages.add(textMessage);
            return;
        }
        if (message instanceof BinaryMessage || message instanceof PingMessage || message instanceof PongMessage) {
            throw new UnsupportedOperationException();
        }
        throw new IllegalArgumentException("unsupported message type: " + message.getClass().getName());
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        this.open = false;
        this.closeStatus = CloseStatus.NORMAL;
    }

    @Override
    public void close(CloseStatus status) {
        this.open = false;
        this.closeStatus = status;
    }
}
