package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** M5: STOMP channels for orders and inventory, authorised per role and outlet, published only after commit. */
class RealtimeTest extends IntegrationTest {
    private Map<String, Object> takeaway(UUID dish, int qty) {
        return Map.of("orderType", "takeaway", "items", List.of(Map.of("menuItemId", dish, "quantity", qty)));
    }

    @Test
    void orderEventsReachTheOutletAndRollbacksStaySilent() throws Exception {
        var o = newOutlet();
        UUID chicken = ingredient(o.id(), "Chicken", "kg", "1", "0");
        UUID fry = dish(o, "Chicken Fry", "280", chicken, "0.2");
        var board = connect(o.id(), "kitchen", "/topic/outlets/" + o.id() + "/orders");

        call(post("/orders"), as(o.id(), "cashier"), takeaway(fry, 6)).andExpect(status().isConflict());     // rolled back
        assertThat(board.messages().poll(700, TimeUnit.MILLISECONDS)).isNull();

        call(post("/orders"), as(o.id(), "cashier"), takeaway(fry, 1)).andExpect(status().isCreated());
        var created = board.messages().poll(5, TimeUnit.SECONDS);
        assertThat(created).containsEntry("type", "created").containsEntry("orderNo", 1000);
        board.session().disconnect();
    }

    @Test
    void inventoryEventsGoToOwnersWhenStockRunsLow() throws Exception {
        var o = newOutlet();
        UUID chicken = ingredient(o.id(), "Chicken", "kg", "1", "0.5");
        UUID fry = dish(o, "Chicken Fry", "280", chicken, "0.6");
        var owner = connect(o.id(), "owner", "/topic/outlets/" + o.id() + "/inventory");
        call(post("/orders"), as(o.id(), "cashier"), takeaway(fry, 1)).andExpect(status().isCreated());
        var ev = owner.messages().poll(5, TimeUnit.SECONDS);
        assertThat(ev).containsEntry("name", "Chicken").containsEntry("state", "low");
        owner.session().disconnect();
    }

    @Test
    void subscriptionsAreRefusedForOtherOutletsAndWrongRoles() throws Exception {
        var a = newOutlet();
        var b = newOutlet();
        var kitchen = connect(a.id(), "kitchen", "/topic/outlets/" + a.id() + "/online");
        assertThat(kitchen.errors().poll(5, TimeUnit.SECONDS)).isNotNull();                              // kitchen has no online channel

        var cashier = connect(a.id(), "cashier", "/topic/outlets/" + b.id() + "/orders");
        assertThat(cashier.errors().poll(5, TimeUnit.SECONDS)).isNotNull();                              // someone else's outlet
    }

    @Test
    void socketWithoutCredentialsIsRejected() throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        var errors = new LinkedBlockingQueue<String>();
        client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), new StompHeaders(), new StompSessionHandlerAdapter() {
            @Override public void handleFrame(StompHeaders h, Object p) { errors.add("frame"); }
            @Override public void handleTransportError(StompSession s, Throwable e) { errors.add("closed"); }
        });
        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();
    }
}
