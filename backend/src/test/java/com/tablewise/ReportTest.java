package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M9: summary, comparison ranges, chart grain, GST summary, CSV export, dashboard, customers (README 9). */
class ReportTest extends IntegrationTest {
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    /** Places and pays an order of one dish, then moves it to a business date and (optionally) a source. */
    private UUID sale(Outlet o, UUID dish, int qty, LocalDate date, String source) throws Exception {
        UUID id = placeOrder(o, dish, qty);
        call(post("/orders/" + id + "/payments"), as(o.id(), "cashier"), Map.of("method", "cash", "tenderedAmount", 100000)).andExpect(status().isCreated());
        jdbc.update("update pos.orders set business_date = ? where id = ?", date, id);
        if (source != null) jdbc.update("update pos.orders set source = ?, order_type = 'delivery' where id = ?", source, id);
        return id;
    }

    private JsonNode summary(Outlet o, LocalDate from, LocalDate to, String compare) throws Exception {
        return json(call(get("/reports/summary?from=" + from + "&to=" + to + "&compare=" + compare), as(o.id(), "owner"), null).andExpect(status().isOk()));
    }

    @Test
    void summaryTotalsChannelsGstAndPaymentModes() throws Exception {
        var o = newOutlet();
        UUID dal = dish(o, "Dal", "200");                         // 200 + 5 % = 210
        sale(o, dal, 1, today, null);
        sale(o, dal, 2, today.minusDays(1), null);                // 420
        sale(o, dal, 1, today.minusDays(1), "swiggy");            // 210, kept apart for GST
        UUID cancelled = placeOrder(o, dal, 1);
        call(post("/orders/" + cancelled + "/cancel"), as(o.id(), "owner"), Map.of("reason", "test")).andExpect(status().isOk()); // not counted

        var cur = summary(o, today.minusDays(1), today, "none").get("current");
        assertThat(cur.get("totals").get("orders").asInt()).isEqualTo(3);
        assertThat(cur.get("totals").get("total").decimalValue()).isEqualByComparingTo("840");
        assertThat(cur.get("totals").get("taxableValue").decimalValue()).isEqualByComparingTo("800");
        assertThat(cur.get("totals").get("cgst").decimalValue()).isEqualByComparingTo("20");
        assertThat(cur.get("gst").get("swiggy").get("total").decimalValue()).isEqualByComparingTo("210");
        assertThat(cur.get("gst").get("own").get("total").decimalValue()).isEqualByComparingTo("630");
        assertThat(cur.get("gst").get("zomato").get("orders").asInt()).isZero();
        assertThat(cur.get("channels")).hasSize(2);
        assertThat(cur.get("paymentModes").get(0).get("method").asText()).isEqualTo("cash");
        assertThat(cur.get("paymentModes").get(0).get("amount").decimalValue()).isEqualByComparingTo("840");
        assertThat(cur.get("topItems").get(0).get("name").asText()).isEqualTo("Dal");
        assertThat(cur.get("topItems").get(0).get("quantity").asInt()).isEqualTo(4);
        assertThat(cur.get("categories").get(0).get("name").asText()).isEqualTo("Mains");
        assertThat(cur.get("series")).hasSize(2);
    }

    @Test
    void comparisonUsesSameLengthPreviousPeriodOrSameDatesLastYear() throws Exception {
        var o = newOutlet();
        UUID dal = dish(o, "Dal", "200");
        sale(o, dal, 1, today, null);
        sale(o, dal, 1, today.minusDays(2), null);
        sale(o, dal, 1, today.minusYears(1), null);

        var prev = summary(o, today.minusDays(1), today, "prev");           // 2 days: compared with the 2 days before
        assertThat(prev.get("current").get("totals").get("orders").asInt()).isEqualTo(1);
        assertThat(prev.get("comparison").get("from").asText()).isEqualTo(today.minusDays(3).toString());
        assertThat(prev.get("comparison").get("to").asText()).isEqualTo(today.minusDays(2).toString());
        assertThat(prev.get("comparison").get("totals").get("orders").asInt()).isEqualTo(1);

        var yoy = summary(o, today, today, "yoy");
        assertThat(yoy.get("comparison").get("from").asText()).isEqualTo(today.minusYears(1).toString());
        assertThat(yoy.get("comparison").get("totals").get("orders").asInt()).isEqualTo(1);
        assertThat(summary(o, today, today, "none").has("comparison")).isFalse();
    }

    @Test
    void chartGrainIsDailyWeeklyOrMonthlyAndGapsAreZero() throws Exception {
        var o = newOutlet();
        var day = summary(o, today.minusDays(9), today, "none").get("current");
        assertThat(day.get("grain").asText()).isEqualTo("day");
        assertThat(day.get("series")).hasSize(10);
        assertThat(day.get("series").get(0).get("sales").decimalValue()).isEqualByComparingTo("0");

        var week = summary(o, today.minusDays(99), today, "none").get("current");
        assertThat(week.get("grain").asText()).isEqualTo("week");
        assertThat(LocalDate.parse(week.get("series").get(0).get("date").asText()).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);

        assertThat(summary(o, today.minusDays(299), today, "none").get("current").get("grain").asText()).isEqualTo("month");
        assertThat(summary(o, today.minusDays(61), today, "none").get("current").get("grain").asText()).isEqualTo("day");   // 62 days
        assertThat(summary(o, today.minusDays(62), today, "none").get("current").get("grain").asText()).isEqualTo("week");  // 63 days
    }

    @Test
    void rangeRulesAndRoles() throws Exception {
        var o = newOutlet();
        var owner = as(o.id(), "owner");
        call(get("/reports/summary?from=" + today + "&to=" + today.plusDays(1)), owner, null).andExpect(status().isBadRequest());                 // future
        call(get("/reports/summary?from=" + today.minusYears(3) + "&to=" + today), owner, null).andExpect(status().isBadRequest());              // over 2 years
        call(get("/reports/summary?from=" + today + "&to=" + today.minusDays(1)), owner, null).andExpect(status().isBadRequest());               // reversed
        call(get("/reports/summary?from=" + today + "&to=" + today), as(o.id(), "cashier"), null).andExpect(status().isForbidden());
        call(get("/dashboard"), as(o.id(), "cashier"), null).andExpect(status().isForbidden());
        call(get("/reports/export?kind=summary&from=" + today + "&to=" + today), as(o.id(), "kitchen"), null).andExpect(status().isForbidden());
    }

    @Test
    void csvExportsAreStreamedAndEscaped() throws Exception {
        var o = newOutlet();
        UUID tricky = dish(o, "Chicken \"65\", spicy", "200");
        UUID formula = dish(o, "=cmd", "100");
        sale(o, tricky, 1, today, null);
        sale(o, formula, 1, today, null);
        var owner = as(o.id(), "owner");

        var item = call(get("/reports/export?kind=itemwise&from=" + today + "&to=" + today), owner, null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv"))).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("itemwise")));
        // StreamingResponseBody runs async in MockMvc
        String csv = mvc.perform(asyncDispatch(item.andReturn())).andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("Dish,Quantity,Sales").contains("\"Chicken \"\"65\"\", spicy\",1,200.00").contains("'=cmd,1,100.00");

        for (String kind : new String[] {"summary", "daywise"}) {
            var r = call(get("/reports/export?kind=" + kind + "&from=" + today + "&to=" + today), owner, null).andExpect(status().isOk());
            assertThat(mvc.perform(asyncDispatch(r.andReturn())).andReturn().getResponse().getContentAsString()).contains("Orders,Gross sales");
        }
        call(get("/reports/export?kind=nope&from=" + today + "&to=" + today), owner, null).andExpect(status().isBadRequest());
    }

    @Test
    void dashboardAndCustomersScreens() throws Exception {
        var o = newOutlet();
        UUID rice = ingredient(o.id(), "Rice", "kg", "0.2", "1");   // low
        UUID dal = dish(o, "Dal", "200", rice, "0.1");
        var cashier = as(o.id(), "cashier");
        var body = Map.of("orderType", "takeaway", "customerName", "Meera Iyer", "customerPhone", "9000010003", "items", java.util.List.of(Map.of("menuItemId", dal, "quantity", 1)));
        call(post("/orders"), cashier, body).andExpect(status().isCreated());
        placeOrder(o, dal, 1);                                       // walk-in

        var d = json(call(get("/dashboard"), as(o.id(), "owner"), null).andExpect(status().isOk()));
        assertThat(d.get("sales").get("day").get("orders").asInt()).isEqualTo(2);
        assertThat(d.get("openOrders").get(0).get("count").asInt()).isEqualTo(2);
        assertThat(d.get("needsAttention").get(0).get("name").asText()).isEqualTo("Rice");
        assertThat(d.get("topItems").get(0).get("name").asText()).isEqualTo("Dal");

        var c = json(call(get("/customers?q=meera"), cashier, null).andExpect(status().isOk()));
        assertThat(c.get("customers")).hasSize(1);
        assertThat(c.get("customers").get(0).get("orders").asInt()).isEqualTo(1);
        assertThat(c.get("walkIns").asInt()).isEqualTo(1);
        assertThat(c.get("customers").get(0).get("favouriteItem").asText()).isEqualTo("Dal");
    }
}
