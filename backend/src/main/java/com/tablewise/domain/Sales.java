package com.tablewise.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Customers, orders, bills, payments, webhooks. */
public final class Sales {
    private Sales() {}

    @Entity @Table(name = "customers") @Getter @Setter
    public static class Customer {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        String name, phone, email, notes;
        @Column(name = "marketing_opt_in") boolean marketingOptIn;
    }

    @Entity(name = "PosOrder") @Table(name = "orders") @Getter @Setter
    public static class PosOrder {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        @Column(name = "order_no") int orderNo;
        @Column(name = "business_date") LocalDate businessDate;
        @Column(name = "order_type") String orderType;
        String source = "pos";
        String status = "new";
        @Column(name = "status_changed_at") Instant statusChangedAt = Instant.now();
        @Column(name = "payment_status") String paymentStatus = "unpaid";
        @Column(name = "table_id") UUID tableId;
        @Column(name = "counter_id") UUID counterId;
        @Column(name = "customer_id") UUID customerId;
        String notes;
        @Column(name = "placed_at") Instant placedAt = Instant.now();
        @Column(name = "created_by") UUID createdBy;
    }

    @Entity @Table(name = "order_items") @Getter @Setter
    public static class OrderItem {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "order_id") UUID orderId;
        @Column(name = "menu_item_id") UUID menuItemId;
        @Column(name = "item_name") String itemName;
        @Column(name = "is_veg") boolean veg;
        @Column(name = "unit_price") BigDecimal unitPrice;
        int quantity;
        @Column(name = "line_total") BigDecimal lineTotal;
        String notes;
    }

    @Entity @Table(name = "order_status_events") @Getter @Setter
    public static class OrderStatusEvent {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
        @Column(name = "order_id") UUID orderId;
        @Column(name = "from_status") String fromStatus;
        @Column(name = "to_status") String toStatus;
        @Column(name = "actor_staff_id") UUID actorStaffId;
        @Column(name = "actor_device_id") UUID actorDeviceId;
        String note;
        @Column(name = "created_at", insertable = false, updatable = false) Instant createdAt;
    }

    @Entity @Table(name = "bills") @Getter @Setter
    public static class Bill {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        @Column(name = "order_id") UUID orderId;
        String status = "open";
        @Column(name = "bill_to_name") String billToName;
        @Column(name = "bill_to_phone") String billToPhone;
        BigDecimal subtotal;
        @Column(name = "discount_type") String discountType;
        @Column(name = "discount_value") BigDecimal discountValue = BigDecimal.ZERO;
        @Column(name = "discount_amount") BigDecimal discountAmount = BigDecimal.ZERO;
        @Column(name = "service_charge_percent") BigDecimal serviceChargePercent;
        @Column(name = "service_charge_amount") BigDecimal serviceChargeAmount;
        @Column(name = "taxable_amount") BigDecimal taxableAmount;
        @Column(name = "gst_percent") BigDecimal gstPercent;
        @Column(name = "cgst_amount") BigDecimal cgstAmount;
        @Column(name = "sgst_amount") BigDecimal sgstAmount;
        @Column(name = "round_off") BigDecimal roundOff;
        @Column(name = "total_amount") BigDecimal totalAmount;
        @Column(name = "invoice_no") String invoiceNo;
        @Column(name = "finalised_at") Instant finalisedAt;
    }

    @Entity @Table(name = "payments") @Getter @Setter
    public static class Payment {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        @Column(name = "bill_id") UUID billId;
        String method;
        String status = "pending";
        BigDecimal amount;
        @Column(name = "tendered_amount") BigDecimal tenderedAmount;
        @Column(name = "change_amount") BigDecimal changeAmount;
        String gateway;
        @Column(name = "gateway_qr_id") String gatewayQrId;
        @Column(name = "qr_payload") String qrPayload;
        @Column(name = "expires_at") Instant expiresAt;
        @Column(name = "gateway_payment_id") String gatewayPaymentId;
        @Column(name = "gateway_reference") String gatewayReference;
        @Column(name = "failure_reason") String failureReason;
        @Column(name = "counter_id") UUID counterId;
        @Column(name = "collected_by") UUID collectedBy;
        @Column(name = "completed_at") Instant completedAt;
    }

    @Entity @Table(name = "gateway_webhook_events") @Getter @Setter
    public static class WebhookEvent {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
        String provider;
        @Column(name = "event_id") String eventId;
        @Column(name = "event_type") String eventType;
        @JdbcTypeCode(SqlTypes.JSON) Map<String, Object> payload;
        @Column(name = "signature_valid") boolean signatureValid;
        @Column(name = "processed_at") Instant processedAt;
        String error;
    }
}
