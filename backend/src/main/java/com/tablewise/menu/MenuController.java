package com.tablewise.menu;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Menu screen and POS menu. Reading is for owner and cashier; every write is owner only. */
@RestController
class MenuController {
    private static final Logger log = LoggerFactory.getLogger(MenuController.class);

    record RecipeLine(@NotNull UUID ingredientId, @NotNull @Positive BigDecimal qtyPerPortion) {}
    record ItemForm(@NotNull UUID categoryId, @NotBlank String name, String shortCode, String description, @NotNull @Positive BigDecimal price,
                    boolean veg, @PositiveOrZero Short prepMinutes, BigDecimal gstPercentOverride, String imageUrl, @Valid List<RecipeLine> recipe) {}
    record Availability(boolean available) {}
    record CategoryForm(@NotBlank String name, Integer sortOrder) {}

    private final MenuItemRepo items;
    private final CategoryRepo categories;
    private final RecipeRepo recipes;
    private final AuditRepo audit;
    private final IngredientRepo ingredientRepo;
    private final Db db;

    MenuController(MenuItemRepo items, CategoryRepo categories, RecipeRepo recipes, AuditRepo audit, IngredientRepo ingredientRepo, Db db) {
        this.items = items;
        this.categories = categories;
        this.recipes = recipes;
        this.audit = audit;
        this.ingredientRepo = ingredientRepo;
        this.db = db;
    }

    @GetMapping("/menu") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    Map<String, Object> menu(@AuthenticationPrincipal StaffPrincipal me) {
        return Map.of("categories", categories.findByOutletIdOrderBySortOrderAscNameAsc(me.outletId()).stream()
                .map(c -> Map.of("id", c.getId(), "name", c.getName(), "sortOrder", c.getSortOrder())).toList(), "items", itemList(me));
    }

    @GetMapping("/menu-items") @PreAuthorize("hasRole('OWNER')")
    List<Map<String, Object>> list(@AuthenticationPrincipal StaffPrincipal me) { return itemList(me); }

    @PostMapping("/menu-items") @PreAuthorize("hasRole('OWNER')") @ResponseStatus(HttpStatus.CREATED) @Transactional
    Map<String, Object> create(@Valid @RequestBody ItemForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var m = new MenuItem();
        m.setOutletId(me.outletId());
        save(m, f, me);
        return Map.of("id", m.getId());
    }

    @PutMapping("/menu-items/{id}") @PreAuthorize("hasRole('OWNER')") @Transactional
    void update(@PathVariable UUID id, @Valid @RequestBody ItemForm f, @AuthenticationPrincipal StaffPrincipal me) { save(find(id, me), f, me); }

    @PatchMapping("/menu-items/{id}/availability") @PreAuthorize("hasRole('OWNER')") @Transactional
    void availability(@PathVariable UUID id, @RequestBody Availability a, @AuthenticationPrincipal StaffPrincipal me) { find(id, me).setAvailable(a.available()); }

    @DeleteMapping("/menu-items/{id}") @PreAuthorize("hasRole('OWNER')") @Transactional
    void delete(@PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal me) {
        find(id, me).setDeletedAt(Instant.now());
        log(me, "menu_item.deleted", id, Map.of());
    }

    @GetMapping("/categories") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    List<Category> categories(@AuthenticationPrincipal StaffPrincipal me) { return categories.findByOutletIdOrderBySortOrderAscNameAsc(me.outletId()); }

    @PostMapping("/categories") @PreAuthorize("hasRole('OWNER')") @ResponseStatus(HttpStatus.CREATED) @Transactional
    Map<String, Object> addCategory(@Valid @RequestBody CategoryForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var c = new Category();
        c.setOutletId(me.outletId());
        c.setName(f.name().trim());
        c.setSortOrder(f.sortOrder() == null ? 0 : f.sortOrder());
        try { categories.saveAndFlush(c); } catch (DataIntegrityViolationException e) { throw ApiException.conflict("A category named " + f.name() + " already exists"); }
        return Map.of("id", c.getId());
    }

    @PutMapping("/categories/{id}") @PreAuthorize("hasRole('OWNER')") @Transactional
    void renameCategory(@PathVariable UUID id, @Valid @RequestBody CategoryForm f, @AuthenticationPrincipal StaffPrincipal me) {
        var c = categories.findByIdAndOutletId(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Category not found"));
        c.setName(f.name().trim());
        if (f.sortOrder() != null) c.setSortOrder(f.sortOrder());
    }

    @DeleteMapping("/categories/{id}") @PreAuthorize("hasRole('OWNER')") @Transactional
    void deleteCategory(@PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal me) {
        var c = categories.findByIdAndOutletId(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Category not found"));
        try {
            categories.delete(c);
            categories.flush();
        } catch (DataIntegrityViolationException e) { // RESTRICT foreign key, includes soft-deleted dishes
            throw ApiException.conflict("Move the dishes in " + c.getName() + " to another category first");
        }
    }

    private List<Map<String, Object>> itemList(StaffPrincipal me) {
        var rows = db.rows("""
                select m.id, m.category_id, m.name, m.short_code, m.description, m.price, m.is_veg as veg, m.prep_minutes, m.gst_percent_override, m.image_url,
                       a.is_available as available, a.portions_left, a.stock_state
                  from pos.menu_items m join pos.v_menu_availability a on a.menu_item_id = m.id
                 where m.outlet_id = ? and m.deleted_at is null order by m.sort_order, m.name""", me.outletId());
        var byItem = new HashMap<UUID, List<Map<String, Object>>>();
        recipes.findByMenuItemIdIn(rows.stream().map(r -> (UUID) r.get("id")).toList()).forEach(r ->
                byItem.computeIfAbsent(r.getMenuItemId(), k -> new ArrayList<>()).add(Map.of("ingredientId", r.getIngredientId(), "qtyPerPortion", r.getQtyPerPortion())));
        rows.forEach(r -> r.put("recipe", byItem.getOrDefault(r.get("id"), List.of())));
        return rows;
    }

    private MenuItem find(UUID id, StaffPrincipal me) {
        return items.findByIdAndOutletIdAndDeletedAtIsNull(id, me.outletId()).orElseThrow(() -> ApiException.notFound("Dish not found"));
    }

    private void save(MenuItem m, ItemForm f, StaffPrincipal me) {
        categories.findByIdAndOutletId(f.categoryId(), me.outletId()).orElseThrow(() -> ApiException.badRequest("Choose a category"));
        var oldPrice = m.getPrice();
        m.setCategoryId(f.categoryId());
        m.setName(f.name().trim());
        m.setShortCode(f.shortCode());
        m.setDescription(f.description());
        m.setPrice(f.price());
        m.setVeg(f.veg());
        if (f.prepMinutes() != null) m.setPrepMinutes(f.prepMinutes());
        m.setGstPercentOverride(f.gstPercentOverride());
        m.setImageUrl(f.imageUrl());
        try {
            items.saveAndFlush(m);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("A dish named " + f.name() + " already exists");
        }
        if (oldPrice != null && oldPrice.compareTo(f.price()) != 0) {
            log(me, "menu_item.price_changed", m.getId(), Map.of("price", Map.of("from", oldPrice, "to", f.price())));
        }
        if (f.recipe() != null) {
            var ids = f.recipe().stream().map(RecipeLine::ingredientId).collect(java.util.stream.Collectors.toSet());
            if (ids.size() != f.recipe().size()) throw ApiException.badRequest("List each ingredient once");
            if (ingredientRepo.findByOutletIdAndIdIn(me.outletId(), ids).stream().filter(i -> i.getDeletedAt() == null).count() != ids.size()) {
                throw ApiException.badRequest("Unknown ingredient in recipe");
            }
            recipes.deleteByMenuItemId(m.getId());
            recipes.flush();
            f.recipe().forEach(r -> {
                var ri = new RecipeItem();
                ri.setMenuItemId(m.getId());
                ri.setIngredientId(r.ingredientId());
                ri.setQtyPerPortion(r.qtyPerPortion());
                recipes.save(ri);
            });
        }
    }

    private void log(StaffPrincipal me, String action, UUID id, Map<String, Object> changes) {
        var a = new AuditLog();
        a.setOutletId(me.outletId());
        a.setActorStaffId(me.staffId());
        a.setAction(action);
        a.setEntityType("menu_item");
        a.setEntityId(id);
        a.setChanges(changes);
        audit.save(a);
        log.info("audit: {} {} {} by {}", action, a.getEntityType(), a.getEntityId(), me.role());
    }
}
