package com.tablewise.realtime;

import com.tablewise.security.SecurityConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.*;

/**
 * shortcut: in-memory simple broker, fine for the single Render instance (README 12.3);
 * swap enableSimpleBroker for a Redis/RabbitMQ relay before running two instances.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final StompAuthInterceptor auth;
    private final SocketRegistry sockets;
    private final String origins;

    WebSocketConfig(StompAuthInterceptor auth, SocketRegistry sockets, @Value("${tablewise.allowed-origins}") String origins) {
        this.auth = auth;
        this.sockets = sockets;
        this.origins = origins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry r) {
        r.addEndpoint("/ws").setAllowedOriginPatterns(SecurityConfig.origins(origins).toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry r) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        r.enableSimpleBroker("/topic").setHeartbeatValue(new long[] {10000, 10000}).setTaskScheduler(scheduler);
        r.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration r) { r.addDecoratorFactory(sockets); }

    @Override
    public void configureClientInboundChannel(ChannelRegistration c) { c.interceptors(auth); }
}
