package com.tablewise.admin;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.tablewise.common.ApiException;
import com.tablewise.common.Db;
import com.tablewise.domain.Repos.*;
import com.tablewise.domain.Tenancy.*;
import com.tablewise.security.Principals.StaffPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Admin screen: restaurant and billing settings, staff, counters, tables, audit log. Owner only except reading the outlet. */
@RestController
@Transactional
class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    record OutletForm(@NotBlank String name, String address, String phone, @Pattern(regexp = "[0-9A-Z]{15}", message = "must be a 15-character GSTIN") String gstin,
                      @Pattern(regexp = "[0-9]{14}", message = "must be 14 digits") String fssaiLicenseNo,
                      @NotNull @DecimalMin("0") @DecimalMax("28") BigDecimal gstPercent, @NotNull @DecimalMin("0") @DecimalMax("20") BigDecimal serviceChargePercent) {}
    record NewStaff(@NotBlank String fullName, @NotBlank @Email String email, String phone, @NotNull @Pattern(regexp = "owner|cashier|kitchen") String role) {}
    record StaffForm(@NotBlank String fullName, String phone, @NotNull @Pattern(regexp = "owner|cashier|kitchen") String role, boolean active) {}
    record CounterForm(@NotBlank String name) {}
    record TableForm(@NotBlank @Size(max = 10) String label, @Min(1) Short seats, String area) {}

    private final OutletRepo outlets;
    private final StaffRepo staff;
    private final CounterRepo counters;
    private final TableRepo tables;
    private final AuditRepo audit;
    private final Db db;

    AdminController(OutletRepo outlets, StaffRepo staff, CounterRepo counters, TableRepo tables, AuditRepo audit, Db db) {
        this.outlets = outlets;
        this.staff = staff;
        this.counters = counters;
        this.tables = tables;
        this.audit = audit;
        this.db = db;
    }

    // ---- restaurant and billing

    @GetMapping("/outlet")
    Map<String, Object> outlet(@AuthenticationPrincipal StaffPrincipal me) {
        var o = outlets.findById(me.outletId()).orElseThrow();
        var m = new LinkedHashMap<String, Object>();
        m.put("id", o.getId());
        m.put("name", o.getName());
        m.put("timezone", o.getTimezone());
        m.put("gstPercent", o.getGstPercent());
        m.put("serviceChargePercent", o.getServiceChargePercent());
        if (!me.kitchen()) { // the kitchen needs no business details
            m.put("address", o.getAddress());
            m.put("phone", o.getPhone());
            m.put("gstin", o.getGstin());
            m.put("fssaiLicenseNo", o.getFssaiLicenseNo());
        }
        return m;
    }

    /** New bills use the new rates; existing bills keep the percentages copied onto them. */
    @PutMapping("/outlet") @PreAuthorize("hasRole('OWNER')")
    void updateOutlet(@Valid @RequestBody OutletForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var o = outlets.findById(me.outletId()).orElseThrow();
        var changes = new LinkedHashMap<String, Object>();
        diff(changes, "name", o.getName(), f.name().trim());
        diff(changes, "gstin", o.getGstin(), f.gstin());
        diff(changes, "gstPercent", o.getGstPercent(), f.gstPercent());
        diff(changes, "serviceChargePercent", o.getServiceChargePercent(), f.serviceChargePercent());
        o.setName(f.name().trim());
        o.setAddress(f.address());
        o.setPhone(f.phone());
        o.setGstin(f.gstin());
        o.setFssaiLicenseNo(f.fssaiLicenseNo());
        o.setGstPercent(f.gstPercent());
        o.setServiceChargePercent(f.serviceChargePercent());
        if (!changes.isEmpty()) log(me, "outlet.updated", "outlet", o.getId(), changes);
    }

    // ---- staff

    @GetMapping("/staff") @PreAuthorize("hasRole('OWNER')")
    List<Map<String, Object>> staff(@AuthenticationPrincipal StaffPrincipal me) {
        return db.rows("select id, full_name, email, phone, role, is_active as active, invited_at, joined_at, last_active_at, user_id is not null as signed_in from pos.staff_members where outlet_id = ? order by full_name", me.outletId());
    }

    /** An invite is a staff row with an email; the person signs in with that email and the first sign-in links their account. */
    @PostMapping("/staff") @PreAuthorize("hasRole('OWNER')") @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> invite(@Valid @RequestBody NewStaff f, @AuthenticationPrincipal StaffPrincipal me) {
        var s = new Staff();
        s.setOutletId(me.outletId());
        s.setFullName(f.fullName().trim());
        s.setEmail(f.email().trim().toLowerCase());
        s.setPhone(f.phone());
        s.setRole(f.role());
        s.setInvitedAt(Instant.now());
        staff.saveAndFlush(s);
        log(me, "staff.invited", "staff", s.getId(), Map.of("email", s.getEmail(), "role", s.getRole()));
        return Map.of("id", s.getId());
    }

    @PutMapping("/staff/{id}") @PreAuthorize("hasRole('OWNER')")
    void updateStaff(@PathVariable UUID id, @Valid @RequestBody StaffForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var s = staff.findByIdAndOutletId(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Staff member not found"));
        boolean losesOwner = s.isActive() && "owner".equals(s.getRole()) && (!f.active() || !"owner".equals(f.role()));
        if (losesOwner && activeOwners(me.outletId()) <= 1) throw ApiException.conflict("The restaurant needs at least one active owner");
        var changes = new LinkedHashMap<String, Object>();
        diff(changes, "role", s.getRole(), f.role());
        diff(changes, "active", s.isActive(), f.active());
        s.setFullName(f.fullName().trim());
        s.setPhone(f.phone());
        s.setRole(f.role());
        s.setActive(f.active());
        if (!changes.isEmpty()) log(me, "staff.updated", "staff", id, changes);
    }

    private long activeOwners(UUID outletId) {
        return staff.findByOutletIdOrderByFullName(outletId).stream().filter(x -> x.isActive() && "owner".equals(x.getRole())).count();
    }

    // ---- counters and tables (deactivated, never deleted: old orders and payments point at them)

    @PostMapping("/counters") @PreAuthorize("hasRole('OWNER')") @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> addCounter(@Valid @RequestBody CounterForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var c = new Counter();
        c.setOutletId(me.outletId());
        c.setName(f.name().trim());
        try { counters.saveAndFlush(c); } catch (DataIntegrityViolationException e) { throw ApiException.conflict("A counter named " + f.name() + " already exists"); }
        log(me, "counter.added", "counter", c.getId(), Map.of("name", c.getName()));
        return Map.of("id", c.getId());
    }

    @PutMapping("/counters/{id}") @PreAuthorize("hasRole('OWNER')")
    void renameCounter(@PathVariable UUID id, @Valid @RequestBody CounterForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var c = counters.findByIdAndOutletId(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Counter not found"));
        c.setName(f.name().trim());
        try { counters.flush(); } catch (DataIntegrityViolationException e) { throw ApiException.conflict("A counter named " + f.name() + " already exists"); }
    }

    @DeleteMapping("/counters/{id}") @PreAuthorize("hasRole('OWNER')")
    void removeCounter(@PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal me) {
        var c = counters.findByIdAndOutletId(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Counter not found"));
        c.setActive(false);
        log(me, "counter.removed", "counter", id, Map.of("name", c.getName()));
    }

    @PostMapping("/tables") @PreAuthorize("hasRole('OWNER')") @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> addTable(@Valid @RequestBody TableForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var t = new DiningTable();
        t.setOutletId(me.outletId());
        fill(t, f);
        try { tables.saveAndFlush(t); } catch (DataIntegrityViolationException e) { throw ApiException.conflict("A table labelled " + f.label() + " already exists"); }
        return Map.of("id", t.getId());
    }

    @PutMapping("/tables/{id}") @PreAuthorize("hasRole('OWNER')")
    void updateTable(@PathVariable UUID id, @Valid @RequestBody TableForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var t = tables.findByIdAndOutletId(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Table not found"));
        fill(t, f);
        try { tables.flush(); } catch (DataIntegrityViolationException e) { throw ApiException.conflict("A table labelled " + f.label() + " already exists"); }
    }

    @DeleteMapping("/tables/{id}") @PreAuthorize("hasRole('OWNER')")
    void removeTable(@PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal me) {
        var t = tables.findByIdAndOutletId(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Table not found"));
        if (Boolean.TRUE.equals(db.rows("select is_busy from pos.v_table_occupancy where table_id = ?", id).get(0).get("isBusy"))) throw ApiException.conflict(t.getLabel() + " has an open order");
        t.setActive(false);
    }

    private static void fill(DiningTable t, TableForm f) {
        t.setLabel(f.label().trim());
        if (f.seats() != null) t.setSeats(f.seats());
        t.setArea(f.area());
    }

    // ---- audit log

    @GetMapping("/audit-log") @PreAuthorize("hasRole('OWNER')")
    List<Map<String, Object>> auditLog(@AuthenticationPrincipal StaffPrincipal me) {
        return db.rows("select a.id, a.created_at, a.action, a.entity_type, a.entity_id, a.changes, s.full_name as actor from pos.audit_log a left join pos.staff_members s on s.id = a.actor_staff_id where a.outlet_id = ? order by a.id desc limit 200", me.outletId());
    }

    private static void diff(Map<String, Object> changes, String key, Object from, Object to) {
        boolean same = from instanceof BigDecimal a && to instanceof BigDecimal b ? a.compareTo(b) == 0 : Objects.equals(from, to);
        if (!same) changes.put(key, Map.of("from", from == null ? "" : from, "to", to == null ? "" : to));
    }

    private void log(StaffPrincipal me, String action, String type, UUID id, Map<String, Object> changes) {
        var a = new AuditLog();
        a.setOutletId(me.outletId());
        a.setActorStaffId(me.staffId());
        a.setAction(action);
        a.setEntityType(type);
        a.setEntityId(id);
        a.setChanges(changes);
        audit.save(a);
        log.info("audit: {} {} {} by {}", action, type, id, me.role());
    }
}
