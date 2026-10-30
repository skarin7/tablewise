package com.tablewise.realtime;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.tablewise.security.Principals.*;
import com.tablewise.security.StaffAuthService;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP CONNECT frame (staff: Authorization Bearer, optional X-Outlet-Id;
 * device: X-Device-Token) and authorises every SUBSCRIBE by destination (README section 8).
 */
@Component
class StompAuthInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

    private final JwtDecoder decoder;
    private final StaffAuthService staffAuth;
    private final ObjectProvider<DeviceAuthenticator> devices;
    private final JdbcTemplate jdbc;

    StompAuthInterceptor(JwtDecoder decoder, StaffAuthService staffAuth, ObjectProvider<DeviceAuthenticator> devices, JdbcTemplate jdbc) {
        this.decoder = decoder;
        this.staffAuth = staffAuth;
        this.devices = devices;
        this.jdbc = jdbc;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) return message;
        if (StompCommand.CONNECT.equals(acc.getCommand())) {
            String bearer = acc.getFirstNativeHeader("Authorization");
            String token = acc.getFirstNativeHeader("X-Device-Token");
            if (bearer != null && bearer.startsWith("Bearer ")) {
                acc.setUser(staffAuth.authenticate(decoder.decode(bearer.substring(7)), acc.getFirstNativeHeader("X-Outlet-Id")));
                log.info("socket connected: staff, session {}", acc.getSessionId());
            } else if (token != null) {
                var p = devices.getObject().authenticate(token, acc.getSessionId());
                acc.setUser(PosAuth.device(p));
                log.info("socket connected: device {}, session {}", p.deviceId(), acc.getSessionId());
            } else {
                log.warn("socket refused: no credentials");
                throw new MessagingException("Missing credentials");
            }
        } else if (StompCommand.SUBSCRIBE.equals(acc.getCommand())) {
            if (!(acc.getUser() instanceof PosAuth a) || !allowed(a.getPrincipal(), acc.getDestination())) {
                log.warn("subscription refused: {} to {}", acc.getUser() == null ? "unauthenticated" : acc.getUser().getName(), acc.getDestination());
                throw new MessagingException("Not allowed");
            }
            log.info("subscribed: {} to {}", acc.getUser().getName(), acc.getDestination());
        } else if (StompCommand.SEND.equals(acc.getCommand())) {
            if (acc.getUser() instanceof PosAuth a && a.getPrincipal() instanceof DevicePrincipal d) devices.getObject().heartbeat(d.deviceId());
        }
        return message;
    }

    private boolean allowed(Object principal, String dest) {
        if (dest == null) return false;
        String[] p = dest.split("/"); // "", topic, outlets|counters, id, channel
        if (p.length != 5 || !p[1].equals("topic")) return false;
        try {
            UUID id = UUID.fromString(p[3]);
            if (principal instanceof DevicePrincipal d) return p[2].equals("counters") && p[4].equals("display") && id.equals(d.counterId());
            var s = (StaffPrincipal) principal;
            if (p[2].equals("counters")) { // staff may watch their own outlet's counters
                return !s.kitchen() && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pos.counters where id=? and outlet_id=?)", Boolean.class, id, s.outletId()));
            }
            if (!id.equals(s.outletId())) return false;
            return switch (p[4]) {
                case "orders" -> true;
                case "online" -> !s.kitchen();
                case "inventory" -> s.owner();
                default -> false;
            };
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
