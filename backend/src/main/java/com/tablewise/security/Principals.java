package com.tablewise.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Who is calling: a signed-in staff member (JWT) or a paired device (device token). */
public final class Principals {
    private Principals() {}

    public record StaffPrincipal(UUID staffId, UUID outletId, String role, String name) {
        public boolean owner() { return "owner".equals(role); }
        public boolean kitchen() { return "kitchen".equals(role); }
    }

    public record DevicePrincipal(UUID deviceId, UUID outletId, UUID counterId) {}

    public static class PosAuth extends AbstractAuthenticationToken {
        private final Object principal;

        public PosAuth(Object principal, Collection<? extends GrantedAuthority> authorities) {
            super(authorities);
            this.principal = principal;
            setAuthenticated(true);
        }

        public static PosAuth staff(StaffPrincipal p) {
            return new PosAuth(p, List.of(new SimpleGrantedAuthority("ROLE_" + p.role().toUpperCase())));
        }

        public static PosAuth device(DevicePrincipal p) {
            return new PosAuth(p, List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
        }

        @Override public Object getCredentials() { return ""; }
        @Override public Object getPrincipal() { return principal; }
        @Override public String getName() { return principal.toString(); }
    }
}
