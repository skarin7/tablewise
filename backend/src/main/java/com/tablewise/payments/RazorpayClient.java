package com.tablewise.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The slice of the Razorpay API the POS uses (README 7.4). Behind an interface so tests and a future gateway can swap in. */
public interface RazorpayClient {
    record Qr(String id, String upiPayload, Instant closeBy) {}

    record Captured(String paymentId, String reference) {}

    Qr createQr(UUID orderId, int orderNo, BigDecimal amount, Instant closeBy);

    void closeQr(String qrId);

    /** Captured payments on a QR, used to poll when a webhook is late. */
    List<Captured> capturedPayments(String qrId);

    boolean configured();
}
