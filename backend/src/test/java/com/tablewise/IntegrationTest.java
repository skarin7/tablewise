package com.tablewise;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tablewise.security.Principals.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import com.tablewise.payments.RazorpayClient;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** One real Postgres 16 shared by every test class; Flyway applies the real migrations. Each test builds its own outlet. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(IntegrationTest.Fakes.class)
@ActiveProfiles("local") // dev decoder: "dev-<user uuid>" is a valid bearer token
public abstract class IntegrationTest {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
    static { PG.start(); }
    protected static final String WEBHOOK_SECRET = "whsec_test";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("tablewise.razorpay.webhook-secret", () -> WEBHOOK_SECRET);
        r.add("tablewise.platform-webhook-secret", () -> WEBHOOK_SECRET);
    }

    @org.springframework.boot.test.web.server.LocalServerPort protected int port;
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper om;

    /** A restaurant: GST 5 %, no service charge, counter "Counter 1", tables T1..T3. */
    protected record Outlet(UUID id, UUID counter, UUID t1, UUID t2, UUID t3, UUID category) {}

    protected Outlet newOutlet() { return newOutlet("5.00", "0"); }

    protected Outlet newOutlet(String gst, String service) {
        UUID org = jdbc.queryForObject("insert into pos.organizations(name) values ('Test Org') returning id", UUID.class);
        UUID id = jdbc.queryForObject("insert into pos.outlets(organization_id, name, gst_percent, service_charge_percent) values (?, 'Saffron Table', ?::numeric, ?::numeric) returning id",
                UUID.class, org, gst, service);
        UUID c = jdbc.queryForObject("insert into pos.counters(outlet_id, name) values (?, 'Counter 1') returning id", UUID.class, id);
        UUID[] t = new UUID[3];
        for (int i = 0; i < 3; i++) t[i] = jdbc.queryForObject("insert into pos.dining_tables(outlet_id, label) values (?, ?) returning id", UUID.class, id, "T" + (i + 1));
        UUID cat = jdbc.queryForObject("insert into pos.menu_categories(outlet_id, name) values (?, 'Mains') returning id", UUID.class, id);
        return new Outlet(id, c, t[0], t[1], t[2], cat);
    }

    protected UUID staffRow(UUID outlet, String role) {
        return jdbc.queryForObject("insert into pos.staff_members(outlet_id, full_name, role, email) values (?, ?, ?, ?) returning id", UUID.class,
                outlet, "Test " + role, role, role + "-" + UUID.randomUUID() + "@example.com");
    }

    /** Signs a request in as a staff member with this role (a fresh staff row), bypassing the JWT step. */
    protected RequestPostProcessor as(UUID outlet, String role) {
        return as(outlet, role, staffRow(outlet, role));
    }

    protected RequestPostProcessor as(UUID outlet, String role, UUID staffId) {
        return authentication(PosAuth.staff(new StaffPrincipal(staffId, outlet, role, "Test " + role)));
    }

    protected UUID ingredient(UUID outlet, String name, String unit, String stock, String reorder) {
        UUID id = jdbc.queryForObject("insert into pos.ingredients(outlet_id, name, unit, reorder_level) values (?,?,?,?::numeric) returning id", UUID.class, outlet, name, unit, reorder);
        if (new BigDecimal(stock).signum() > 0) {
            jdbc.update("insert into pos.stock_movements(outlet_id, ingredient_id, quantity_delta, reason) values (?,?,?::numeric,'opening_stock')", outlet, id, stock);
        }
        return id;
    }

    protected BigDecimal stock(UUID ingredient) {
        return jdbc.queryForObject("select current_stock from pos.ingredients where id = ?", BigDecimal.class, ingredient);
    }

    /** A dish with an optional recipe of {ingredientId, qtyPerPortion} pairs. */
    protected UUID dish(Outlet o, String name, String price, Object... recipe) {
        UUID id = jdbc.queryForObject("insert into pos.menu_items(outlet_id, category_id, name, price, is_veg) values (?,?,?,?::numeric,true) returning id", UUID.class,
                o.id(), o.category(), name, price);
        for (int i = 0; i < recipe.length; i += 2) {
            jdbc.update("insert into pos.recipe_items(menu_item_id, ingredient_id, qty_per_portion) values (?,?,?::numeric)", id, recipe[i], recipe[i + 1].toString());
        }
        return id;
    }


    /** Stands in for Razorpay: remembers QR codes it created and closed, and which payments a poll should report. */
    public static class FakeRazorpay implements RazorpayClient {
        public final List<String> created = new CopyOnWriteArrayList<>(), closed = new CopyOnWriteArrayList<>();
        public final Map<String, List<Captured>> captured = new ConcurrentHashMap<>();

        @Override public Qr createQr(UUID orderId, int orderNo, java.math.BigDecimal amount, java.time.Instant closeBy) {
            String id = "qr_" + UUID.randomUUID().toString().substring(0, 8);
            created.add(id);
            return new Qr(id, "upi://pay?pa=test@upi&am=" + amount + "&tn=" + orderNo, closeBy);
        }
        @Override public void closeQr(String qrId) { closed.add(qrId); }
        @Override public List<Captured> capturedPayments(String qrId) { return captured.getOrDefault(qrId, List.of()); }
        @Override public boolean configured() { return true; }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary FakeRazorpay fakeRazorpay() { return new FakeRazorpay(); }
    }

    @Autowired protected FakeRazorpay razorpay;

    record Socket(StompSession session, BlockingQueue<Map<String, Object>> messages, BlockingQueue<String> errors) {}

    protected Socket connect(UUID outlet, String role, String... topics) throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("update pos.staff_members set user_id = ? where id = ?", user, staffRow(outlet, role));
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        var errors = new LinkedBlockingQueue<String>();
        var headers = new StompHeaders();
        headers.add("Authorization", "Bearer dev-" + user);
        var session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
            @Override public void handleFrame(StompHeaders h, Object payload) { errors.add(String.valueOf(h.getFirst("message"))); }
            @Override public void handleTransportError(StompSession s, Throwable e) { errors.add("transport " + e.getMessage()); }
        }).get(10, TimeUnit.SECONDS);
        var messages = new LinkedBlockingQueue<Map<String, Object>>();
        for (String t : topics) {
            session.subscribe(t, new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders h) { return Map.class; }
                @SuppressWarnings("unchecked") @Override public void handleFrame(StompHeaders h, Object p) { messages.add((Map<String, Object>) p); }
            });
        }
        Thread.sleep(300); // let the subscriptions register
        return new Socket(session, messages, errors);
    }


    /** A cashier places a one-dish takeaway order and gets its id. */
    protected UUID placeOrder(Outlet o, UUID dish, int qty) throws Exception {
        var body = Map.of("orderType", "takeaway", "items", List.of(Map.of("menuItemId", dish, "quantity", qty)));
        return UUID.fromString(json(call(post("/orders"), as(o.id(), "cashier"), body)).get("id").asText());
    }

    protected ResultActions call(MockHttpServletRequestBuilder req, RequestPostProcessor who, Object body) throws Exception {
        req.with(who);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        return mvc.perform(req);
    }

    protected JsonNode json(ResultActions r) throws Exception { return om.readTree(r.andReturn().getResponse().getContentAsString()); }
}
