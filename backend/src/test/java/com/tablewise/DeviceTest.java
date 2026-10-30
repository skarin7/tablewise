package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** M7: pairing codes, device tokens, the display channel and revocation (README 7.6). */
class DeviceTest extends IntegrationTest {
    private Socket connectDevice(String token, String... topics) throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        var errors = new LinkedBlockingQueue<String>();
        var headers = new StompHeaders();
        headers.add("X-Device-Token", token);
        var session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
            @Override public void handleFrame(StompHeaders h, Object p) { errors.add(String.valueOf(h.getFirst("message"))); }
            @Override public void handleTransportError(StompSession s, Throwable e) { errors.add("closed"); }
        }).get(10, TimeUnit.SECONDS);
        var messages = new LinkedBlockingQueue<Map<String, Object>>();
        for (String t : topics) {
            session.subscribe(t, new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders h) { return Map.class; }
                @SuppressWarnings("unchecked") @Override public void handleFrame(StompHeaders h, Object p) { messages.add((Map<String, Object>) p); }
            });
        }
        Thread.sleep(300);
        return new Socket(session, messages, errors);
    }

    private String pairNewDevice(Outlet o, UUID deviceKey) throws Exception {
        call(post("/devices/pairing-codes"), anon(), Map.of("deviceKey", deviceKey)).andExpect(status().isCreated());
        String code = json(mvc.perform(get("/devices/pairing-codes/" + deviceKey))).get("code").asText();
        call(post("/devices/pair"), as(o.id(), "owner"), Map.of("code", code, "counterId", o.counter(), "name", "Counter 1 display")).andExpect(status().isOk());
        return json(mvc.perform(get("/devices/pairing-codes/" + deviceKey))).get("token").asText();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor anon() { return r -> r; }

    @Test
    void pairingFlowIssuesTheTokenOnceAndStoresOnlyItsHash() throws Exception {
        var o = newOutlet();
        UUID key = UUID.randomUUID();
        var first = json(call(post("/devices/pairing-codes"), anon(), Map.of("deviceKey", key)).andExpect(status().isCreated()));
        assertThat(first.get("code").asText()).matches("\\d{6}");
        assertThat(json(call(post("/devices/pairing-codes"), anon(), Map.of("deviceKey", key))).get("code").asText()).isEqualTo(first.get("code").asText()); // same tablet, same code
        assertThat(json(mvc.perform(get("/devices/pairing-codes/" + key))).get("status").asText()).isEqualTo("pending");

        String code = first.get("code").asText();
        call(post("/devices/pair"), as(o.id(), "cashier"), Map.of("code", code, "counterId", o.counter(), "name", "D")).andExpect(status().isForbidden());
        call(post("/devices/pair"), as(o.id(), "owner"), Map.of("code", "000000", "counterId", o.counter(), "name", "D")).andExpect(status().isNotFound());
        call(post("/devices/pair"), as(o.id(), "owner"), Map.of("code", code, "counterId", o.counter(), "name", "Counter 1 display")).andExpect(status().isOk());

        var paired = json(mvc.perform(get("/devices/pairing-codes/" + key)));
        String token = paired.get("token").asText();
        assertThat(paired.get("counterId").asText()).isEqualTo(o.counter().toString());
        assertThat(json(mvc.perform(get("/devices/pairing-codes/" + key))).has("token")).isFalse();                     // handed over once
        byte[] hash = jdbc.queryForObject("select token_hash from pos.devices where outlet_id = ?", byte[].class, o.id());
        assertThat(hash).isEqualTo(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        call(post("/devices/pair"), as(o.id(), "owner"), Map.of("code", code, "counterId", o.counter(), "name", "again")).andExpect(status().isNotFound()); // code is single use
    }

    @Test
    void expiredCodesCannotBeClaimed() throws Exception {
        var o = newOutlet();
        UUID key = UUID.randomUUID();
        String code = json(call(post("/devices/pairing-codes"), anon(), Map.of("deviceKey", key))).get("code").asText();
        jdbc.update("update pos.device_pairing_codes set expires_at = now() - interval '1 minute' where device_key = ?", key);
        assertThat(json(mvc.perform(get("/devices/pairing-codes/" + key))).get("status").asText()).isEqualTo("expired");
        call(post("/devices/pair"), as(o.id(), "owner"), Map.of("code", code, "counterId", o.counter(), "name", "D")).andExpect(status().isNotFound());
    }

    @Test
    void deviceSocketSeesOnlyItsCounterAndShowsOnline() throws Exception {
        var o = newOutlet();
        var other = newOutlet();
        String token = pairNewDevice(o, UUID.randomUUID());
        var ok = connectDevice(token, "/topic/counters/" + o.counter() + "/display");
        assertThat(ok.errors().poll(500, TimeUnit.MILLISECONDS)).isNull();

        var online = json(call(get("/devices"), as(o.id(), "owner"), null));
        assertThat(online.get(0).get("online").asBoolean()).isTrue();

        var wrongCounter = connectDevice(token, "/topic/counters/" + other.counter() + "/display");
        assertThat(wrongCounter.errors().poll(5, TimeUnit.SECONDS)).isNotNull();
        var wrongChannel = connectDevice(token, "/topic/outlets/" + o.id() + "/orders");
        assertThat(wrongChannel.errors().poll(5, TimeUnit.SECONDS)).isNotNull();
        ok.session().disconnect();
    }

    @Test
    void revokingADeviceClosesItsSocketAndBlocksReconnects() throws Exception {
        var o = newOutlet();
        String token = pairNewDevice(o, UUID.randomUUID());
        var socket = connectDevice(token, "/topic/counters/" + o.counter() + "/display");
        assertThat(socket.session().isConnected()).isTrue();
        UUID id = jdbc.queryForObject("select id from pos.devices where outlet_id = ?", UUID.class, o.id());

        call(delete("/devices/" + id), as(o.id(), "cashier"), null).andExpect(status().isForbidden());
        call(delete("/devices/" + id), as(o.id(), "owner"), null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select revoked_at is not null from pos.devices where id = ?", Boolean.class, id)).isTrue();
        for (int i = 0; i < 50 && socket.session().isConnected(); i++) Thread.sleep(100);
        assertThat(socket.session().isConnected()).isFalse();

        assertThatThrownBy(() -> connectDevice(token)).isNotNull(); // CONNECT is refused for a revoked token
        assertThat(json(call(get("/devices"), as(o.id(), "owner"), null))).isEmpty();
    }

    @Test
    void anUnknownTokenIsRejected() throws Exception {
        assertThatThrownBy(() -> connectDevice("dev_not_a_real_token")).isNotNull();
    }
}
