package com.tablewise.orders;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Order numbers and GST invoice numbers, both allocated atomically inside the caller's transaction (README 7.7). */
@Service
public class NumberingService {
    private final JdbcTemplate jdbc;

    NumberingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int nextOrderNo(UUID outletId) {
        return jdbc.queryForObject("update pos.outlets set next_order_no = next_order_no + 1 where id = ? returning next_order_no - 1", Integer.class, outletId);
    }

    /** {prefix}/{yy-yy}/{000000}, e.g. SAF/26-27/000123, 16 characters. Consecutive per outlet and financial year (April to March). */
    public String nextInvoiceNo(UUID outletId, String outletName, LocalDate businessDate) {
        int start = businessDate.getMonthValue() >= 4 ? businessDate.getYear() : businessDate.getYear() - 1;
        String fy = start + "-" + String.format("%02d", (start + 1) % 100);
        String prefix = outletName.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        prefix = prefix.isEmpty() ? "INV" : prefix.substring(0, Math.min(3, prefix.length()));
        jdbc.update("insert into pos.invoice_sequences(outlet_id, financial_year, prefix) values (?,?,?) on conflict do nothing", outletId, fy, prefix);
        var row = jdbc.queryForMap("update pos.invoice_sequences set next_number = next_number + 1 where outlet_id = ? and financial_year = ? returning prefix, next_number - 1 as n",
                outletId, fy);
        return String.format("%s/%02d-%02d/%06d", row.get("prefix"), start % 100, (start + 1) % 100, ((Number) row.get("n")).intValue());
    }
}
