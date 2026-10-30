package com.tablewise.reports;

import com.tablewise.common.ApiException;
import com.tablewise.common.Db;
import com.tablewise.domain.Repos.OutletRepo;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.stereotype.Service;

/** README 9: summary with comparison, chart grain, GST summary and CSV export, all read from the business_date views. */
@Service
public class ReportService {
    private final Db db;
    private final OutletRepo outlets;

    ReportService(Db db, OutletRepo outlets) {
        this.db = db;
        this.outlets = outlets;
    }

    public Map<String, Object> summary(UUID outletId, LocalDate from, LocalDate to, String compare) {
        LocalDate today = today(outletId);
        if (from == null || to == null) throw ApiException.badRequest("Choose a date range");
        if (from.isAfter(to)) throw ApiException.badRequest("The start date must be on or before the end date");
        if (to.isAfter(today)) throw ApiException.badRequest("Reports cannot include future dates");
        if (ChronoUnit.DAYS.between(from, to) > 730) throw ApiException.badRequest("Choose a range of 2 years or less");

        var out = new LinkedHashMap<String, Object>();
        out.put("current", period(outletId, from, to));
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        switch (compare == null ? "none" : compare) {
            case "prev" -> out.put("comparison", period(outletId, from.minusDays(days), from.minusDays(1))); // same number of days straight before
            case "yoy" -> out.put("comparison", period(outletId, from.minusYears(1), to.minusYears(1)));
            case "none" -> { }
            default -> throw ApiException.badRequest("compare must be prev, yoy or none");
        }
        return out;
    }

    private Map<String, Object> period(UUID outlet, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        String grain = days <= 62 ? "day" : days <= 190 ? "week" : "month";

        var channels = db.rows("""
                select channel, sum(orders) as orders, sum(gross_sales) as gross_sales, sum(discount) as discount, sum(taxable_value) as taxable_value,
                       sum(cgst) as cgst, sum(sgst) as sgst, sum(total) as total
                  from pos.v_daily_sales where outlet_id = ? and business_date between ? and ? group by channel order by channel""", outlet, from, to);

        var buckets = new LinkedHashMap<LocalDate, Map<String, Object>>();
        for (LocalDate d = bucket(from, grain); !d.isAfter(to); d = next(d, grain)) buckets.put(d, new LinkedHashMap<>(Map.of("date", d, "orders", 0L, "sales", BigDecimal.ZERO)));
        db.rows("select date_trunc('" + grain + "', business_date)::date as bucket, sum(orders) as orders, sum(total) as sales from pos.v_daily_sales where outlet_id = ? and business_date between ? and ? group by 1",
                outlet, from, to).forEach(r -> {
            var b = buckets.get(((java.sql.Date) r.get("bucket")).toLocalDate());
            if (b != null) { b.put("orders", r.get("orders")); b.put("sales", r.get("sales")); }
        });

        var p = new LinkedHashMap<String, Object>();
        p.put("from", from);
        p.put("to", to);
        p.put("grain", grain);
        p.put("totals", total(channels, null));
        p.put("channels", channels);
        p.put("gst", Map.of("own", total(channels, c -> !Set.of("swiggy", "zomato").contains(c)), "swiggy", total(channels, "swiggy"::equals), "zomato", total(channels, "zomato"::equals),
                "all", total(channels, null))); // platforms usually pay GST on their own orders (section 9(5)), so they are shown apart
        p.put("series", buckets.values());
        p.put("paymentModes", db.rows("select method, sum(payments) as payments, sum(amount) as amount from pos.v_payment_mode_daily where outlet_id = ? and business_date between ? and ? group by method order by 3 desc", outlet, from, to));
        p.put("topItems", db.rows("select item_name as name, sum(quantity) as quantity, sum(sales) as sales from pos.v_item_sales where outlet_id = ? and business_date between ? and ? group by item_name order by 3 desc limit 10", outlet, from, to));
        p.put("categories", db.rows("""
                select coalesce(c.name, 'Other') as name, sum(i.quantity) as quantity, sum(i.sales) as sales
                  from pos.v_item_sales i left join pos.menu_categories c on c.id = i.category_id
                 where i.outlet_id = ? and i.business_date between ? and ? group by 1 order by 3 desc""", outlet, from, to));
        return p;
    }

    private static Map<String, Object> total(List<Map<String, Object>> channels, java.util.function.Predicate<String> keep) {
        long orders = 0;
        var m = new LinkedHashMap<String, BigDecimal>();
        for (String k : List.of("gross_sales", "discount", "taxable_value", "cgst", "sgst", "total")) m.put(k, BigDecimal.ZERO);
        for (var c : channels) {
            if (keep != null && !keep.test((String) c.get("channel"))) continue;
            orders += ((Number) c.get("orders")).longValue();
            m.replaceAll((k, v) -> v.add((BigDecimal) c.get(camel(k))));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("orders", orders);
        m.forEach((k, v) -> out.put(camel(k), v));
        out.put("invoiceValue", m.get("total"));
        return out;
    }

    private static String camel(String s) {
        var b = new StringBuilder();
        boolean up = false;
        for (char ch : s.toCharArray()) { if (ch == '_') up = true; else { b.append(up ? Character.toUpperCase(ch) : ch); up = false; } }
        return b.toString();
    }

    private static LocalDate bucket(LocalDate d, String grain) {
        return switch (grain) { case "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); case "month" -> d.withDayOfMonth(1); default -> d; };
    }

    private static LocalDate next(LocalDate d, String grain) {
        return switch (grain) { case "week" -> d.plusWeeks(1); case "month" -> d.plusMonths(1); default -> d.plusDays(1); };
    }

    private LocalDate today(UUID outletId) { return LocalDate.now(ZoneId.of(outlets.findById(outletId).orElseThrow().getTimezone())); }

    // ----------------------------------------------------------------- export

    public void exportCsv(UUID outletId, String kind, LocalDate from, LocalDate to, Writer w) throws IOException {
        summary(outletId, from, to, "none"); // same range rules as the screen
        switch (kind) {
            case "summary" -> {
                row(w, "Channel", "Orders", "Gross sales", "Discount", "Taxable value", "CGST", "SGST", "Invoice value");
                for (var r : db.rows("""
                        select channel, sum(orders) as orders, sum(gross_sales) as g, sum(discount) as d, sum(taxable_value) as t, sum(cgst) as c, sum(sgst) as s, sum(total) as v
                          from pos.v_daily_sales where outlet_id = ? and business_date between ? and ? group by channel order by channel""", outletId, from, to)) {
                    row(w, r.get("channel"), r.get("orders"), r.get("g"), r.get("d"), r.get("t"), r.get("c"), r.get("s"), r.get("v"));
                }
            }
            case "daywise" -> {
                row(w, "Date", "Orders", "Gross sales", "Discount", "Taxable value", "CGST", "SGST", "Invoice value");
                for (var r : db.rows("""
                        select business_date as d, sum(orders) as orders, sum(gross_sales) as g, sum(discount) as disc, sum(taxable_value) as t, sum(cgst) as c, sum(sgst) as s, sum(total) as v
                          from pos.v_daily_sales where outlet_id = ? and business_date between ? and ? group by business_date order by business_date""", outletId, from, to)) {
                    row(w, r.get("d"), r.get("orders"), r.get("g"), r.get("disc"), r.get("t"), r.get("c"), r.get("s"), r.get("v"));
                }
            }
            case "itemwise" -> {
                row(w, "Dish", "Quantity", "Sales");
                for (var r : db.rows("select item_name as n, sum(quantity) as q, sum(sales) as s from pos.v_item_sales where outlet_id = ? and business_date between ? and ? group by item_name order by 3 desc", outletId, from, to)) {
                    row(w, r.get("n"), r.get("q"), r.get("s"));
                }
            }
            default -> throw ApiException.badRequest("kind must be summary, daywise or itemwise");
        }
        w.flush();
    }

    private static void row(Writer w, Object... cells) throws IOException {
        var sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            String s = cells[i] == null ? "" : cells[i].toString();
            if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(cells[i] instanceof Number)) s = "'" + s; // keep spreadsheets from running a dish name as a formula
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
            sb.append(i == 0 ? "" : ",").append(s);
        }
        w.write(sb.append("\r\n").toString());
    }
}
