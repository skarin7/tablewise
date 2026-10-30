package com.tablewise.reports;

import com.tablewise.common.Db;
import com.tablewise.domain.Repos.OutletRepo;
import com.tablewise.security.Principals.StaffPrincipal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
class ReportController {
    private final ReportService reports;
    private final Db db;
    private final OutletRepo outlets;

    ReportController(ReportService reports, Db db, OutletRepo outlets) {
        this.reports = reports;
        this.db = db;
        this.outlets = outlets;
    }

    @GetMapping("/reports/summary") @PreAuthorize("hasRole('OWNER')")
    Map<String, Object> summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(defaultValue = "none") String compare, @AuthenticationPrincipal StaffPrincipal me) {
        return reports.summary(me.outletId(), from, to, compare);
    }

    @GetMapping("/reports/export") @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<StreamingResponseBody> export(@RequestParam String kind, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @AuthenticationPrincipal StaffPrincipal me) {
        if (!java.util.Set.of("summary", "daywise", "itemwise").contains(kind)) throw com.tablewise.common.ApiException.badRequest("kind must be summary, daywise or itemwise");
        reports.summary(me.outletId(), from, to, "none"); // validate before the response starts
        StreamingResponseBody body = out -> reports.exportCsv(me.outletId(), kind, from, to, new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8));
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + kind + "-" + from + "-to-" + to + ".csv\"").body(body);
    }

    /** Dashboard: today against the same time yesterday, open orders, what needs attention, best sellers. */
    @GetMapping("/dashboard") @PreAuthorize("hasRole('OWNER')")
    Map<String, Object> dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @AuthenticationPrincipal StaffPrincipal me) {
        var tz = ZoneId.of(outlets.findById(me.outletId()).orElseThrow().getTimezone());
        LocalDate today = LocalDate.now(tz);
        LocalDate day = date == null ? today : date;
        String sales = """
                select count(*) as orders, coalesce(sum(b.total_amount), 0) as total
                  from pos.orders o join pos.bills b on b.order_id = o.id and b.status <> 'void'
                 where o.outlet_id = ? and o.business_date = ? and o.status <> 'cancelled'""";
        // a day in progress is compared with the same time yesterday, a finished day with the whole of the day before
        String upTo = day.equals(today) ? " and (o.placed_at at time zone ?)::time <= (now() at time zone ?)::time" : "";
        var now = db.rows(sales, me.outletId(), day).get(0);
        var before = day.equals(today) ? db.rows(sales + upTo, me.outletId(), day.minusDays(1), tz.getId(), tz.getId()).get(0) : db.rows(sales, me.outletId(), day.minusDays(1)).get(0);
        return Map.of(
                "date", day,
                "sales", Map.of("day", now, "sameTimeYesterday", before),
                "openOrders", db.rows("select status, count(*) as count from pos.orders where outlet_id = ? and status not in ('completed','cancelled') group by status", me.outletId()),
                "needsAttention", db.rows("select id, name, unit, current_stock, reorder_level, stock_state from pos.v_ingredient_status where outlet_id = ? and stock_state <> 'ok' order by stock_state desc, name", me.outletId()),
                "menuAvailability", db.rows("select stock_state, count(*) as count from pos.v_menu_availability where outlet_id = ? group by stock_state", me.outletId()),
                "topItems", db.rows("select item_name as name, sum(quantity) as quantity, sum(sales) as sales from pos.v_item_sales where outlet_id = ? and business_date = ? group by item_name order by 3 desc limit 5", me.outletId(), day));
    }

    /** Customers screen, plus how many orders had no customer. */
    @GetMapping("/customers") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    Map<String, Object> customers(@RequestParam(required = false) String q, @AuthenticationPrincipal StaffPrincipal me) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim() + "%";
        List<Map<String, Object>> rows = db.rows("select customer_id as id, name, phone, orders, total_spent, average_bill, last_order_at, favourite_item from pos.v_customer_stats where outlet_id = ? and (name ilike ? or phone like ?) order by total_spent desc, name limit 500",
                me.outletId(), like, like);
        Object walkIns = db.rows("select count(*) as n from pos.orders where outlet_id = ? and customer_id is null and status <> 'cancelled'", me.outletId()).get(0).get("n");
        return Map.of("customers", rows, "walkIns", walkIns);
    }
}
