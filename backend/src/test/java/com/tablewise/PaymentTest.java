package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tablewise.payments.PaymentService;
import com.tablewise.payments.RazorpayClient.Captured;
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

/** M6: cash, card, UPI QR, webhook idempotency, expiry and reconciliation, invoice numbering (README 7.4, 7.7). */
class PaymentTest extends IntegrationTest {
    @Autowired PaymentService payments;

    private String sign(String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String credited(String qrId, String payId) {
        return "{\"event\":\"qr_code.credited\",\"payload\":{\"qr_code\":{\"entity\":{\"id\":\"" + qrId + "\"}},\"payment\":{\"entity\":{\"id\":\"" + payId
                + "\",\"status\":\"captured\",\"acquirer_data\":{\"rrn\":\"RRN123\"}}}}}";
    }

    private org.springframework.test.web.servlet.ResultActions hook(String body, String eventId, String sig) throws Exception {
        return mvc.perform(post("/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body).header("X-Razorpay-Event-Id", eventId).header("X-Razorpay-Signature", sig));
    }

    private UUID upi(Outlet o, UUID order) throws Exception {
        return UUID.fromString(json(call(post("/orders/" + order + "/payments"), as(o.id(), "cashier"), Map.of("method", "upi_qr", "counterId", o.counter())).andExpect(status().isCreated())).get("paymentId").asText());
    }

    @Test
    void cashPaymentNeedsEnoughMoneyThenPaysTheBillAndGivesAnInvoice() throws Exception {
        var o = newOutlet();
        UUID dish = dish(o, "Dal", "190");               // 190 + 5 % = 199.50 -> 200
        UUID order = placeOrder(o, dish, 1);
        var cashier = as(o.id(), "cashier");
        call(post("/orders/" + order + "/payments"), cashier, Map.of("method", "cash", "tenderedAmount", 150)).andExpect(status().isBadRequest());
        call(post("/orders/" + order + "/payments"), cashier, Map.of("method", "cash", "tenderedAmount", 500)).andExpect(status().isCreated());

        var pay = jdbc.queryForMap("select status, amount, tendered_amount, change_amount from pos.payments where bill_id = (select id from pos.bills where order_id = ?)", order);
        assertThat(pay).containsEntry("status", "succeeded");
        assertThat((java.math.BigDecimal) pay.get("change_amount")).isEqualByComparingTo("300");
        var bill = jdbc.queryForMap("select status, invoice_no, finalised_at from pos.bills where order_id = ?", order);
        assertThat(bill.get("status")).isEqualTo("paid");
        assertThat((String) bill.get("invoice_no")).matches("SAF/\\d\\d-\\d\\d/000001").hasSizeLessThanOrEqualTo(16);
        assertThat(bill.get("finalised_at")).isNotNull();
        assertThat(jdbc.queryForObject("select payment_status from pos.orders where id = ?", String.class, order)).isEqualTo("paid");

        call(post("/orders/" + order + "/payments"), cashier, Map.of("method", "cash", "tenderedAmount", 500)).andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("already paid")));
        call(post("/orders/" + order + "/payments"), as(o.id(), "kitchen"), Map.of("method", "cash", "tenderedAmount", 500)).andExpect(status().isForbidden());
    }

    @Test
    void invoiceNumbersAreConsecutivePerOutlet() throws Exception {
        var o = newOutlet();
        UUID dish = dish(o, "Dal", "190");
        for (int i = 1; i <= 3; i++) {
            UUID order = placeOrder(o, dish, 1);
            call(post("/orders/" + order + "/payments"), as(o.id(), "cashier"), Map.of("method", "cash", "tenderedAmount", 200)).andExpect(status().isCreated());
        }
        assertThat(jdbc.queryForList("select right(invoice_no, 6) from pos.bills where outlet_id = ? order by invoice_no", String.class, o.id())).containsExactly("000001", "000002", "000003");
    }

    @Test
    void cardNeedsAnApprovalCode() throws Exception {
        var o = newOutlet();
        UUID order = placeOrder(o, dish(o, "Dal", "190"), 1);
        var cashier = as(o.id(), "cashier");
        call(post("/orders/" + order + "/payments"), cashier, Map.of("method", "card")).andExpect(status().isBadRequest());
        call(post("/orders/" + order + "/payments"), cashier, Map.of("method", "card", "approvalCode", "A12345")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select gateway_reference from pos.payments where bill_id = (select id from pos.bills where order_id = ?)", String.class, order)).isEqualTo("A12345");
    }

    @Test
    void onlyOnePendingAttemptAndSwitchingMethodClosesTheQr() throws Exception {
        var o = newOutlet();
        UUID order = placeOrder(o, dish(o, "Dal", "190"), 1);
        UUID first = upi(o, order);
        String qr1 = jdbc.queryForObject("select gateway_qr_id from pos.payments where id = ?", String.class, first);
        assertThat(jdbc.queryForObject("select qr_payload from pos.payments where id = ?", String.class, first)).startsWith("upi://pay");
        assertThat(jdbc.queryForObject("select expires_at > now() from pos.payments where id = ?", Boolean.class, first)).isTrue();

        UUID second = upi(o, order);                                   // a new QR cancels the old one first
        assertThat(razorpay.closed).contains(qr1);
        assertThat(jdbc.queryForObject("select status from pos.payments where id = ?", String.class, first)).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("select count(*) from pos.payments where bill_id = (select id from pos.bills where order_id = ?) and status = 'pending'", Integer.class, order)).isEqualTo(1);

        call(post("/orders/" + order + "/payments"), as(o.id(), "cashier"), Map.of("method", "cash", "tenderedAmount", 200)).andExpect(status().isCreated()); // switch to cash
        assertThat(jdbc.queryForObject("select status from pos.payments where id = ?", String.class, second)).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("select status from pos.bills where order_id = ?", String.class, order)).isEqualTo("paid");
    }

    @Test
    void cancellingAPendingPaymentOrTheOrderClosesTheQr() throws Exception {
        var o = newOutlet();
        UUID order = placeOrder(o, dish(o, "Dal", "190"), 1);
        UUID p = upi(o, order);
        call(post("/payments/" + p + "/cancel"), as(o.id(), "cashier"), null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from pos.payments where id = ?", String.class, p)).isEqualTo("cancelled");
        call(post("/payments/" + p + "/cancel"), as(o.id(), "cashier"), null).andExpect(status().isConflict());

        UUID p2 = upi(o, order);
        call(post("/orders/" + order + "/cancel"), as(o.id(), "cashier"), Map.of("reason", "Left")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from pos.payments where id = ?", String.class, p2)).isEqualTo("cancelled");
        call(post("/orders/" + order + "/payments"), as(o.id(), "cashier"), Map.of("method", "cash", "tenderedAmount", 500)).andExpect(status().isConflict()); // cancelled order
    }

    @Test
    void webhookSettlesThePaymentOnceAndRetriesAreNoOps() throws Exception {
        var o = newOutlet();
        UUID order = placeOrder(o, dish(o, "Dal", "190"), 1);
        UUID p = upi(o, order);
        String qr = jdbc.queryForObject("select gateway_qr_id from pos.payments where id = ?", String.class, p);
        String body = credited(qr, "pay_" + qr);

        hook(body, "evt_" + qr, "deadbeef").andExpect(status().isUnauthorized());                          // bad signature
        assertThat(jdbc.queryForObject("select count(*) from pos.gateway_webhook_events where event_id = ?", Integer.class, "evt_" + qr)).isZero();

        hook(body, "evt_" + qr, sign(body)).andExpect(status().isOk());
        var pay = jdbc.queryForMap("select status, gateway_payment_id, gateway_reference from pos.payments where id = ?", p);
        assertThat(pay).containsEntry("status", "succeeded").containsEntry("gateway_payment_id", "pay_" + qr).containsEntry("gateway_reference", "RRN123");
        String invoice = jdbc.queryForObject("select invoice_no from pos.bills where order_id = ?", String.class, order);
        assertThat(invoice).isNotNull();
        assertThat(jdbc.queryForObject("select payment_status from pos.orders where id = ?", String.class, order)).isEqualTo("paid");

        hook(body, "evt_" + qr, sign(body)).andExpect(status().isOk());                                    // Razorpay retry
        assertThat(jdbc.queryForObject("select count(*) from pos.gateway_webhook_events where event_id = ?", Integer.class, "evt_" + qr)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select invoice_no from pos.bills where order_id = ?", String.class, order)).isEqualTo(invoice);
        assertThat(jdbc.queryForObject("select count(*) from pos.payments where status = 'succeeded' and bill_id = (select id from pos.bills where order_id = ?)", Integer.class, order)).isEqualTo(1);

        String other = credited("qr_unknown", "pay_x");                                                    // unknown QR is kept, with a note
        hook(other, "evt_unknown_" + qr, sign(other)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select error from pos.gateway_webhook_events where event_id = ?", String.class, "evt_unknown_" + qr)).isEqualTo("no matching payment");
    }

    @Test
    void schedulerExpiresStaleQrAndPollsRazorpayWhenAWebhookIsLate() throws Exception {
        var o = newOutlet();
        UUID stale = placeOrder(o, dish(o, "Dal", "190"), 1);
        UUID p1 = upi(o, stale);
        jdbc.update("update pos.payments set expires_at = now() - interval '1 minute' where id = ?", p1);

        UUID late = placeOrder(o, dish(o, "Chai", "40"), 1);
        UUID p2 = upi(o, late);
        razorpay.captured.put(jdbc.queryForObject("select gateway_qr_id from pos.payments where id = ?", String.class, p2), List.of(new Captured("pay_polled", "UTR999")));

        payments.sweep();
        assertThat(jdbc.queryForObject("select status from pos.payments where id = ?", String.class, p1)).isEqualTo("expired");
        assertThat(jdbc.queryForObject("select status from pos.payments where id = ?", String.class, p2)).isEqualTo("succeeded");
        assertThat(jdbc.queryForObject("select status from pos.bills where order_id = ?", String.class, late)).isEqualTo("paid");
        // an expired bill can start a fresh QR
        upi(o, stale);
    }

    @Test
    void displayChannelGetsTheBillThenPaid() throws Exception {
        var o = newOutlet();
        UUID order = placeOrder(o, dish(o, "Dal", "190"), 1);
        var watcher = connect(o.id(), "cashier", "/topic/counters/" + o.counter() + "/display");
        UUID p = upi(o, order);
        var bill = watcher.messages().poll(5, TimeUnit.SECONDS);
        assertThat(bill).containsEntry("state", "bill").containsKey("qr").containsKey("expiresAt");
        assertThat(((Number) bill.get("total")).doubleValue()).isEqualTo(200.0);

        String qr = jdbc.queryForObject("select gateway_qr_id from pos.payments where id = ?", String.class, p);
        String body = credited(qr, "pay_1");
        hook(body, "evt_disp_" + qr, sign(body)).andExpect(status().isOk());
        assertThat(watcher.messages().poll(5, TimeUnit.SECONDS)).containsEntry("state", "paid");
        watcher.session().disconnect();
    }
}
