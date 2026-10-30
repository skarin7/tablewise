package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M10: settings, staff invites, counters, tables, audit log. */
class AdminTest extends IntegrationTest {
    private Map<String, Object> settings(String gst, String service) {
        return Map.of("name", "Saffron Table", "gstin", "36ABCDE1234F1Z5", "gstPercent", new java.math.BigDecimal(gst), "serviceChargePercent", new java.math.BigDecimal(service));
    }

    @Test
    void ownerChangesBillingSettingsAndNewBillsUseThemWhileOldBillsKeepTheirs() throws Exception {
        var o = newOutlet();
        UUID dal = dish(o, "Dal", "200");
        UUID before = placeOrder(o, dal, 1);                                           // 210 at 5 %
        call(put("/outlet"), as(o.id(), "owner"), settings("12", "10")).andExpect(status().isOk());
        UUID after = placeOrder(o, dal, 1);                                            // 200 + 20 service = 220, + 12 % = 246.40 -> 246

        assertThat(jdbc.queryForObject("select total_amount from pos.bills where order_id = ?", java.math.BigDecimal.class, before)).isEqualByComparingTo("210");
        assertThat(jdbc.queryForObject("select gst_percent from pos.bills where order_id = ?", java.math.BigDecimal.class, before)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("select total_amount from pos.bills where order_id = ?", java.math.BigDecimal.class, after)).isEqualByComparingTo("246");
        assertThat(jdbc.queryForObject("select changes::text from pos.audit_log where outlet_id = ? and action = 'outlet.updated'", String.class, o.id())).contains("gstPercent").contains("12");

        call(put("/outlet"), as(o.id(), "owner"), settings("40", "0")).andExpect(status().isBadRequest());
        call(put("/outlet"), as(o.id(), "owner"), Map.of("name", "X", "gstin", "bad", "gstPercent", 5, "serviceChargePercent", 0)).andExpect(status().isBadRequest());
        call(put("/outlet"), as(o.id(), "cashier"), settings("5", "0")).andExpect(status().isForbidden());
        var cashierView = json(call(get("/outlet"), as(o.id(), "cashier"), null));
        assertThat(cashierView.get("gstPercent").decimalValue()).isEqualByComparingTo("12");
        assertThat(json(call(get("/outlet"), as(o.id(), "kitchen"), null)).has("gstin")).isFalse();
    }

    @Test
    void staffAreInvitedByEmailAndTheLastOwnerCannotBeRemoved() throws Exception {
        var o = newOutlet();
        UUID ownerId = staffRow(o.id(), "owner");
        var owner = as(o.id(), "owner", ownerId);
        call(post("/staff"), as(o.id(), "cashier"), Map.of("fullName", "A", "email", "a@example.com", "role", "cashier")).andExpect(status().isForbidden());
        var id = UUID.fromString(json(call(post("/staff"), owner, Map.of("fullName", "Mohan Lal", "email", "Mohan@Example.com", "role", "cashier")).andExpect(status().isCreated())).get("id").asText());
        var row = jdbc.queryForMap("select email, role, invited_at, user_id from pos.staff_members where id = ?", id);
        assertThat(row).containsEntry("email", "mohan@example.com").containsEntry("role", "cashier").containsEntry("user_id", null);
        assertThat(row.get("invited_at")).isNotNull();

        call(put("/staff/" + id), owner, Map.of("fullName", "Mohan Lal", "role", "cashier", "active", false)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select is_active from pos.staff_members where id = ?", Boolean.class, id)).isFalse();

        call(put("/staff/" + ownerId), owner, Map.of("fullName", "Me", "role", "owner", "active", false)).andExpect(status().isConflict());   // only owner
        call(put("/staff/" + ownerId), owner, Map.of("fullName", "Me", "role", "cashier", "active", true)).andExpect(status().isConflict());
        jdbc.queryForObject("insert into pos.staff_members(outlet_id, full_name, role) values (?, 'Second', 'owner') returning id", UUID.class, o.id());
        call(put("/staff/" + ownerId), owner, Map.of("fullName", "Me", "role", "cashier", "active", true)).andExpect(status().isOk());       // now allowed
        assertThat(json(call(get("/staff"), as(o.id(), "owner"), null))).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void countersAndTablesAreManagedWithoutLosingHistory() throws Exception {
        var o = newOutlet();
        var owner = as(o.id(), "owner");
        call(post("/counters"), owner, Map.of("name", "Counter 2")).andExpect(status().isCreated());
        call(post("/counters"), owner, Map.of("name", "Counter 2")).andExpect(status().isConflict());
        call(post("/tables"), owner, Map.of("label", "T4", "seats", 6, "area", "Terrace")).andExpect(status().isCreated());
        call(post("/tables"), owner, Map.of("label", "T4")).andExpect(status().isConflict());
        assertThat(json(call(get("/tables"), owner, null))).hasSize(4);

        UUID dal = dish(o, "Dal", "200");
        call(post("/orders"), as(o.id(), "cashier"), Map.of("orderType", "dine_in", "tableId", o.t1(), "items", java.util.List.of(Map.of("menuItemId", dal, "quantity", 1)))).andExpect(status().isCreated());
        call(delete("/tables/" + o.t1()), owner, null).andExpect(status().isConflict());                          // open order on it
        call(delete("/tables/" + o.t2()), owner, null).andExpect(status().isOk());
        assertThat(json(call(get("/tables"), owner, null))).hasSize(3);                                          // hidden, row kept
        assertThat(jdbc.queryForObject("select count(*) from pos.dining_tables where id = ?", Integer.class, o.t2())).isEqualTo(1);
        call(delete("/counters/" + o.counter()), owner, null).andExpect(status().isOk());
        assertThat(json(call(get("/counters"), owner, null))).hasSize(1);
    }

    @Test
    void auditLogShowsWhoChangedWhat() throws Exception {
        var o = newOutlet();
        var owner = as(o.id(), "owner", staffRow(o.id(), "owner"));
        UUID dish = UUID.fromString(json(call(post("/menu-items"), owner, Map.of("categoryId", o.category(), "name", "Dal", "price", 100, "veg", true))).get("id").asText());
        call(put("/menu-items/" + dish), owner, Map.of("categoryId", o.category(), "name", "Dal", "price", 120, "veg", true)).andExpect(status().isOk());
        var log = json(call(get("/audit-log"), owner, null));
        assertThat(log.get(0).get("action").asText()).isEqualTo("menu_item.price_changed");
        assertThat(log.get(0).get("actor").asText()).isEqualTo("Test owner");
        call(get("/audit-log"), as(o.id(), "cashier"), null).andExpect(status().isForbidden());
    }
}
