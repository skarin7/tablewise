package com.tablewise.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tablewise.common.ApiException;
import com.tablewise.common.Db;
import com.tablewise.domain.Online.*;
import com.tablewise.domain.Repos.*;
import com.tablewise.orders.OrderService;
import com.tablewise.payments.PaymentService;
import com.tablewise.realtime.Realtime;
import com.tablewise.security.Principals.StaffPrincipal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The online orders inbox and the decisions on it (README 7.5). */
@Service
public class OnlineService {
    private static final Logger log = LoggerFactory.getLogger(OnlineService.class);
    static final Set<String> PLATFORMS = Set.of("swiggy", "zomato", "website");

    private final PlatformAdapter adapter;
    private final IntegrationRepo integrations;
    private final ItemMappingRepo mappings;
    private final OnlineOrderRepo onlineOrders;
    private final OrderRepo orders;
    private final MenuItemRepo menuItems;
    private final OrderService orderService;
    private final PaymentService payments;
    private final OutletRepo outlets;
    private final Realtime realtime;
    private final JdbcTemplate jdbc;
    private final Db db;
    private final ObjectMapper json;

    OnlineService(PlatformAdapter adapter, IntegrationRepo integrations, ItemMappingRepo mappings, OnlineOrderRepo onlineOrders, OrderRepo orders, MenuItemRepo menuItems,
                  OrderService orderService, PaymentService payments, OutletRepo outlets, Realtime realtime, JdbcTemplate jdbc, Db db, ObjectMapper json) {
        this.adapter = adapter;
        this.integrations = integrations;
        this.mappings = mappings;
        this.onlineOrders = onlineOrders;
        this.orders = orders;
        this.menuItems = menuItems;
        this.orderService = orderService;
        this.payments = payments;
        this.outlets = outlets;
        this.realtime = realtime;
        this.jdbc = jdbc;
        this.db = db;
        this.json = json;
    }

    // ----------------------------------------------------------------- intake

    @Transactional
    public void intake(String platform, String rawBody, String signature) {
        if (!PLATFORMS.contains(platform)) throw ApiException.notFound("Unknown platform");
        if (!adapter.verify(rawBody, signature)) {
            log.warn("{} webhook refused: bad or missing signature", platform);
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Bad signature");
        }
        JsonNode root;
        try { root = json.readTree(rawBody); } catch (Exception e) { throw ApiException.badRequest("Bad payload"); }
        var ev = adapter.parse(root);
        Long row = jdbc.query("""
                insert into pos.gateway_webhook_events(provider, event_id, event_type, payload, signature_valid) values (?, ?, ?, ?::jsonb, true)
                on conflict (provider, event_id) do nothing returning id""", rs -> rs.next() ? rs.getLong(1) : null, platform, ev.eventId(), ev.type(), rawBody);
        if (row == null) { // retry of an event we already have
            log.info("{} webhook {} already handled, ignored", platform, ev.eventId());
            return;
        }

        String error = null;
        var integ = integrations.findByPlatformAndExternalStoreId(platform, ev.storeId()).orElse(null);
        if (integ == null) {
            error = "unknown store " + ev.storeId();
        } else if ("order.cancelled".equals(ev.type())) {
            platformCancelled(integ, ev);
        } else if ("order.created".equals(ev.type())) {
            created(integ, ev);
        } else {
            error = "unhandled event " + ev.type();
        }
        jdbc.update("update pos.gateway_webhook_events set processed_at = now(), error = ? where id = ?", error, row);
        if (error != null) log.warn("{} webhook {} ({}) stored but not applied: {}", platform, ev.eventId(), ev.type(), error);
        else log.info("{} webhook {} ({}) processed for platform order {}", platform, ev.eventId(), ev.type(), ev.externalOrderId());
    }

    private void created(Integration integ, PlatformAdapter.Event ev) {
        if (onlineOrders.findByIntegrationIdAndExternalOrderId(integ.getId(), ev.externalOrderId()).isPresent()) return;
        var o = new OnlineOrder();
        o.setOutletId(integ.getOutletId());
        o.setIntegrationId(integ.getId());
        o.setExternalOrderId(ev.externalOrderId());
        o.setAcceptBy(Instant.now().plusSeconds(integ.getAcceptWindowSeconds()));
        // only what the card shows: items and instructions. Customer and rider data stay with the platform.
        o.setPayload(Map.of("items", ev.items().stream().map(i -> Map.of("itemId", i.externalItemId(), "name", i.name(), "quantity", i.quantity(), "price", i.price())).toList(),
                "instructions", ev.instructions() == null ? "" : ev.instructions()));
        if (!integ.isAccepting()) { // the pause switch is on, so decline straight away
            o.setStatus("rejected");
            o.setRejectReason("Store paused");
            o.setDecidedAt(Instant.now());
            onlineOrders.saveAndFlush(o);
            log.info("online order {} declined: {} store is paused", ev.externalOrderId(), integ.getPlatform());
            afterCommit(() -> adapter.reject(integ.getPlatform(), ev.externalOrderId(), "Store paused"));
            return;
        }
        onlineOrders.saveAndFlush(o);
        realtime.online(o.getOutletId(), "incoming", o.getId());
        log.info("online order received: {} {} must be accepted by {}", integ.getPlatform(), o.getExternalOrderId(), o.getAcceptBy());
    }

    private void platformCancelled(Integration integ, PlatformAdapter.Event ev) {
        var o = onlineOrders.findByIntegrationIdAndExternalOrderId(integ.getId(), ev.externalOrderId()).orElse(null);
        if (o == null || "cancelled_by_platform".equals(o.getStatus()) || "rejected".equals(o.getStatus()) || "missed".equals(o.getStatus())) return;
        if (o.getOrderId() != null) {
            var kitchen = orders.lockByIdAndOutletId(o.getOrderId(), o.getOutletId()).orElseThrow();
            if (!Set.of("completed", "cancelled").contains(kitchen.getStatus())) orderService.cancelOrder(kitchen, "Cancelled by platform", null); // the kitchen stops
        }
        o.setStatus("cancelled_by_platform");
        o.setDecidedAt(o.getDecidedAt() == null ? Instant.now() : o.getDecidedAt());
        onlineOrders.saveAndFlush(o);
        realtime.online(o.getOutletId(), "cancelled_by_platform", o.getId());
        log.info("online order {} cancelled by {}; kitchen order {}", o.getExternalOrderId(), integ.getPlatform(), o.getOrderId() == null ? "none" : "stopped");
    }

    // -------------------------------------------------------------- decisions

    @Transactional
    public Map<String, Object> accept(UUID id, int prepMinutes, StaffPrincipal me) {
        if (prepMinutes < 1 || prepMinutes > 180) throw ApiException.badRequest("Choose a preparation time between 1 and 180 minutes");
        var o = find(id, me);
        var integ = integrations.findById(o.getIntegrationId()).orElseThrow();
        if (o.getAcceptBy().isBefore(Instant.now())) {
            log.warn("online order {} accept refused: deadline passed", o.getExternalOrderId());
            throw ApiException.conflict("The time to accept this order has passed");
        }

        var lines = new ArrayList<OrderService.Line>();
        for (var item : items(o)) {
            var map = mappings.findByIntegrationIdAndExternalItemId(integ.getId(), item.get("itemId").toString())
                    .orElseThrow(() -> ApiException.conflict(item.get("name") + " is not linked to a dish on your menu"));
            lines.add(new OrderService.Line(map.getMenuItemId(), ((Number) item.get("quantity")).intValue(), null));
        }
        // OrderService refuses switched-off or out-of-stock dishes and rolls everything back, so the order stays incoming
        var created = orderService.create(me.outletId(), me.staffId(), new OrderService.Spec("delivery", integ.getPlatform(), null, null, null, null,
                (String) o.getPayload().get("instructions"), null, null, lines));
        payments.recordPlatformPayment(created.order(), created.bill());

        o.setStatus("accepted");
        o.setOrderId(created.order().getId());
        o.setPrepMinutes((short) prepMinutes);
        o.setDecidedBy(me.staffId());
        o.setDecidedAt(Instant.now());
        onlineOrders.saveAndFlush(o);
        realtime.online(me.outletId(), "accepted", o.getId());
        log.info("online order accepted: {} {} as #{} prep {} min", integ.getPlatform(), o.getExternalOrderId(), created.order().getOrderNo(), prepMinutes);
        afterCommit(() -> adapter.accept(integ.getPlatform(), o.getExternalOrderId(), prepMinutes));
        return Map.of("orderId", created.order().getId(), "orderNo", created.order().getOrderNo());
    }

    @Transactional
    public void reject(UUID id, String reason, StaffPrincipal me) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("Choose a reason for rejecting this order");
        var o = find(id, me);
        var integ = integrations.findById(o.getIntegrationId()).orElseThrow();
        o.setStatus("rejected");
        o.setRejectReason(reason.trim());
        o.setDecidedBy(me.staffId());
        o.setDecidedAt(Instant.now());
        onlineOrders.saveAndFlush(o);
        realtime.online(me.outletId(), "rejected", o.getId());
        log.info("online order rejected: {} {} reason=\"{}\"", integ.getPlatform(), o.getExternalOrderId(), reason.trim());
        afterCommit(() -> adapter.reject(integ.getPlatform(), o.getExternalOrderId(), reason.trim()));
    }

    private OnlineOrder find(UUID id, StaffPrincipal me) {
        var o = onlineOrders.findByIdAndOutletId(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Online order not found"));
        if (!"incoming".equals(o.getStatus())) throw ApiException.conflict("This order is already " + o.getStatus().replace('_', ' ') + ".");
        return o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(OnlineOrder o) { return (List<Map<String, Object>>) o.getPayload().get("items"); }

    /** A scheduler marks incoming orders past their deadline as missed. */
    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    @Transactional
    public void markMissed() {
        jdbc.query("update pos.online_orders set status = 'missed' where status = 'incoming' and accept_by < now() returning id, outlet_id",
                (rs, i) -> { realtime.online(rs.getObject(2, UUID.class), "missed", rs.getObject(1, UUID.class)); log.warn("online order {} missed: nobody decided in time", rs.getObject(1, UUID.class)); return null; });
    }

    // ------------------------------------------------------------------ views

    public List<Map<String, Object>> list(String tab, StaffPrincipal me) {
        var tz = ZoneId.of(outlets.findById(me.outletId()).orElseThrow().getTimezone());
        String where = switch (tab == null ? "incoming" : tab) {
            case "progress" -> "oo.status = 'accepted' and k.status in ('new','preparing','ready')";
            case "today" -> "(oo.received_at at time zone ?)::date = ?";
            default -> "oo.status = 'incoming'";
        };
        var args = new ArrayList<Object>(List.of(me.outletId()));
        if ("today".equals(tab)) { args.add(tz.getId()); args.add(LocalDate.now(tz)); }
        return db.rows("""
                select oo.id, ip.platform, oo.external_order_id, oo.status, oo.received_at, oo.accept_by, oo.prep_minutes, oo.reject_reason, oo.decided_at, oo.payload,
                       oo.order_id, k.order_no, k.status as kitchen_status, k.status_changed_at as kitchen_status_at
                  from pos.online_orders oo join pos.platform_integrations ip on ip.id = oo.integration_id left join pos.orders k on k.id = oo.order_id
                 where oo.outlet_id = ? and""" + " " + where + " order by oo.received_at desc limit 200", args.toArray());
    }

    public List<Map<String, Object>> platforms(StaffPrincipal me) {
        return db.rows("select platform, is_accepting as accepting, external_store_id, accept_window_seconds from pos.platform_integrations where outlet_id = ? order by platform", me.outletId());
    }

    @Transactional
    public void setAccepting(String platform, boolean accepting, StaffPrincipal me) {
        var integ = integration(platform, me);
        integ.setAccepting(accepting);
        integrations.save(integ);
        log.info("{} orders {}", platform, accepting ? "resumed" : "paused");
        afterCommit(() -> adapter.setStoreOpen(platform, accepting));
    }

    @Transactional
    public void setup(String platform, String storeId, Short windowSeconds, StaffPrincipal me) {
        var integ = integration(platform, me);
        if (storeId != null) integ.setExternalStoreId(storeId);
        if (windowSeconds != null) integ.setAcceptWindowSeconds(windowSeconds);
        integrations.save(integ);
    }

    @Transactional
    public void map(String platform, String externalItemId, UUID menuItemId, StaffPrincipal me) {
        var integ = integration(platform, me);
        menuItems.findByIdAndOutletIdAndDeletedAtIsNull(menuItemId, me.outletId()).orElseThrow(() -> ApiException.badRequest("Unknown dish"));
        var m = mappings.findByIntegrationIdAndExternalItemId(integ.getId(), externalItemId).orElseGet(() -> {
            var n = new ItemMapping();
            n.setIntegrationId(integ.getId());
            n.setExternalItemId(externalItemId);
            return n;
        });
        m.setMenuItemId(menuItemId);
        mappings.save(m);
    }

    private Integration integration(String platform, StaffPrincipal me) {
        if (!PLATFORMS.contains(platform)) throw ApiException.notFound("Unknown platform");
        return integrations.findByOutletIdAndPlatform(me.outletId(), platform).orElseGet(() -> {
            var n = new Integration();
            n.setOutletId(me.outletId());
            n.setPlatform(platform);
            return integrations.saveAndFlush(n);
        });
    }

    private void afterCommit(Runnable r) {
        Runnable safe = () -> { try { r.run(); } catch (RuntimeException e) { log.warn("platform call failed: {}", e.getMessage()); } };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { safe.run(); } });
        } else {
            safe.run();
        }
    }
}
