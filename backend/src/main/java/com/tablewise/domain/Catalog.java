package com.tablewise.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

/** Menu, recipes, suppliers, ingredients and the stock ledger. */
public final class Catalog {
    private Catalog() {}

    @Entity @Table(name = "menu_categories") @Getter @Setter
    public static class Category {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        String name;
        @Column(name = "sort_order") int sortOrder;
        @Column(name = "is_active") boolean active = true;
    }

    @Entity @Table(name = "menu_items") @Getter @Setter
    public static class MenuItem {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        @Column(name = "category_id") UUID categoryId;
        String name;
        @Column(name = "short_code") String shortCode;
        String description;
        BigDecimal price;
        @Column(name = "is_veg") boolean veg;
        @Column(name = "prep_minutes") Short prepMinutes = 10;
        @Column(name = "is_available") boolean available = true;
        @Column(name = "gst_percent_override") BigDecimal gstPercentOverride;
        @Column(name = "image_url") String imageUrl;
        @Column(name = "sort_order") int sortOrder;
        @Column(name = "deleted_at") Instant deletedAt;
    }

    @Entity @Table(name = "suppliers") @Getter @Setter
    public static class Supplier {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        String name, phone, gstin;
    }

    @Entity @Table(name = "ingredients") @Getter @Setter
    public static class Ingredient {
        @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
        @Column(name = "outlet_id") UUID outletId;
        String name, unit;
        @Column(name = "current_stock", insertable = false, updatable = false) BigDecimal currentStock; // ledger-only
        @Column(name = "reorder_level") BigDecimal reorderLevel = BigDecimal.ZERO;
        @Column(name = "cost_per_unit") BigDecimal costPerUnit = BigDecimal.ZERO;
        @Column(name = "supplier_id") UUID supplierId;
        @Column(name = "deleted_at") Instant deletedAt;
    }

    @Entity @Table(name = "recipe_items") @IdClass(RecipeItem.Pk.class) @Getter @Setter
    public static class RecipeItem {
        @Id @Column(name = "menu_item_id") UUID menuItemId;
        @Id @Column(name = "ingredient_id") UUID ingredientId;
        @Column(name = "qty_per_portion") BigDecimal qtyPerPortion;

        @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
        public static class Pk implements Serializable {
            UUID menuItemId;
            UUID ingredientId;
        }
    }

    @Entity @Table(name = "stock_movements") @Getter @Setter
    public static class StockMovement {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
        @Column(name = "outlet_id") UUID outletId;
        @Column(name = "ingredient_id") UUID ingredientId;
        @Column(name = "quantity_delta") BigDecimal quantityDelta;
        String reason;
        @Column(name = "order_id") UUID orderId;
        @Column(name = "unit_cost") BigDecimal unitCost;
        String note;
        @Column(name = "stock_after", insertable = false, updatable = false) BigDecimal stockAfter; // trigger fills it
        @Column(name = "created_by") UUID createdBy;
        @Column(name = "created_at", insertable = false, updatable = false) Instant createdAt;
    }
}
