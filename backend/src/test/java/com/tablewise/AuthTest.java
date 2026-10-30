package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tablewise.security.Principals.StaffPrincipal;
import com.tablewise.security.StaffAuthService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

/** M2: JWT to staff lookup, role checks (README 7.1 screens) and outlet scoping. */
class AuthTest extends IntegrationTest {
    @Autowired StaffAuthService staffAuth;

    private Jwt jwt(UUID user, String email) {
        var b = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString());
        if (email != null) b.claim("email", email);
        return b.build();
    }

    @Test
    void jwtResolvesStaffAndRoleFromDatabase() {
        var o = newOutlet();
        UUID user = UUID.randomUUID();
        UUID staff = staffRow(o.id(), "cashier");
        jdbc.update("update pos.staff_members set user_id = ? where id = ?", user, staff);
        var p = (StaffPrincipal) staffAuth.authenticate(jwt(user, null), null).getPrincipal();
        assertThat(p.role()).isEqualTo("cashier");
        assertThat(p.outletId()).isEqualTo(o.id());
    }

    @Test
    void invitedPersonIsLinkedByEmailOnFirstSignIn() {
        var o = newOutlet();
        UUID staff = staffRow(o.id(), "kitchen");
        String email = jdbc.queryForObject("select email from pos.staff_members where id = ?", String.class, staff);
        UUID user = UUID.randomUUID();
        staffAuth.authenticate(jwt(user, email), null);
        assertThat(jdbc.queryForObject("select user_id from pos.staff_members where id = ?", UUID.class, staff)).isEqualTo(user);
    }

    @Test
    void unknownOrInactiveUsersAreRejected() {
        var o = newOutlet();
        UUID user = UUID.randomUUID();
        UUID staff = staffRow(o.id(), "owner");
        jdbc.update("update pos.staff_members set user_id = ?, is_active = false where id = ?", user, staff);
        assertThatThrownBy(() -> staffAuth.authenticate(jwt(user, null), null)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> staffAuth.authenticate(jwt(UUID.randomUUID(), null), null)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void personAtTwoOutletsMustChooseOne() {
        var a = newOutlet();
        var b = newOutlet();
        UUID user = UUID.randomUUID();
        jdbc.update("update pos.staff_members set user_id = ? where id = ?", user, staffRow(a.id(), "owner"));
        jdbc.update("update pos.staff_members set user_id = ? where id = ?", user, staffRow(b.id(), "cashier"));
        assertThatThrownBy(() -> staffAuth.authenticate(jwt(user, null), null)).isInstanceOf(BadCredentialsException.class);
        var p = (StaffPrincipal) staffAuth.authenticate(jwt(user, null), b.id().toString()).getPrincipal();
        assertThat(p.role()).isEqualTo("cashier");
    }

    @Test
    void requestsWithoutTokenGet401AndHealthIsPublic() throws Exception {
        mvc.perform(get("/menu")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void screensAreLimitedByRole() throws Exception {
        var o = newOutlet();
        call(get("/menu"), as(o.id(), "kitchen"), null).andExpect(status().isForbidden());
        call(get("/menu"), as(o.id(), "cashier"), null).andExpect(status().isOk());
        call(get("/ingredients"), as(o.id(), "cashier"), null).andExpect(status().isForbidden());
        call(get("/ingredients"), as(o.id(), "owner"), null).andExpect(status().isOk());
        var item = Map.of("categoryId", o.category(), "name", "Dal", "price", 100, "veg", true);
        call(post("/menu-items"), as(o.id(), "cashier"), item).andExpect(status().isForbidden());
        call(post("/menu-items"), as(o.id(), "owner"), item).andExpect(status().isCreated());
    }

    @Test
    void everyQueryIsScopedToTheCallersOutlet() throws Exception {
        var a = newOutlet();
        var b = newOutlet();
        UUID ing = ingredient(a.id(), "Chicken", "kg", "10", "2");
        assertThat(json(call(get("/ingredients"), as(b.id(), "owner"), null))).isEmpty();
        call(delete("/ingredients/" + ing), as(b.id(), "owner"), null).andExpect(status().isNotFound());
        call(post("/ingredients/" + ing + "/restock"), as(b.id(), "owner"), Map.of("quantity", 5)).andExpect(status().isNotFound());
        assertThat(stock(ing)).isEqualByComparingTo("10");
    }
}
