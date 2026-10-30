package com.tablewise.orders;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.tablewise.common.ApiException;
import com.tablewise.common.Db;
import com.tablewise.domain.Repos.*;
import com.tablewise.domain.Sales.*;
import com.tablewise.inventory.StockService;
import com.tablewise.realtime.Realtime;
import com.tablewise.security.Principals.StaffPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Placing orders (order + items + bill + numbering + stock in one transaction), status changes, cancellation, board and search. */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record Line(UUID menuItemId, int quantity, String notes) {}

    /** Everything needed to create an order, whether it comes from the POS or an accepted online order. */
    public record Spec(String type, String source, UUID tableId, UUID counterId, String customerName, String customerPhone, String notes,
                       String discountType, BigDecimal discountValue, List<Line> lines) {}

    public record Created(PosOrder order, Bill bill) {}

    private final OrderRepo orders;
    private final OrderItemRepo orderItems;
    private final BillRepo bills;
    private final StatusEventRepo events;
    private final OutletRepo outlets;
    private final MenuItemRepo menuItems;
    private final RecipeRepo recipes;
    private final TableRepo tables;
    private final CustomerRepo customers;
    private final NumberingService numbering;
    private final StockService stock;
    private final Realtime realtime;
    private final JdbcTemplate jdbc;
    private final Db db;
    private final java.util.function.Consumer<UUID> closePendingPayment;

    OrderService(OrderRepo orders, OrderItemRepo orderItems, BillRepo bills, StatusEventRepo events, OutletRepo outlets, MenuItemRepo menuItems,
                 RecipeRepo recipes, TableRepo tables, CustomerRepo customers, NumberingService numbering, StockService stock, Realtime realtime,
                 JdbcTemplate jdbc, Db db, org.springframework.beans.factory.ObjectProvider<PaymentHooks> paymentHooks) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.bills = bills;
        this.events = events;
        this.outlets = outlets;
        this.menuItems = menuItems;
        this.recipes = recipes;
        this.tables = tables;
        this.customers = customers;
        this.numbering = numbering;
        this.stock = stock;
        this.realtime = realtime;
        this.jdbc = jdbc;
        this.db = db;
        this.closePendingPayment = billId -> paymentHooks.ifAvailable(h -> h.closePending(billId));
    }

    /** Implemented by the payments package so cancelling an order also closes a live QR. */
    public interface PaymentHooks {
        void closePending(UUID billId);
    }

    // ------------------------------------------------------------------ place

    @Transactional
    public Created create(UUID outletId, UUID createdBy, Spec s) {
        var outlet = outlets.findById(outletId).orElseThrow();
        if (s.lines() == null || s.lines().isEmpty()) throw ApiException.badRequest("Add at least one dish first");
        // one line per dish
        var qty = new LinkedHashMap<UUID, Integer>();
        var lineNotes = new HashMap<UUID, String>();
        for (var l : s.lines()) {
            if (l.quantity() <= 0) throw ApiException.badRequest("Quantity must be at least 1");
            qty.merge(l.menuItemId(), l.quantity(), Integer::sum);
            if (l.notes() != null) lineNotes.put(l.menuItemId(), l.notes());
        }
        var dishes = menuItems.findByOutletIdAndIdInAndDeletedAtIsNull(outletId, qty.keySet()).stream().collect(Collectors.toMap(d -> d.getId(), d -> d));
        if (dishes.size() != qty.size()) throw ApiException.badRequest("A dish on this order is no longer on the menu");
        for (var d : dishes.values()) {
            String state = jdbc.queryForObject("select stock_state from pos.v_menu_availability where menu_item_id = ?", String.class, d.getId());
            if ("off".equals(state)) throw ApiException.conflict(d.getName() + " is switched off");
            if ("out".equals(state)) throw ApiException.conflict(d.getName() + " is out of stock");
        }

        UUID tableId = null;
        if ("dine_in".equals(s.type())) {
            var table = s.tableId() == null ? null : tables.findByIdAndOutletId(s.tableId(), outletId).filter(t -> t.isActive()).orElse(null);
            if (table == null) throw ApiException.badRequest("Pick a table for this dine-in order");
            if (Boolean.TRUE.equals(jdbc.queryForObject("select is_busy from pos.v_table_occupancy where table_id = ?", Boolean.class, table.getId()))) {
                throw ApiException.conflict(table.getLabel() + " already has an open order");
            }
            tableId = table.getId();
        }
        String phone = normalisePhone(s.customerPhone());
        final String typedName = s.customerName() == null || s.customerName().isBlank() ? null : s.customerName().trim();
        String name = typedName;
        if ("delivery".equals(s.type()) && "pos".equals(s.source()) && (name == null || phone == null)) {
            throw ApiException.badRequest("Delivery needs a customer name and a 10-digit phone number");
        }

        UUID customerId = null;
        if (phone != null) {
            var c = customers.findByOutletIdAndPhone(outletId, phone).orElseGet(() -> {
                var n = new Customer();
                n.setOutletId(outletId);
                n.setPhone(phone);
                n.setName(typedName == null ? "Guest" : typedName);
                return customers.save(n);
            });
            customerId = c.getId();
            if (name == null) name = c.getName();
        }

        var o = new PosOrder();
        o.setOutletId(outletId);
        o.setOrderNo(numbering.nextOrderNo(outletId));
        o.setBusinessDate(LocalDate.now(ZoneId.of(outlet.getTimezone())));
        o.setOrderType(s.type());
        o.setSource(s.source());
        o.setTableId(tableId);
        o.setCounterId(s.counterId());
        o.setCustomerId(customerId);
        o.setNotes(s.notes());
        o.setCreatedBy(createdBy);
        orders.saveAndFlush(o);
        MDC.put("orderId", o.getId().toString());

        var billLines = new ArrayList<BillCalculator.Line>();
        qty.forEach((id, q) -> {
            var d = dishes.get(id);
            var i = new OrderItem();
            i.setOrderId(o.getId());
            i.setMenuItemId(id);
            i.setItemName(d.getName());
            i.setVeg(d.isVeg());
            i.setUnitPrice(d.getPrice());
            i.setQuantity(q);
            i.setLineTotal(d.getPrice().multiply(BigDecimal.valueOf(q)));
            i.setNotes(lineNotes.get(id));
            orderItems.save(i);
            billLines.add(new BillCalculator.Line(d.getPrice(), q));
        });

        var r = BillCalculator.calc(billLines, s.discountType(), s.discountValue(), outlet.getServiceChargePercent(), outlet.getGstPercent());
        var b = new Bill();
        b.setOutletId(outletId);
        b.setOrderId(o.getId());
        b.setBillToName(name);
        b.setBillToPhone(phone);
        b.setSubtotal(r.subtotal());
        b.setDiscountType(r.discountType());
        b.setDiscountValue(r.discountValue());
        b.setDiscountAmount(r.discountAmount());
        b.setServiceChargePercent(outlet.getServiceChargePercent());
        b.setServiceChargeAmount(r.serviceChargeAmount());
        b.setTaxableAmount(r.taxableAmount());
        b.setGstPercent(outlet.getGstPercent());
        b.setCgstAmount(r.cgst());
        b.setSgstAmount(r.sgst());
        b.setRoundOff(r.roundOff());
        b.setTotalAmount(r.total());
        bills.saveAndFlush(b);

        event(o.getId(), null, "new", createdBy, null);
        consumeStock(outletId, o.getId(), createdBy, qty);
        realtime.orders(outletId, "created", o.getId(), o.getOrderNo());
        log.info("order placed: #{} {} source={} dishes={} total={}", o.getOrderNo(), s.type(), s.source(), qty.size(), r.total());
        return new Created(o, b);
    }

    /** One order_consumption movement per ingredient. The DB check rolls the whole order back if any stock would go negative. */
    private void consumeStock(UUID outletId, UUID orderId, UUID by, Map<UUID, Integer> qty) {
        var need = new TreeMap<UUID, BigDecimal>();
        recipes.findByMenuItemIdIn(qty.keySet()).forEach(r ->
                need.merge(r.getIngredientId(), r.getQtyPerPortion().multiply(BigDecimal.valueOf(qty.get(r.getMenuItemId()))), BigDecimal::add));
        need.forEach((ing, q) -> stock.move(outletId, ing, q.negate(), "order_consumption", orderId, by, null, null));
    }

    // ---------------------------------------------------------------- status

    @Transactional
    public void advance(UUID orderId, StaffPrincipal me) {
        var o = lock(orderId, me.outletId());
        String to, action;
        Set<String> allowed;
        switch (o.getStatus()) {
            case "new" -> { to = "preparing"; action = "start preparing"; allowed = Set.of("owner", "kitchen"); }
            case "preparing" -> { to = "ready"; action = "mark ready"; allowed = Set.of("owner", "kitchen"); }
            case "ready" -> { to = "completed"; action = serveLabel(o); allowed = Set.of("owner", "cashier"); }
            default -> throw ApiException.conflict("This order is already " + o.getStatus() + ".");
        }
        if (!allowed.contains(me.role())) {
            log.warn("order action refused: #{} {} by {}", o.getOrderNo(), action, me.role());
            throw ApiException.forbidden(label(me.role()) + " cannot " + action + ".");
        }
        if ("completed".equals(to) && !"dine_in".equals(o.getOrderType()) && !"paid".equals(o.getPaymentStatus())) {
            throw ApiException.conflict("Collect payment before you " + action + " a " + o.getOrderType().replace('_', '-') + " order.");
        }
        transition(o, to, me.staffId(), null);
        realtime.orders(o.getOutletId(), "status", o.getId(), o.getOrderNo());
        log.info("order status: #{} -> {} by {}", o.getOrderNo(), to, me.role());
    }

    @Transactional
    public void cancel(UUID orderId, String reason, StaffPrincipal me) {
        var o = lock(orderId, me.outletId());
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("Give a reason for cancelling this order");
        switch (o.getStatus()) {
            case "new" -> { if (me.kitchen()) throw ApiException.forbidden("Kitchen cannot cancel orders."); }
            case "preparing" -> { if (!me.owner()) throw ApiException.forbidden("Only the owner can cancel an order the kitchen has started."); }
            default -> throw ApiException.conflict("A " + o.getStatus() + " order cannot be cancelled.");
        }
        cancelOrder(o, reason.trim(), me.staffId());
    }

    /** Used by staff cancellation and by "cancelled by the platform". Stock returns only if the kitchen had not started. */
    @Transactional
    public void cancelOrder(PosOrder o, String reason, UUID actor) {
        boolean restore = "new".equals(o.getStatus());
        bills.findByOrderIdAndStatusNot(o.getId(), "void").ifPresent(b -> {
            closePendingPayment.accept(b.getId());
            b.setStatus("void");
        });
        if (restore) {
            jdbc.query("select ingredient_id, -sum(quantity_delta) as q from pos.stock_movements where order_id = ? and reason = 'order_consumption' group by ingredient_id order by ingredient_id",
                    (rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), o.getId())
                    .forEach(e -> stock.move(o.getOutletId(), e.getKey(), e.getValue(), "order_cancellation", o.getId(), actor, reason, null));
        }
        transition(o, "cancelled", actor, reason);
        realtime.orders(o.getOutletId(), "cancelled", o.getId(), o.getOrderNo());
        log.info("order cancelled: #{} stockRestored={} reason=\"{}\"", o.getOrderNo(), restore, reason);
    }

    private void transition(PosOrder o, String to, UUID actor, String note) {
        String from = o.getStatus();
        o.setStatus(to);
        o.setStatusChangedAt(Instant.now());
        orders.saveAndFlush(o);
        event(o.getId(), from, to, actor, note);
    }

    private void event(UUID orderId, String from, String to, UUID actor, String note) {
        var e = new OrderStatusEvent();
        e.setOrderId(orderId);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setActorStaffId(actor);
        e.setNote(note);
        events.save(e);
    }

    private PosOrder lock(UUID id, UUID outletId) {
        MDC.put("orderId", id.toString());
        return orders.lockByIdAndOutletId(id, outletId).orElseThrow(() -> ApiException.notFound("Order not found"));
    }

    private static String serveLabel(PosOrder o) {
        return !"pos".equals(o.getSource()) ? "hand to rider" : switch (o.getOrderType()) { case "dine_in" -> "mark served"; case "takeaway" -> "hand over"; default -> "dispatch"; };
    }

    private static String label(String role) { return switch (role) { case "owner" -> "Owner"; case "cashier" -> "Cashier"; default -> "Kitchen"; }; }

    // ----------------------------------------------------------------- board

    private static final Set<String> MONEY = Set.of("billId", "billStatus", "subtotal", "discountAmount", "serviceChargeAmount", "taxableAmount", "cgstAmount",
            "sgstAmount", "roundOff", "total", "invoiceNo", "billToName", "billToPhone", "paymentStatus");

    /** Open orders, today's orders and every unpaid order; kitchen gets tickets without money, names or payment state. */
    public List<Map<String, Object>> board(StaffPrincipal me, String status, String type, boolean unpaid, String q, UUID onlyId) {
        var outlet = outlets.findById(me.outletId()).orElseThrow();
        var today = LocalDate.now(ZoneId.of(outlet.getTimezone()));
        var sql = new StringBuilder("""
                select o.id, o.order_no, o.business_date, o.order_type, o.source, o.status, o.status_changed_at, o.payment_status, o.notes, o.placed_at,
                       o.table_id, t.label as table_label, o.counter_id,
                       b.id as bill_id, b.status as bill_status, b.subtotal, b.discount_amount, b.service_charge_amount, b.taxable_amount, b.cgst_amount, b.sgst_amount,
                       b.round_off, b.total_amount as total, b.invoice_no, b.bill_to_name, b.bill_to_phone,
                       tl.preparing_at, tl.ready_at, tl.completed_at, tl.cancelled_at, tl.cancel_reason,
                       oo.external_order_id, oo.prep_minutes, oo.accept_by
                  from pos.orders o
                  left join pos.dining_tables t on t.id = o.table_id
                  left join lateral (select * from pos.bills x where x.order_id = o.id order by (x.status = 'void'), x.created_at desc limit 1) b on true
                  left join pos.v_order_timeline tl on tl.order_id = o.id
                  left join pos.online_orders oo on oo.order_id = o.id
                 where o.outlet_id = ?""");
        var args = new ArrayList<Object>(List.of(me.outletId()));
        if (onlyId != null) {
            sql.append(" and o.id = ?");
            args.add(onlyId);
        } else if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            sql.append(" and (o.order_no::text = ?");
            args.add(q.trim().replaceFirst("^#", ""));
            if (!me.kitchen()) {
                sql.append(" or b.bill_to_phone ilike ? or b.bill_to_name ilike ? or oo.external_order_id ilike ?");
                args.addAll(List.of(like, like, like));
            }
            sql.append(")");
        } else {
            sql.append(" and (o.status not in ('completed','cancelled') or o.business_date = ? or (o.payment_status = 'unpaid' and o.status <> 'cancelled'))");
            args.add(today);
        }
        if (status != null && !status.isBlank()) {
            if ("open".equals(status)) sql.append(" and o.status not in ('completed','cancelled')");
            else { sql.append(" and o.status = ?"); args.add(status); }
        }
        if (type != null && !type.isBlank()) { sql.append(" and o.order_type = ?"); args.add(type); }
        if (unpaid) sql.append(" and o.payment_status = 'unpaid' and o.status <> 'cancelled'");
        sql.append(" order by o.placed_at limit 300");

        var rows = db.rows(sql.toString(), args.toArray());
        var items = new HashMap<Object, List<Map<String, Object>>>();
        if (!rows.isEmpty()) {
            var ids = rows.stream().map(r -> r.get("id")).toList();
            var in = ids.stream().map(i -> "?").collect(Collectors.joining(","));
            db.rows("select order_id, item_name as name, is_veg as veg, quantity, unit_price, line_total, notes from pos.order_items where order_id in (" + in + ") order by created_at, item_name",
                    ids.toArray()).forEach(i -> {
                var order = i.remove("orderId");
                if (me.kitchen()) { i.remove("unitPrice"); i.remove("lineTotal"); }
                items.computeIfAbsent(order, k -> new ArrayList<>()).add(i);
            });
        }
        rows.forEach(r -> {
            r.put("items", items.getOrDefault(r.get("id"), List.of()));
            if (me.kitchen()) MONEY.forEach(r::remove);
        });
        return rows;
    }

    public static String normalisePhone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (d.length() == 12 && d.startsWith("91")) d = d.substring(2);
        if (d.length() != 10) throw ApiException.badRequest("Phone number should have 10 digits");
        return "+91" + d;
    }
}
