package com.tablewise.realtime;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/** Keeps open sockets by session id so a revoked device can be disconnected at once (README 7.6 step 4). */
@Component
public class SocketRegistry implements WebSocketHandlerDecoratorFactory {
    private final Map<String, WebSocketSession> open = new ConcurrentHashMap<>();

    public void close(Collection<String> sessionIds) {
        for (String id : sessionIds) {
            var s = open.remove(id);
            if (s != null) {
                try { s.close(CloseStatus.POLICY_VIOLATION); } catch (IOException ignored) { /* already gone */ }
            }
        }
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override public void afterConnectionEstablished(WebSocketSession s) throws Exception { open.put(s.getId(), s); super.afterConnectionEstablished(s); }
            @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus c) throws Exception { open.remove(s.getId()); super.afterConnectionClosed(s, c); }
        };
    }
}
