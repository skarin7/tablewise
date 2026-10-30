package com.tablewise.security;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.tablewise.domain.Repos.StaffRepo;
import com.tablewise.domain.Tenancy.Staff;
import com.tablewise.security.Principals.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns a validated Supabase JWT into a staff member at one outlet. The role always comes from the database. */
@Service
public class StaffAuthService {
    private static final Logger log = LoggerFactory.getLogger(StaffAuthService.class);

    private final StaffRepo staff;

    StaffAuthService(StaffRepo staff) { this.staff = staff; }

    @Transactional
    public PosAuth authenticate(Jwt jwt, String outletHeader) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<Staff> rows = staff.findByUserIdAndActiveTrue(userId);
        if (rows.isEmpty()) rows = claimInvite(userId, jwt.getClaimAsString("email"));
        if (outletHeader != null && !outletHeader.isBlank()) {
            UUID outlet = UUID.fromString(outletHeader);
            rows = rows.stream().filter(s -> s.getOutletId().equals(outlet)).toList();
        }
        if (rows.size() != 1) {
            log.warn("sign-in refused for user {}: {}", userId, rows.isEmpty() ? "no active staff profile" : "several outlets, X-Outlet-Id needed");
            throw new BadCredentialsException(rows.isEmpty() ? "No active staff profile" : "Choose an outlet with X-Outlet-Id");
        }
        Staff s = rows.get(0);
        if (s.getLastActiveAt() == null || s.getLastActiveAt().isBefore(Instant.now().minus(Duration.ofMinutes(1)))) s.setLastActiveAt(Instant.now());
        MDC.put("outletId", s.getOutletId().toString());
        return PosAuth.staff(new StaffPrincipal(s.getId(), s.getOutletId(), s.getRole(), s.getFullName()));
    }

    /** First sign-in of an invited person: link the unclaimed staff row that has their email. */
    private List<Staff> claimInvite(UUID userId, String email) {
        if (email == null) return List.of();
        List<Staff> invited = staff.findByEmailIgnoreCaseAndUserIdIsNullAndActiveTrue(email);
        invited.forEach(s -> { s.setUserId(userId); s.setJoinedAt(Instant.now()); log.info("invited staff {} signed in for the first time and was linked to user {}", s.getId(), userId); });
        return invited;
    }
}
