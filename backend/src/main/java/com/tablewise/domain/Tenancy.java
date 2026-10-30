package com.tablewise.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Tenancy, staff, floor, devices. Foreign keys are plain UUID columns; services load related rows explicitly. */
public final class Tenancy {
    private Tenancy() {}

    @Entity @Table(name = "organizations") @Getter @Setter
    public static class Organization {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        String name;
    }

    @Entity @Table(name = "outlets") @Getter @Setter
    public static class Outlet {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "organization_id") UUID organizationId;
        String name, address, phone, gstin;
        @Column(name = "fssai_license_no") String fssaiLicenseNo;
        String timezone = "Asia/Kolkata";
        @JdbcTypeCode(SqlTypes.CHAR) @Column(columnDefinition = "char(3)") String currency = "INR";
        @Column(name = "gst_percent") BigDecimal gstPercent = new BigDecimal("5.00");
        @Column(name = "service_charge_percent") BigDecimal serviceChargePercent = BigDecimal.ZERO;
        @Column(name = "next_order_no", insertable = false, updatable = false) Integer nextOrderNo; // bumped only via SQL
        @Column(name = "is_active") boolean active = true;
    }

    @Entity @Table(name = "staff_members") @Getter @Setter
    public static class Staff {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        @Column(name = "user_id") UUID userId;
        @Column(name = "full_name") String fullName;
        String phone, email, role;
        @Column(name = "is_active") boolean active = true;
        @Column(name = "invited_at") Instant invitedAt;
        @Column(name = "joined_at") Instant joinedAt;
        @Column(name = "last_active_at") Instant lastActiveAt;
    }

    @Entity @Table(name = "counters") @Getter @Setter
    public static class Counter {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        String name;
        @Column(name = "sort_order") int sortOrder;
        @Column(name = "is_active") boolean active = true;
    }

    @Entity @Table(name = "dining_tables") @Getter @Setter
    public static class DiningTable {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        String label;
        Short seats = 4;
        String area;
        @Column(name = "sort_order") int sortOrder;
        @Column(name = "is_active") boolean active = true;
    }

    @Entity @Table(name = "devices") @Getter @Setter
    public static class Device {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        @Column(name = "counter_id") UUID counterId;
        @Column(name = "device_type") String deviceType = "customer_display";
        String name;
        @Column(name = "token_hash") byte[] tokenHash;
        @Column(name = "paired_by") UUID pairedBy;
        @Column(name = "paired_at") Instant pairedAt = Instant.now();
        @Column(name = "last_seen_at") Instant lastSeenAt;
        @Column(name = "revoked_at") Instant revokedAt;
        @Column(name = "user_agent") String userAgent;
    }

    @Entity @Table(name = "device_pairing_codes") @Getter @Setter
    public static class PairingCode {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        String code;
        @Column(name = "device_key") UUID deviceKey;
        @Column(name = "expires_at") Instant expiresAt;
        @Column(name = "claimed_at") Instant claimedAt;
        @Column(name = "device_id") UUID deviceId;
    }

    @Entity @Table(name = "invoice_sequences") @IdClass(InvoiceSequence.Pk.class) @Getter @Setter
    public static class InvoiceSequence {
        @Id @Column(name = "outlet_id") UUID outletId;
        @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "financial_year", columnDefinition = "char(7)") String financialYear;
        String prefix;
        @Column(name = "next_number") int nextNumber = 1;

        @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
        public static class Pk implements Serializable {
            UUID outletId;
            String financialYear;
        }
    }

    @Entity @Table(name = "audit_log") @Getter @Setter
    public static class AuditLog {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
        @Column(name = "outlet_id") UUID outletId;
        @Column(name = "actor_staff_id") UUID actorStaffId;
        String action;
        @Column(name = "entity_type") String entityType;
        @Column(name = "entity_id") UUID entityId;
        @JdbcTypeCode(SqlTypes.JSON) Map<String, Object> changes;
        @Column(name = "created_at", insertable = false, updatable = false) Instant createdAt;
    }
}
