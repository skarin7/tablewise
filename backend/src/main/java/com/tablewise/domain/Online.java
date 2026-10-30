package com.tablewise.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Swiggy / Zomato / website inbox. */
public final class Online {
    private Online() {}

    @Entity @Table(name = "platform_integrations") @Getter @Setter
    public static class Integration {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        String platform;
        @Column(name = "external_store_id") String externalStoreId;
        @Column(name = "is_accepting") boolean accepting = true;
        @Column(name = "accept_window_seconds") Short acceptWindowSeconds = 180;
        @Column(name = "credentials_secret_ref") String credentialsSecretRef;
    }

    @Entity @Table(name = "platform_item_mappings") @Getter @Setter
    public static class ItemMapping {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "integration_id") UUID integrationId;
        @Column(name = "external_item_id") String externalItemId;
        @Column(name = "menu_item_id") UUID menuItemId;
    }

    @Entity @Table(name = "online_orders") @Getter @Setter
    public static class OnlineOrder {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        @Column(name = "integration_id") UUID integrationId;
        @Column(name = "external_order_id") String externalOrderId;
        String status = "incoming";
        @Column(name = "received_at") Instant receivedAt = Instant.now();
        @Column(name = "accept_by") Instant acceptBy;
        @Column(name = "prep_minutes") Short prepMinutes;
        @Column(name = "decided_by") UUID decidedBy;
        @Column(name = "decided_at") Instant decidedAt;
        @Column(name = "reject_reason") String rejectReason;
        @Column(name = "order_id") UUID orderId;
        @JdbcTypeCode(SqlTypes.JSON) Map<String, Object> payload;
    }
}
