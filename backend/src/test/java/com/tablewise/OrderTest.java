package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** M4: placing orders, numbering, stock, the status machine and role table, cancellation, search (README 7.1, 7.2, 7.3, 7.7). */
class OrderTest extends IntegrationTest {
    private Map<String, Object> order(String type, UUID table, Object... itemPairs) {
        var items = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < itemPairs.length; i += 2) items.add(Map.of("menuItemId", itemPairs[i], "quantity", itemPairs[i + 1]));
        var m = new java.util.HashMap<String, Object>(Map.of("orderType", type, "items", items));
        if (table != null) m.put("tableId", table);
        return m;
    }

    private UUID place(RequestPostProcessor who, Map<String, Object> body) throws Exception {
        return UUID.fromString(json(call(post("/orders"), who, body).andExpect(status().isCreated())).get("id").asText());
    }

    @Test
    void placeOrderWritesOrderItemsBillNumberAndStockTogether() throws Exception {
        var o = newOutlet();
        UUID chicken = ingredient(o.id(), "Chicken", "kg", "10", "2");
        UUID biryani = dish(o, "Chicken Biryani", "320", chicken, "0.25");
        var cashier = as(o.id(), "cashier");

        var body = order("dine_in", o.t1(), biryani, 2);
        body.put("customerName", "Ananya");
        body.put("customerPhone", "90000 10001");
        var res = json(call(post("/orders"), cashier, body).andExpect(status().isCreated()));

        assertThat(res.get("orderNo").asInt()).isEqualTo(1000);                         // first number of the outlet
        assertThat(res.get("total").decimalValue()).isEqualByComparingTo("672");         // 640 + 5 % GST
        assertThat(res.get("tableLabel").asText()).isEqualTo("T1");
        assertThat(stock(chicken)).isEqualByComparingTo("9.5");                         // 2 x 0.25 deducted
        assertThat(jdbc.queryForObject("select count(*) from pos.stock_movements where reason='order_consumption' and order_id = ?::uuid", Integer.class, res.get("id").asText())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from pos.bills where order_id = ?::uuid", String.class, res.get("id").asText())).isEqualTo("open");
        assertThat(jdbc.queryForObject("select phone from pos.customers where outlet_id = ?", String.class, o.id())).isEqualTo("+919000010001");

        UUID second = place(cashier, order("takeaway", null, biryani, 1));
        assertThat(jdbc.queryForObject("select order_no from pos.orders where id = ?", Integer.class, second)).isEqualTo(1001); // numbers count up
        assertThat(jdbc.queryForObject("select business_date = (now() at time zone 'Asia/Kolkata')::date from pos.orders where id = ?", Boolean.class, second)).isTrue();
    }

    @Test
    void serverComputesTheBillAndIgnoresClientTotals() throws Exception {
        var o = newOutlet("5.00", "10");
        UUID dal = dish(o, "Dal", "190");
        var body = order("takeaway", null, dal, 1);
        body.put("total", 1);
        body.put("discountType", "percent");
        body.put("discountValue", 10);
        var res = json(call(post("/orders"), as(o.id(), "owner"), body).andExpect(status().isCreated()));
        // 190 - 19 = 171; service 17.10; taxable 188.10; gst 9.405; exact 197.505 -> 198
        assertThat(res.get("total").decimalValue()).isEqualByComparingTo("198");
        assertThat(res.get("discountAmount").decimalValue()).isEqualByComparingTo("19");
        assertThat(res.get("roundOff").decimalValue()).isEqualByComparingTo("0.50");
    }

    @Test
    void notEnoughStockRollsBackTheWholeOrder() throws Exception {
        var o = newOutlet();
        UUID chicken = ingredient(o.id(), "Chicken", "kg", "1", "0");
        UUID oil = ingredient(o.id(), "Oil", "l", "5", "0");
        UUID fry = dish(o, "Chicken Fry", "280", oil, "0.05", chicken, "0.2");   // 5 portions of chicken
        var cashier = as(o.id(), "cashier");
        call(post("/orders"), cashier, order("takeaway", null, fry, 6)).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Not enough chicken in stock"));
        assertThat(stock(chicken)).isEqualByComparingTo("1");
        assertThat(stock(oil)).isEqualByComparingTo("5");                                  // oil deduction rolled back too
        assertThat(jdbc.queryForObject("select count(*) from pos.orders where outlet_id = ?", Integer.class, o.id())).isZero();
        assertThat(jdbc.queryForObject("select next_order_no from pos.outlets where id = ?", Integer.class, o.id())).isEqualTo(1000); // number not burned
    }

    @Test
    void switchedOffOutOfStockAndBusyTableAreRefused() throws Exception {
        var o = newOutlet();
        UUID rice = ingredient(o.id(), "Rice", "kg", "0.05", "0");
        UUID out = dish(o, "Rice Bowl", "100", rice, "0.1");
        UUID off = dish(o, "Special", "100");
        jdbc.update("update pos.menu_items set is_available = false where id = ?", off);
        UUID ok = dish(o, "Water", "10");
        var cashier = as(o.id(), "cashier");
        call(post("/orders"), cashier, order("takeaway", null, out, 1)).andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("out of stock")));
        call(post("/orders"), cashier, order("takeaway", null, off, 1)).andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("switched off")));
        call(post("/orders"), cashier, order("dine_in", null, ok, 1)).andExpect(status().isBadRequest());     // no table
        place(cashier, order("dine_in", o.t1(), ok, 1));
        call(post("/orders"), cashier, order("dine_in", o.t1(), ok, 1)).andExpect(status().isConflict());     // T1 busy
        var delivery = order("delivery", null, ok, 1);
        call(post("/orders"), cashier, delivery).andExpect(status().isBadRequest());                          // needs name and phone
        call(post("/orders"), as(o.id(), "kitchen"), order("takeaway", null, ok, 1)).andExpect(status().isForbidden());
    }

    @Test
    void statusMachineFollowsTheRoleTable() throws Exception {
        var o = newOutlet();
        UUID dal = dish(o, "Dal", "190");
        var owner = as(o.id(), "owner");
        var cashier = as(o.id(), "cashier");
        var kitchen = as(o.id(), "kitchen");
        UUID take = place(cashier, order("takeaway", null, dal, 1));

        call(post("/orders/" + take + "/advance"), cashier, null).andExpect(status().isForbidden());     // cashier cannot start
        call(post("/orders/" + take + "/advance"), kitchen, null).andExpect(status().isOk());            // new -> preparing
        call(post("/orders/" + take + "/advance"), cashier, null).andExpect(status().isForbidden());     // cashier cannot mark ready
        call(post("/orders/" + take + "/advance"), kitchen, null).andExpect(status().isOk());            // preparing -> ready
        call(post("/orders/" + take + "/advance"), kitchen, null).andExpect(status().isForbidden());     // kitchen cannot hand over
        // takeaway must be paid before it is handed over (rule, and the DB constraint behind it)
        call(post("/orders/" + take + "/advance"), cashier, null).andExpect(status().isConflict()).andExpect(jsonPath("$.error", containsString("Collect payment")));
        jdbc.update("update pos.orders set payment_status = 'paid' where id = ?", take);
        call(post("/orders/" + take + "/advance"), cashier, null).andExpect(status().isOk());
        call(post("/orders/" + take + "/advance"), owner, null).andExpect(status().isConflict());        // already completed

        assertThat(jdbc.queryForList("select to_status from pos.order_status_events where order_id = ? order by id", String.class, take))
                .containsExactly("new", "preparing", "ready", "completed");
        assertThat(jdbc.queryForObject("select status_changed_at > placed_at from pos.orders where id = ?", Boolean.class, take)).isTrue();

        UUID dine = place(cashier, order("dine_in", o.t2(), dal, 1));                                    // dine-in may be served before it is paid
        call(post("/orders/" + dine + "/advance"), owner, null);
        call(post("/orders/" + dine + "/advance"), owner, null);
        call(post("/orders/" + dine + "/advance"), cashier, null).andExpect(status().isOk());
    }

    @Test
    void cancellingANewOrderRestoresStockAndVoidsTheBill() throws Exception {
        var o = newOutlet();
        UUID chicken = ingredient(o.id(), "Chicken", "kg", "10", "2");
        UUID fry = dish(o, "Chicken Fry", "280", chicken, "0.5");
        var cashier = as(o.id(), "cashier");
        UUID id = place(cashier, order("takeaway", null, fry, 2));
        assertThat(stock(chicken)).isEqualByComparingTo("9");

        call(post("/orders/" + id + "/cancel"), cashier, Map.of("reason", " ")).andExpect(status().isBadRequest());   // reason is required
        call(post("/orders/" + id + "/cancel"), as(o.id(), "kitchen"), Map.of("reason", "x")).andExpect(status().isForbidden());
        call(post("/orders/" + id + "/cancel"), cashier, Map.of("reason", "Customer left")).andExpect(status().isOk());

        assertThat(stock(chicken)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select count(*) from pos.stock_movements where reason='order_cancellation' and order_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from pos.bills where order_id = ?", String.class, id)).isEqualTo("void");
        assertThat(jdbc.queryForObject("select note from pos.order_status_events where order_id = ? and to_status = 'cancelled'", String.class, id)).isEqualTo("Customer left");
        call(post("/orders/" + id + "/cancel"), cashier, Map.of("reason", "again")).andExpect(status().isConflict());
    }

    @Test
    void cancellingAPreparingOrderIsOwnerOnlyAndKeepsStockDeducted() throws Exception {
        var o = newOutlet();
        UUID chicken = ingredient(o.id(), "Chicken", "kg", "10", "2");
        UUID fry = dish(o, "Chicken Fry", "280", chicken, "0.5");
        UUID id = place(as(o.id(), "cashier"), order("takeaway", null, fry, 2));
        call(post("/orders/" + id + "/advance"), as(o.id(), "kitchen"), null);

        call(post("/orders/" + id + "/cancel"), as(o.id(), "cashier"), Map.of("reason", "x")).andExpect(status().isForbidden());
        call(post("/orders/" + id + "/cancel"), as(o.id(), "owner"), Map.of("reason", "Wrong order")).andExpect(status().isOk());
        assertThat(stock(chicken)).isEqualByComparingTo("9");                                           // food already used
        assertThat(jdbc.queryForObject("select count(*) from pos.stock_movements where reason='order_cancellation' and outlet_id = ?", Integer.class, o.id())).isZero();
        assertThat(jdbc.queryForObject("select status from pos.bills where order_id = ?", String.class, id)).isEqualTo("void");
    }

    @Test
    void boardHidesMoneyFromKitchenAndSearchFindsOrders() throws Exception {
        var o = newOutlet();
        UUID dal = dish(o, "Dal", "190");
        var body = order("delivery", null, dal, 1);
        body.put("customerName", "Rohit Verma");
        body.put("customerPhone", "9000010002");
        UUID id = place(as(o.id(), "cashier"), body);
        place(as(o.id(), "cashier"), order("takeaway", null, dal, 1));

        var kitchenView = json(call(get("/orders"), as(o.id(), "kitchen"), null));
        assertThat(kitchenView).hasSize(2);
        assertThat(kitchenView.get(0).has("total")).isFalse();
        assertThat(kitchenView.get(0).has("billToName")).isFalse();
        assertThat(kitchenView.get(0).has("paymentStatus")).isFalse();
        assertThat(kitchenView.get(0).get("items").get(0).has("unitPrice")).isFalse();

        var cashier = as(o.id(), "cashier");
        assertThat(json(call(get("/orders?q=1000"), cashier, null))).hasSize(1);
        assertThat(json(call(get("/orders?q=rohit"), cashier, null)).get(0).get("id").asText()).isEqualTo(id.toString());
        assertThat(json(call(get("/orders?q=9000010002"), cashier, null))).hasSize(1);
        assertThat(json(call(get("/orders?type=takeaway"), cashier, null))).hasSize(1);
        assertThat(json(call(get("/orders?q=rohit"), as(o.id(), "kitchen"), null))).isEmpty();           // kitchen cannot search by customer
        assertThat(json(call(get("/orders?unpaid=true"), cashier, null))).hasSize(2);
    }
}
