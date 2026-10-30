package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tablewise.online.LoggingPlatformAdapter;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** M8: webhook intake, accept, reject, missed, platform cancellation, pause switch (README 7.5). */
class OnlineOrderTest extends IntegrationTest {
    @Autowired LoggingPlatformAdapter adapter;
    @Autowired com.tablewise.online.OnlineService onlineService;

    record Setup(Outlet outlet, UUID chicken, UUID fry, String store) {}

    private Setup setup() {
        var o = newOutlet();
        UUID chicken = ingredient(o.id(), "Chicken", "kg", "10", "1");
        UUID fry = dish(o, "Chicken Fry", "280", chicken, "0.5");
        String store = "store-" + UUID.randomUUID();
        UUID integ = jdbc.queryForObject("insert into pos.platform_integrations(outlet_id, platform, external_store_id) values (?, 'swiggy', ?) returning id", UUID.class, o.id(), store);
        jdbc.update("insert into pos.platform_item_mappings(integration_id, external_item_id, menu_item_id) values (?, 'sw-fry', ?)", integ, fry);
        return new Setup(o, chicken, fry, store);
    }

    private String sign(String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions hook(String body, String signature) throws Exception {
        return mvc.perform(post("/webhooks/swiggy").contentType(MediaType.APPLICATION_JSON).content(body).header("X-Signature", signature));
    }

    private String created(Setup s, String orderId, String itemId, int qty) {
        return "{\"eventId\":\"ev-" + orderId + "\",\"type\":\"order.created\",\"storeId\":\"" + s.store() + "\",\"orderId\":\"" + orderId + "\",\"instructions\":\"Less spicy\","
                + "\"customerName\":\"Hidden Person\",\"items\":[{\"itemId\":\"" + itemId + "\",\"name\":\"Chicken Fry\",\"quantity\":" + qty + ",\"price\":280}]}";
    }

    /** Sends a signed order and returns the online order's id. */
    private UUID incoming(Setup s, String orderId, String itemId, int qty) throws Exception {
        String body = created(s, orderId, itemId, qty);
        hook(body, sign(body)).andExpect(status().isOk());
        return jdbc.queryForObject("select id from pos.online_orders where outlet_id = ? and external_order_id = ?", UUID.class, s.outlet().id(), orderId);
    }

    @Test
    void webhookCreatesAnIncomingOrderWithADeadlineAndKeepsPlatformDataOut() throws Exception {
        var s = setup();
        var watcher = connect(s.outlet().id(), "cashier", "/topic/outlets/" + s.outlet().id() + "/online");
        String body = created(s, "SW-1", "sw-fry", 2);
        hook(body, "bad").andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from pos.online_orders where outlet_id = ?", Integer.class, s.outlet().id())).isZero();

        hook(body, sign(body)).andExpect(status().isOk());
        var row = jdbc.queryForMap("select status, accept_by > now() + interval '170 seconds' as deadline_ok, payload::text as payload from pos.online_orders where outlet_id = ?", s.outlet().id());
        assertThat(row).containsEntry("status", "incoming").containsEntry("deadline_ok", true);
        assertThat((String) row.get("payload")).contains("Less spicy").doesNotContain("Hidden Person");              // customer data stays with the platform
        assertThat(jdbc.queryForObject("select payload::text from pos.gateway_webhook_events where event_id = 'ev-SW-1'", String.class)).contains("Hidden Person"); // full payload lives here
        assertThat(watcher.messages().poll(5, TimeUnit.SECONDS)).containsEntry("type", "incoming");

        hook(body, sign(body)).andExpect(status().isOk());                                                          // duplicate event id is a no-op
        assertThat(jdbc.queryForObject("select count(*) from pos.online_orders where outlet_id = ?", Integer.class, s.outlet().id())).isEqualTo(1);
        watcher.session().disconnect();
    }

    @Test
    void acceptCreatesTheKitchenOrderPaidBillAndStockInOneTransaction() throws Exception {
        var s = setup();
        UUID id = incoming(s, "SW-2", "sw-fry", 2);
        call(post("/online-orders/" + id + "/accept"), as(s.outlet().id(), "kitchen"), Map.of("prepMinutes", 20)).andExpect(status().isForbidden());
        var cashier = as(s.outlet().id(), "cashier");
        call(post("/online-orders/" + id + "/accept"), cashier, Map.of("prepMinutes", 20)).andExpect(status().isOk());

        var online = jdbc.queryForMap("select status, order_id, prep_minutes, decided_by from pos.online_orders where id = ?", id);
        assertThat(online).containsEntry("status", "accepted").containsEntry("prep_minutes", 20);
        assertThat(online.get("order_id")).isNotNull();
        assertThat(online.get("decided_by")).isNotNull();
        var order = jdbc.queryForMap("select order_type, source, payment_status, notes, status from pos.orders where id = ?", online.get("order_id"));
        assertThat(order).containsEntry("order_type", "delivery").containsEntry("source", "swiggy").containsEntry("payment_status", "paid")
                .containsEntry("notes", "Less spicy").containsEntry("status", "new");
        var bill = jdbc.queryForMap("select status, invoice_no from pos.bills where order_id = ?", online.get("order_id"));
        assertThat(bill).containsEntry("status", "paid");
        assertThat(bill.get("invoice_no")).isNotNull();
        assertThat(jdbc.queryForObject("select method || '/' || status from pos.payments where bill_id = (select id from pos.bills where order_id = ?)", String.class, online.get("order_id"))).isEqualTo("platform/succeeded");
        assertThat(stock(s.chicken())).isEqualByComparingTo("9");                                                  // 2 x 0.5 deducted
        assertThat(adapter.calls).contains("accept swiggy SW-2 20");

        call(post("/online-orders/" + id + "/accept"), cashier, Map.of("prepMinutes", 20)).andExpect(status().isConflict());   // decided once

        // in progress, then "hand to rider" completes the already-paid order
        assertThat(json(call(get("/online-orders?tab=progress"), cashier, null)).get(0).get("kitchenStatus").asText()).isEqualTo("new");
        UUID kitchenOrder = (UUID) online.get("order_id");
        call(post("/orders/" + kitchenOrder + "/advance"), as(s.outlet().id(), "kitchen"), null).andExpect(status().isOk());
        call(post("/orders/" + kitchenOrder + "/advance"), as(s.outlet().id(), "kitchen"), null).andExpect(status().isOk());
        call(post("/orders/" + kitchenOrder + "/advance"), cashier, null).andExpect(status().isOk());
        assertThat(json(call(get("/online-orders?tab=progress"), cashier, null))).isEmpty();
        assertThat(json(call(get("/online-orders?tab=today"), cashier, null))).hasSize(1);
    }

    @Test
    void acceptIsRefusedForUnmappedSwitchedOffOrOutOfStockItemsAndLeavesTheOrderIncoming() throws Exception {
        var s = setup();
        var cashier = as(s.outlet().id(), "cashier");

        UUID unmapped = incoming(s, "SW-3", "sw-unknown", 1);
        call(post("/online-orders/" + unmapped + "/accept"), cashier, Map.of("prepMinutes", 15)).andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("not linked")));

        UUID tooMany = incoming(s, "SW-4", "sw-fry", 30);                                                          // needs 15 kg, 10 in stock
        call(post("/online-orders/" + tooMany + "/accept"), cashier, Map.of("prepMinutes", 15)).andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("Not enough chicken")));
        assertThat(stock(s.chicken())).isEqualByComparingTo("10");

        jdbc.update("update pos.menu_items set is_available = false where id = ?", s.fry());
        UUID off = incoming(s, "SW-5", "sw-fry", 1);
        call(post("/online-orders/" + off + "/accept"), cashier, Map.of("prepMinutes", 15)).andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("switched off")));

        assertThat(jdbc.queryForObject("select count(*) from pos.online_orders where outlet_id = ? and status = 'incoming'", Integer.class, s.outlet().id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from pos.orders where outlet_id = ?", Integer.class, s.outlet().id())).isZero();
    }

    @Test
    void rejectNeedsAReasonAndTellsThePlatform() throws Exception {
        var s = setup();
        UUID id = incoming(s, "SW-6", "sw-fry", 1);
        var cashier = as(s.outlet().id(), "cashier");
        call(post("/online-orders/" + id + "/reject"), cashier, Map.of("reason", " ")).andExpect(status().isBadRequest());
        call(post("/online-orders/" + id + "/reject"), cashier, Map.of("reason", "Kitchen is too busy")).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("select status, reject_reason from pos.online_orders where id = ?", id)).containsEntry("status", "rejected").containsEntry("reject_reason", "Kitchen is too busy");
        assertThat(adapter.calls).contains("reject swiggy SW-6 Kitchen is too busy");
    }

    @Test
    void ordersPastTheDeadlineAreMarkedMissedAndCannotBeAccepted() throws Exception {
        var s = setup();
        UUID id = incoming(s, "SW-7", "sw-fry", 1);
        jdbc.update("update pos.online_orders set accept_by = now() - interval '1 second' where id = ?", id);
        call(post("/online-orders/" + id + "/accept"), as(s.outlet().id(), "cashier"), Map.of("prepMinutes", 15)).andExpect(status().isConflict()); // even before the scheduler runs
        onlineService.markMissed();
        assertThat(jdbc.queryForObject("select status from pos.online_orders where id = ?", String.class, id)).isEqualTo("missed");
    }

    @Test
    void platformCancellationStopsTheKitchenAndRestoresStock() throws Exception {
        var s = setup();
        UUID id = incoming(s, "SW-8", "sw-fry", 2);
        call(post("/online-orders/" + id + "/accept"), as(s.outlet().id(), "owner"), Map.of("prepMinutes", 20)).andExpect(status().isOk());
        assertThat(stock(s.chicken())).isEqualByComparingTo("9");

        String body = "{\"eventId\":\"ev-cancel-SW-8\",\"type\":\"order.cancelled\",\"storeId\":\"" + s.store() + "\",\"orderId\":\"SW-8\"}";
        hook(body, sign(body)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from pos.online_orders where id = ?", String.class, id)).isEqualTo("cancelled_by_platform");
        assertThat(jdbc.queryForMap("select status from pos.orders where outlet_id = ?", s.outlet().id())).containsEntry("status", "cancelled");
        assertThat(stock(s.chicken())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select status from pos.bills where outlet_id = ?", String.class, s.outlet().id())).isEqualTo("void");
        assertThat(jdbc.queryForObject("select note from pos.order_status_events e join pos.orders o on o.id = e.order_id where o.outlet_id = ? and e.to_status = 'cancelled'", String.class, s.outlet().id())).isEqualTo("Cancelled by platform");
    }

    @Test
    void pauseSwitchCallsThePlatformAndDeclinesNewOrders() throws Exception {
        var s = setup();
        var cashier = as(s.outlet().id(), "cashier");
        call(patch("/platforms/swiggy"), cashier, Map.of("accepting", false)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select is_accepting from pos.platform_integrations where outlet_id = ?", Boolean.class, s.outlet().id())).isFalse();
        assertThat(adapter.calls).contains("store swiggy off");

        String body = created(s, "SW-9", "sw-fry", 1);
        hook(body, sign(body)).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("select status, reject_reason from pos.online_orders where outlet_id = ?", s.outlet().id())).containsEntry("status", "rejected").containsEntry("reject_reason", "Store paused");
        assertThat(json(call(get("/platforms"), cashier, null)).get(0).get("accepting").asBoolean()).isFalse();
        call(patch("/platforms/swiggy"), as(s.outlet().id(), "kitchen"), Map.of("accepting", true)).andExpect(status().isForbidden());
    }

    @Test
    void onlineOrderIsFoundByThePlatformOrderId() throws Exception {
        var s = setup();
        UUID id = incoming(s, "SW-123456", "sw-fry", 1);
        call(post("/online-orders/" + id + "/accept"), as(s.outlet().id(), "cashier"), Map.of("prepMinutes", 20)).andExpect(status().isOk());
        assertThat(json(call(get("/orders?q=123456"), as(s.outlet().id(), "cashier"), null))).hasSize(1);
    }
}
