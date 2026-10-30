package com.tablewise.inventory;

import com.tablewise.common.ApiException;
import com.tablewise.common.Db;
import com.tablewise.domain.Catalog.*;
import com.tablewise.domain.Repos.*;
import com.tablewise.domain.Tenancy.AuditLog;
import com.tablewise.security.Principals.StaffPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Inventory screen: ingredients, restock, corrections, movement log. Owner only (README 7.1). */
@RestController
@PreAuthorize("hasRole('OWNER')")
class InventoryController {
    record IngredientForm(@NotBlank String name, @Pattern(regexp = "kg|g|l|ml|pcs") String unit, @PositiveOrZero BigDecimal reorderLevel,
                          @PositiveOrZero BigDecimal costPerUnit, String supplierName, @PositiveOrZero BigDecimal openingStock) {}
    record Restock(@Positive BigDecimal quantity, @PositiveOrZero BigDecimal unitCost, String note) {}
    record Adjust(@NotNull BigDecimal delta, @Pattern(regexp = "manual_correction|wastage") String reason, String note) {}

    private final IngredientRepo ingredients;
    private final SupplierRepo suppliers;
    private final StockService stock;
    private final Db db;
    private final JdbcTemplate jdbc;
    private final AuditRepo audit;

    InventoryController(IngredientRepo ingredients, SupplierRepo suppliers, StockService stock, Db db, JdbcTemplate jdbc, AuditRepo audit) {
        this.ingredients = ingredients;
        this.suppliers = suppliers;
        this.stock = stock;
        this.db = db;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/ingredients")
    List<Map<String, Object>> list(@AuthenticationPrincipal StaffPrincipal me) {
        return db.rows("""
                select v.id, v.name, v.unit, v.current_stock, v.reorder_level, v.cost_per_unit, v.stock_state, v.stock_value, s.name as supplier
                  from pos.v_ingredient_status v left join pos.suppliers s on s.id = v.supplier_id
                 where v.outlet_id = ? order by v.name""", me.outletId());
    }

    @PostMapping("/ingredients") @ResponseStatus(HttpStatus.CREATED) @Transactional
    Map<String, Object> create(@Valid @RequestBody IngredientForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var i = new Ingredient();
        i.setOutletId(me.outletId());
        apply(i, f, me);
        try {
            ingredients.saveAndFlush(i);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict("An ingredient named " + f.name() + " already exists");
        }
        if (f.openingStock() != null && f.openingStock().signum() > 0) {
            stock.move(me.outletId(), i.getId(), f.openingStock(), "opening_stock", null, me.staffId(), "Opening stock", null);
        }
        return Map.of("id", i.getId());
    }

    @PutMapping("/ingredients/{id}") @Transactional
    void update(@PathVariable UUID id, @Valid @RequestBody IngredientForm f, @AuthenticationPrincipal StaffPrincipal me) {
        apply(find(id, me), f, me);
    }

    @DeleteMapping("/ingredients/{id}") @Transactional
    void delete(@PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal me) {
        var i = find(id, me);
        boolean used = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from pos.recipe_items r join pos.menu_items m on m.id = r.menu_item_id where r.ingredient_id = ? and m.deleted_at is null)",
                Boolean.class, id));
        if (used) throw ApiException.conflict("Remove " + i.getName() + " from recipes first");
        i.setDeletedAt(Instant.now());
    }

    @PostMapping("/ingredients/{id}/restock") @Transactional
    Map<String, Object> restock(@PathVariable UUID id, @Valid @RequestBody Restock r, @AuthenticationPrincipal StaffPrincipal me) {
        var after = stock.move(me.outletId(), id, r.quantity(), "restock", null, me.staffId(), r.note(), r.unitCost());
        return Map.of("currentStock", after);
    }

    @PostMapping("/ingredients/{id}/adjust") @Transactional
    Map<String, Object> adjust(@PathVariable UUID id, @Valid @RequestBody Adjust a, @AuthenticationPrincipal StaffPrincipal me) {
        if (a.delta().signum() == 0) throw ApiException.badRequest("Enter a quantity other than zero");
        var after = stock.move(me.outletId(), id, a.delta(), a.reason(), null, me.staffId(), a.note(), null);
        return Map.of("currentStock", after);
    }

    @GetMapping("/stock-movements")
    List<Map<String, Object>> movements(@RequestParam(required = false) UUID ingredientId, @RequestParam(defaultValue = "100") int limit, @AuthenticationPrincipal StaffPrincipal me) {
        return db.rows("""
                select m.id, m.created_at, i.name as ingredient, i.unit, m.quantity_delta, m.reason, m.stock_after, m.note, m.order_id, o.order_no, s.full_name as by
                  from pos.stock_movements m join pos.ingredients i on i.id = m.ingredient_id
                  left join pos.orders o on o.id = m.order_id left join pos.staff_members s on s.id = m.created_by
                 where m.outlet_id = ? and (?::uuid is null or m.ingredient_id = ?::uuid)
                 order by m.id desc limit ?""", me.outletId(), ingredientId, ingredientId, Math.min(limit, 500));
    }

    private Ingredient find(UUID id, StaffPrincipal me) {
        return ingredients.findByIdAndOutletIdAndDeletedAtIsNull(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Ingredient not found"));
    }

    private void apply(Ingredient i, IngredientForm f, StaffPrincipal me) {
        var oldCost = i.getCostPerUnit();
        i.setName(f.name().trim());
        i.setUnit(f.unit());
        if (f.reorderLevel() != null) i.setReorderLevel(f.reorderLevel());
        if (f.costPerUnit() != null) i.setCostPerUnit(f.costPerUnit());
        if (f.supplierName() != null && !f.supplierName().isBlank()) {
            var name = f.supplierName().trim();
            i.setSupplierId(suppliers.findByOutletIdAndNameIgnoreCase(me.outletId(), name).orElseGet(() -> {
                var s = new Supplier();
                s.setOutletId(me.outletId());
                s.setName(name);
                return suppliers.save(s);
            }).getId());
        }
        if (i.getId() != null && oldCost != null && f.costPerUnit() != null && oldCost.compareTo(f.costPerUnit()) != 0) {
            var a = new AuditLog();
            a.setOutletId(me.outletId());
            a.setActorStaffId(me.staffId());
            a.setAction("ingredient.cost_changed");
            a.setEntityType("ingredient");
            a.setEntityId(i.getId());
            a.setChanges(Map.of("costPerUnit", Map.of("from", oldCost, "to", f.costPerUnit())));
            audit.save(a);
        }
    }
}
