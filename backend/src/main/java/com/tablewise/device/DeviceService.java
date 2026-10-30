package com.tablewise.device;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.tablewise.common.ApiException;
import com.tablewise.domain.Repos.*;
import com.tablewise.domain.Tenancy.*;
import com.tablewise.realtime.DeviceAuthenticator;
import com.tablewise.realtime.SocketRegistry;
import com.tablewise.security.Principals.DevicePrincipal;
import com.tablewise.security.Principals.StaffPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer display pairing and device tokens (README 7.6). The raw token is never stored, only its SHA-256. */
@Service
public class DeviceService implements DeviceAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepo devices;
    private final PairingCodeRepo codes;
    private final CounterRepo counters;
    private final AuditRepo audit;
    private final SocketRegistry sockets;
    /** shortcut: the raw token waits here for the tablet's next poll; lost on restart (re-pair), fine for one instance. */
    private final Map<UUID, String> handoff = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> sessions = new ConcurrentHashMap<>();

    DeviceService(DeviceRepo devices, PairingCodeRepo codes, CounterRepo counters, AuditRepo audit, SocketRegistry sockets) {
        this.devices = devices;
        this.codes = codes;
        this.counters = counters;
        this.audit = audit;
        this.sockets = sockets;
    }

    @Transactional
    public Map<String, Object> issueCode(UUID deviceKey) {
        codes.deleteByClaimedAtIsNullAndExpiresAtBefore(Instant.now());
        var existing = codes.findFirstByDeviceKeyOrderByExpiresAtDesc(deviceKey).filter(c -> c.getClaimedAt() == null);
        if (existing.isPresent()) return codeView(existing.get());
        var c = new PairingCode();
        c.setDeviceKey(deviceKey);
        c.setExpiresAt(Instant.now().plus(CODE_LIFETIME));
        do {
            c.setCode(String.format("%06d", RANDOM.nextInt(1_000_000)));
        } while (codes.findByCodeAndClaimedAtIsNull(c.getCode()).isPresent());
        codes.saveAndFlush(c);
        log.info("pairing code issued for a new tablet, expires {}", c.getExpiresAt());
        return codeView(c);
    }

    /** The tablet polls this: pending, then paired (with the token exactly once), or expired. */
    @Transactional
    public Map<String, Object> poll(UUID deviceKey) {
        var c = codes.findFirstByDeviceKeyOrderByExpiresAtDesc(deviceKey).orElse(null);
        if (c == null) return Map.of("status", "unknown");
        if (c.getClaimedAt() != null) {
            var m = new LinkedHashMap<String, Object>(Map.of("status", "paired"));
            String token = handoff.remove(deviceKey);
            if (token != null) {
                m.put("token", token);
                devices.findById(c.getDeviceId()).ifPresent(d -> m.put("counterId", d.getCounterId()));
            }
            return m;
        }
        return c.getExpiresAt().isBefore(Instant.now()) ? Map.of("status", "expired") : new LinkedHashMap<>(codeView(c)) {{ put("status", "pending"); }};
    }

    @Transactional
    public Map<String, Object> pair(String code, UUID counterId, String name, StaffPrincipal me) {
        var c = codes.findByCodeAndClaimedAtIsNull(code).filter(x -> x.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> { log.warn("pairing refused: code not valid or expired"); return ApiException.notFound("That code is not valid or has expired"); });
        counters.findByIdAndOutletId(counterId, me.outletId()).orElseThrow(() -> ApiException.badRequest("Choose a counter"));
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "dev_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var d = new Device();
        d.setOutletId(me.outletId());
        d.setCounterId(counterId);
        d.setName(name.trim());
        d.setTokenHash(sha256(token));
        d.setPairedBy(me.staffId());
        devices.saveAndFlush(d);
        c.setClaimedAt(Instant.now());
        c.setDeviceId(d.getId());
        handoff.put(c.getDeviceKey(), token);
        audit(me, "device.paired", d.getId(), Map.of("name", d.getName(), "counterId", counterId));
        log.info("device paired: {} \"{}\" at counter {}", d.getId(), d.getName(), counterId);
        return Map.of("id", d.getId(), "name", d.getName(), "counterId", counterId);
    }

    @Transactional
    public void revoke(UUID id, StaffPrincipal me) {
        var d = devices.findByIdAndOutletId(id, me.outletId()).filter(x -> x.getRevokedAt() == null).orElseThrow(() -> ApiException.notFound("Device not found"));
        d.setRevokedAt(Instant.now());
        audit(me, "device.revoked", id, Map.of("name", d.getName()));
        log.info("device revoked: {} \"{}\"; open sockets are closed", id, d.getName());
        var open = sessions.remove(id);
        if (open != null) sockets.close(open);
    }

    // ---- socket side (DeviceAuthenticator)

    @Override
    @Transactional
    public DevicePrincipal authenticate(String rawToken, String sessionId) {
        var d = devices.findByTokenHash(sha256(rawToken)).filter(x -> x.getRevokedAt() == null).orElseThrow(() -> { log.warn("device socket refused: unknown or revoked token"); return new MessagingException("Unknown or revoked device"); });
        d.setLastSeenAt(Instant.now());
        sessions.computeIfAbsent(d.getId(), k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        return new DevicePrincipal(d.getId(), d.getOutletId(), d.getCounterId());
    }

    @Override
    @Transactional
    public void heartbeat(UUID deviceId) {
        devices.findById(deviceId).ifPresent(d -> {
            if (d.getRevokedAt() != null) {
                var open = sessions.remove(deviceId);
                if (open != null) sockets.close(open);
            } else {
                d.setLastSeenAt(Instant.now());
            }
        });
    }

    private Map<String, Object> codeView(PairingCode c) { return Map.of("code", c.getCode(), "expiresAt", c.getExpiresAt()); }

    private void audit(StaffPrincipal me, String action, UUID id, Map<String, Object> changes) {
        var a = new AuditLog();
        a.setOutletId(me.outletId());
        a.setActorStaffId(me.staffId());
        a.setAction(action);
        a.setEntityType("device");
        a.setEntityId(id);
        a.setChanges(changes);
        audit.save(a);
    }

    static byte[] sha256(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
