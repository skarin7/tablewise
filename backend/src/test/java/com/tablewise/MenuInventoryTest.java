package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M3: categories, dishes, recipes, ingredients, restock and corrections, availability view (README 7.3). */
class MenuInventoryTest extends IntegrationTest {
    @Test
    void restockAndCorrectionsAreLedgerRowsAndStockNeverGoesNegative() throws Exception {
        var o = newOutlet();
        var owner = as(o.id(), "owner");
        UUID chicken = UUID.fromString(json(call(post("/ingredients"), owner, Map.of("name", "Chicken", "unit", "kg", "reorderLevel", 6, "costPerUnit", 220, "supplierName", "Al-Noor Meats", "openingStock", 4))).get("id").asText());
        assertThat(stock(chicken)).isEqualByComparingTo("4");

        call(post("/ingredients/" + chicken + "/restock"), owner, Map.of("quantity", 12, "unitCost", 225, "note", "Inv 88")).andExpect(jsonPath("$.currentStock").value(16));
        call(post("/ingredients/" + chicken + "/adjust"), owner, Map.of("delta", -1.5, "reason", "manual_correction")).andExpect(status().isOk());
        assertThat(stock(chicken)).isEqualByComparingTo("14.5");

        call(post("/ingredients/" + chicken + "/adjust"), owner, Map.of("delta", -100, "reason", "wastage"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Not enough chicken in stock"));
        assertThat(stock(chicken)).isEqualByComparingTo("14.5");

        var log = json(call(get("/stock-movements"), owner, null));
        assertThat(log).hasSize(3);
        assertThat(log.get(0).get("reason").asText()).isEqualTo("manual_correction");
        assertThat(log.get(0).get("stockAfter").decimalValue()).isEqualByComparingTo("14.5");
    }

    @Test
    void availabilityStatesComeFromRecipesAndTheSwitch() throws Exception {
        var o = newOutlet();
        UUID rice = ingredient(o.id(), "Rice", "kg", "1", "0");
        UUID none = dish(o, "Plain water", "10");
        UUID ok = dish(o, "Jeera Rice", "140", rice, "0.1");      // 10 portions
        UUID low = dish(o, "Big Rice", "200", rice, "0.25");      // 4 portions
        UUID out = dish(o, "Huge Rice", "300", rice, "2");        // 0 portions
        UUID off = dish(o, "Off Rice", "150", rice, "0.1");
        var owner = as(o.id(), "owner");
        call(patch("/menu-items/" + off + "/availability"), owner, Map.of("available", false)).andExpect(status().isOk());

        var items = json(call(get("/menu"), owner, null)).get("items");
        var state = new java.util.HashMap<String, String>();
        items.forEach(i -> state.put(i.get("id").asText(), i.get("stockState").asText()));
        assertThat(state).containsEntry(none.toString(), "untracked").containsEntry(ok.toString(), "ok").containsEntry(low.toString(), "low")
                .containsEntry(out.toString(), "out").containsEntry(off.toString(), "off");
    }

    @Test
    void dishFormSavesRecipeAndPriceChangeIsAudited() throws Exception {
        var o = newOutlet();
        var owner = as(o.id(), "owner");
        UUID rice = ingredient(o.id(), "Rice", "kg", "5", "1");
        var form = Map.of("categoryId", o.category(), "name", "Jeera Rice", "price", 140, "veg", true, "recipe", List.of(Map.of("ingredientId", rice, "qtyPerPortion", 0.18)));
        UUID id = UUID.fromString(json(call(post("/menu-items"), owner, form)).get("id").asText());
        call(put("/menu-items/" + id), owner, Map.of("categoryId", o.category(), "name", "Jeera Rice", "price", 150, "veg", true)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from pos.recipe_items where menu_item_id = ?", Integer.class, id)).isEqualTo(1); // recipe untouched when omitted
        var audit = jdbc.queryForObject("select changes::text from pos.audit_log where entity_id = ? and action = 'menu_item.price_changed'", String.class, id);
        assertThat(audit).contains("140").contains("150");
        call(post("/menu-items"), owner, form).andExpect(status().isConflict()); // same name twice
    }

    @Test
    void deletesFollowTheRestrictRules() throws Exception {
        var o = newOutlet();
        var owner = as(o.id(), "owner");
        UUID rice = ingredient(o.id(), "Rice", "kg", "5", "1");
        UUID dish = dish(o, "Jeera Rice", "140", rice, "0.1");
        call(delete("/ingredients/" + rice), owner, null).andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("recipes")));
        call(delete("/categories/" + o.category()), owner, null).andExpect(status().isConflict());
        call(delete("/menu-items/" + dish), owner, null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select deleted_at is not null from pos.menu_items where id = ?", Boolean.class, dish)).isTrue(); // soft delete
        call(delete("/ingredients/" + rice), owner, null).andExpect(status().isOk());
        assertThat(json(call(get("/ingredients"), owner, null))).isEmpty();
    }
}
