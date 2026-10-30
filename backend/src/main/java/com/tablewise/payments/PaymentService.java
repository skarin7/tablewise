package com.tablewise.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tablewise.common.ApiException;
import com.tablewise.common.Db;
import com.tablewise.domain.Repos.*;
import com.tablewise.domain.Sales.*;
import com.tablewise.orders.NumberingService;
import com.tablewise.orders.OrderService;
import com.tablewise.realtime.Realtime;
import com.tablewise.security.Principals.StaffPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Collecting payments (README 7.4): one pending attempt per bill, webhooks, expiry, reconciliation, invoice on paid. */
@Service
public class PaymentService implements OrderService.PaymentHooks {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Duration QR_LIFETIME = Duration.ofMinutes(5);

    public record Start(String method, UUID counterId, BigDecimal tenderedAmount, String approvalCode) {}

    private final OrderRepo orders;
    private final BillRepo bills;
    private final PaymentRepo payments;
    private final CounterRepo counters;
    private final OutletRepo outlets;
    private final NumberingService numbering;
    private final RazorpayClient razorpay;
    private final Realtime realtime;
    private final JdbcTemplate jdbc;
    private final Db db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final String webhookSecret;

    PaymentService(OrderRepo orders, BillRepo bills, PaymentRepo payments, CounterRepo counters, OutletRepo outlets, NumberingService numbering, RazorpayClient razorpay,
                   Realtime realtime, JdbcTemplate jdbc, Db db, ObjectMapper json, TransactionTemplate tx, @Value("${tablewise.razorpay.webhook-secret}") String webhookSecret) {
        this.orders = orders;
        this.bills = bills;
        this.payments = payments;
        this.counters = counters;
        this.outlets = outlets;
        this.numbering = numbering;
        this.razorpay = razorpay;
        this.realtime = realtime;
        this.jdbc = jdbc;
        this.db = db;
        this.json = json;
        this.tx = tx;
        this.webhookSecret = webhookSecret;
    }

    // ------------------------------------------------------------ collecting

    @Transactional
    public Map<String, Object> start(UUID orderId, StaffPrincipal me, Start req) {
        var order = orders.lockByIdAndOutletId(orderId, me.outletId()).orElseThrow(() -> ApiException.notFound("Order not found"));
        if ("cancelled".equals(order.getStatus())) throw ApiException.conflict("This order was cancelled.");
        if ("paid".equals(order.getPaymentStatus())) throw ApiException.conflict("This order is already paid.");
        var bill = bills.findByOrderIdAndStatusNot(orderId, "void").orElseThrow(() -> ApiException.conflict("This order has no open bill."));
        if (req.counterId() != null) counters.findByIdAndOutletId(req.counterId(), me.outletId()).orElseThrow(() -> ApiException.badRequest("Unknown counter"));
        payments.findByBillIdAndStatus(bill.getId(), "pending").ifPresent(old -> closeAttempt(old, "cancelled", "Replaced by another payment method"));
        payments.flush(); // the partial unique index allows one pending row per bill

        BigDecimal due = bill.getTotalAmount().subtract(paidSoFar(bill.getId()));
        if (due.signum() <= 0) { // nothing to collect, e.g. a 100 % discount
            settleBill(order, bill, req.counterId());
            return Map.of("billStatus", "paid");
        }
        var p = new Payment();
        p.setOutletId(me.outletId());
        p.setBillId(bill.getId());
        p.setAmount(due);
        p.setCounterId(req.counterId());
        p.setCollectedBy(me.staffId());
        switch (req.method() == null ? "" : req.method()) {
            case "cash" -> {
                if (req.tenderedAmount() == null || req.tenderedAmount().compareTo(due) < 0) throw ApiException.badRequest("The cash handed over is less than the bill");
                p.setMethod("cash");
                p.setTenderedAmount(req.tenderedAmount());
                p.setChangeAmount(req.tenderedAmount().subtract(due));
                succeed(p, order, bill, null, null);
            }
            case "card" -> {
                if (req.approvalCode() == null || req.approvalCode().isBlank()) throw ApiException.badRequest("Enter the card approval code");
                p.setMethod("card");
                p.setGateway("card_terminal");
                succeed(p, order, bill, null, req.approvalCode().trim());
            }
            case "upi_qr" -> {
                p.setMethod("upi_qr");
                p.setGateway("razorpay");
                payments.saveAndFlush(p); // pending row first, so a crash never leaves a live QR without a record
                var qr = razorpay.createQr(order.getId(), order.getOrderNo(), due, Instant.now().plus(QR_LIFETIME));
                p.setGatewayQrId(qr.id());
                p.setQrPayload(qr.upiPayload());
                p.setExpiresAt(qr.closeBy());
                payments.save(p);
                realtime.display(p.getCounterId(), billState(order, bill, p));
            }
            default -> throw ApiException.badRequest("Choose cash, upi_qr or card");
        }
        log.info("payment started: order #{} {} amount {} -> {}", order.getOrderNo(), req.method(), due, p.getStatus());
        return Map.of("paymentId", p.getId(), "status", p.getStatus(), "billStatus", bill.getStatus());
    }

    @Transactional
    public void cancel(UUID paymentId, StaffPrincipal me) {
        var p = payments.findByIdAndOutletId(paymentId, me.outletId()).orElseThrow(() -> ApiException.notFound("Payment not found"));
        if (!"pending".equals(p.getStatus())) throw ApiException.conflict("Only a pending payment can be cancelled.");
        closeAttempt(p, "cancelled", "Cancelled at the counter");
    }

    public Map<String, Object> forOrder(UUID orderId, UUID counterId, StaffPrincipal me) {
        orders.findByIdAndOutletId(orderId, me.outletId()).orElseThrow(() -> ApiException.notFound("Order not found"));
        var bill = bills.findByOrderIdAndStatusNot(orderId, "void").orElse(null);
        boolean online = counterId != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from pos.devices where counter_id = ? and outlet_id = ? and revoked_at is null and last_seen_at > now() - interval '15 seconds')", Boolean.class, counterId, me.outletId()));
        var m = new LinkedHashMap<String, Object>();
        m.put("billStatus", bill == null ? null : bill.getStatus());
        m.put("total", bill == null ? null : bill.getTotalAmount());
        m.put("invoiceNo", bill == null ? null : bill.getInvoiceNo());
        m.put("displayOnline", online);
        m.put("payments", bill == null ? List.of() : db.rows("select id, method, status, amount, tendered_amount, change_amount, qr_payload, expires_at, gateway_reference, failure_reason, created_at from pos.payments where bill_id = ? order by created_at", bill.getId()));
        return m;
    }

    /** Platform orders arrive paid: record the platform's payment so the bill is finalised and invoiced. */
    @Transactional
    public void recordPlatformPayment(PosOrder order, Bill bill) {
        var p = new Payment();
        p.setOutletId(order.getOutletId());
        p.setBillId(bill.getId());
        p.setMethod("platform");
        p.setGateway("platform");
        p.setAmount(bill.getTotalAmount());
        succeed(p, order, bill, null, null);
    }

    @Override
    @Transactional
    public void closePending(UUID billId) {
        payments.findByBillIdAndStatus(billId, "pending").ifPresent(p -> closeAttempt(p, "cancelled", "Order cancelled"));
    }

    // -------------------------------------------------------------- outcomes

    private void succeed(Payment p, PosOrder order, Bill bill, String gatewayPaymentId, String reference) {
        p.setStatus("succeeded");
        p.setGatewayPaymentId(gatewayPaymentId);
        p.setGatewayReference(reference);
        p.setCompletedAt(Instant.now());
        payments.saveAndFlush(p);
        log.info("payment succeeded: {} {} for order #{}", p.getMethod(), p.getAmount(), order.getOrderNo());
        if (paidSoFar(bill.getId()).compareTo(bill.getTotalAmount()) >= 0) settleBill(order, bill, p.getCounterId());
    }

    /** Bill paid: invoice number and finalised_at (required by the DB), order marked paid, POS and display told. */
    private void settleBill(PosOrder order, Bill bill, UUID counterId) {
        var outlet = outlets.findById(order.getOutletId()).orElseThrow();
        bill.setInvoiceNo(numbering.nextInvoiceNo(outlet.getId(), outlet.getName(), order.getBusinessDate()));
        bill.setFinalisedAt(Instant.now());
        bill.setStatus("paid");
        bills.saveAndFlush(bill);
        order.setPaymentStatus("paid");
        orders.saveAndFlush(order);
        log.info("bill paid: order #{} invoice {} total {}", order.getOrderNo(), bill.getInvoiceNo(), bill.getTotalAmount());
        realtime.orders(order.getOutletId(), "paid", order.getId(), order.getOrderNo());
        realtime.display(counterId, Map.of("state", "paid", "orderNo", order.getOrderNo(), "total", bill.getTotalAmount(), "invoiceNo", bill.getInvoiceNo()));
    }

    /** Closes the QR through the API (best effort) and marks the row cancelled or expired. */
    private void closeAttempt(Payment p, String status, String reason) {
        if (p.getGatewayQrId() != null) {
            try { razorpay.closeQr(p.getGatewayQrId()); } catch (RuntimeException e) { log.warn("could not close QR {}: {}", p.getGatewayQrId(), e.getMessage()); }
        }
        log.info("payment {}: {} {} ({})", status, p.getMethod(), p.getId(), reason);
        p.setStatus(status);
        p.setFailureReason(reason);
        p.setCompletedAt(Instant.now());
        payments.saveAndFlush(p);
        realtime.display(p.getCounterId(), "expired".equals(status) ? Map.of("state", "failed", "reason", "The QR code expired") : Map.of("state", "idle"));
    }

    private BigDecimal paidSoFar(UUID billId) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from pos.payments where bill_id = ? and status = 'succeeded'", BigDecimal.class, billId);
    }

    private Map<String, Object> billState(PosOrder order, Bill bill, Payment p) {
        var m = new LinkedHashMap<String, Object>();
        m.put("state", "bill");
        m.put("orderNo", order.getOrderNo());
        m.put("items", db.rows("select item_name as name, quantity, line_total from pos.order_items where order_id = ? order by created_at, item_name", order.getId()));
        m.put("subtotal", bill.getSubtotal());
        m.put("discount", bill.getDiscountAmount());
        m.put("serviceCharge", bill.getServiceChargeAmount());
        m.put("gst", bill.getCgstAmount().add(bill.getSgstAmount()));
        m.put("roundOff", bill.getRoundOff());
        m.put("total", bill.getTotalAmount());
        m.put("qr", p.getQrPayload());
        m.put("expiresAt", p.getExpiresAt());
        return m;
    }

    // ---------------------------------------------------------------- webhook

    /** Verifies the signature, stores the event once (duplicate ids are no-ops) and settles the matching payment. */
    @Transactional
    public void razorpayWebhook(String rawBody, String signature, String eventId) {
        if (webhookSecret.isBlank() || signature == null || !MessageDigest.isEqual(hmacHex(rawBody).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            log.warn("razorpay webhook refused: bad or missing signature (event {})", eventId);
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Bad signature");
        }
        if (eventId == null || eventId.isBlank()) throw ApiException.badRequest("Missing event id");
        JsonNode root;
        try { root = json.readTree(rawBody); } catch (Exception e) { throw ApiException.badRequest("Bad payload"); }
        String type = root.path("event").asText("unknown");
        Long row = jdbc.query("""
                insert into pos.gateway_webhook_events(provider, event_id, event_type, payload, signature_valid) values ('razorpay', ?, ?, ?::jsonb, true)
                on conflict (provider, event_id) do nothing returning id""", rs -> rs.next() ? rs.getLong(1) : null, eventId, type, rawBody);
        if (row == null) { // a retry of something already handled
            log.info("razorpay webhook {} ({}) already handled, ignored", eventId, type);
            return;
        }

        String error = null;
        if (type.equals("qr_code.credited") || type.equals("payment.captured")) {
            JsonNode pay = root.path("payload").path("payment").path("entity");
            String qrId = root.path("payload").path("qr_code").path("entity").path("id").asText(null);
            String payId = pay.path("id").asText(null);
            var found = qrId != null ? payments.findByGatewayQrId(qrId) : Optional.<Payment>empty();
            if (found.isEmpty() && payId != null) found = payments.findByGatewayPaymentId(payId);
            if (found.isPresent()) {
                String rrn = pay.path("acquirer_data").path("rrn").asText(pay.path("acquirer_data").path("upi_transaction_id").asText(null));
                settleFromGateway(found.get(), payId, rrn);
            } else {
                error = "no matching payment";
            }
        }
        jdbc.update("update pos.gateway_webhook_events set processed_at = now(), error = ? where id = ?", error, row);
        if (error != null) log.warn("razorpay webhook {} ({}) stored but not applied: {}", eventId, type, error);
        else log.info("razorpay webhook {} ({}) processed", eventId, type);
    }

    /** Pending (or just expired) attempt that the gateway says was paid. */
    @Transactional
    public void settleFromGateway(Payment p, String gatewayPaymentId, String reference) {
        if ("succeeded".equals(p.getStatus())) return;
        var bill = bills.findById(p.getBillId()).orElseThrow();
        if ("void".equals(bill.getStatus())) {
            log.warn("payment {} received for a void bill {}; refund needed", gatewayPaymentId, bill.getId());
            return;
        }
        var order = orders.lockByIdAndOutletId(bill.getOrderId(), bill.getOutletId()).orElseThrow();
        log.info("payment {} confirmed by the gateway ({})", p.getId(), gatewayPaymentId);
        succeed(p, order, bill, gatewayPaymentId, reference);
    }

    private String hmacHex(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --------------------------------------------------------------- scheduler

    /** Every few seconds: expire stale QR rows, and ask Razorpay about live ones in case a webhook was lost. */
    public void sweep() {
        for (var p : payments.findByStatusAndMethod("pending", "upi_qr")) {
            try {
                tx.executeWithoutResult(s -> {
                    var fresh = payments.findById(p.getId()).orElseThrow();
                    if (!"pending".equals(fresh.getStatus())) return;
                    if (fresh.getExpiresAt() != null && fresh.getExpiresAt().isBefore(Instant.now())) {
                        closeAttempt(fresh, "expired", "QR expired");
                    } else if (razorpay.configured() && fresh.getGatewayQrId() != null) {
                        razorpay.capturedPayments(fresh.getGatewayQrId()).stream().findFirst().ifPresent(c -> settleFromGateway(fresh, c.paymentId(), c.reference()));
                    }
                });
            } catch (RuntimeException e) {
                log.warn("payment sweep failed for {}", p.getId(), e);
            }
        }
    }
}
