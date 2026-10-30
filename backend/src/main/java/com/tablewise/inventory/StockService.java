package com.tablewise.inventory;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.tablewise.common.ApiException;
import com.tablewise.realtime.Realtime;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The only writer of ingredient stock: every change is a stock_movements row and the DB trigger applies it (README 7.3). */
@Service
public class StockService {
    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final JdbcTemplate jdbc;
    private final Realtime realtime;

    StockService(JdbcTemplate jdbc, Realtime realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    /** Inserts one movement and returns the new stock. Throws "Not enough X" when the CHECK (stock >= 0) refuses it. */
    public BigDecimal move(UUID outletId, UUID ingredientId, BigDecimal delta, String reason, UUID orderId, UUID by, String note, BigDecimal unitCost) {
        // name and reorder level first: after a failed statement the transaction cannot be queried
        var ing = jdbc.query("select name, reorder_level from pos.ingredients where id=? and outlet_id=? and deleted_at is null",
                (rs, i) -> new Object[] {rs.getString(1), rs.getBigDecimal(2)}, ingredientId, outletId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Ingredient not found"));
        BigDecimal after;
        try {
            after = jdbc.queryForObject("""
                    insert into pos.stock_movements(outlet_id, ingredient_id, quantity_delta, reason, order_id, created_by, note, unit_cost)
                    values (?,?,?,?,?,?,?,?) returning stock_after""",
                    BigDecimal.class, outletId, ingredientId, delta, reason, orderId, by, note, unitCost);
        } catch (DataIntegrityViolationException e) {
            log.warn("not enough stock: ingredient {} ({}), wanted {}", ing[0], ingredientId, delta.negate());
            throw ApiException.conflict("Not enough " + ing[0].toString().toLowerCase() + " in stock");
        }
        if (!reason.startsWith("order_")) log.info("stock movement: ingredient {} {} {} -> {}", ing[0], reason, delta, after);
        if (delta.signum() < 0 && after.compareTo((BigDecimal) ing[1]) <= 0) {
            log.warn("stock {}: ingredient {} is at {}", after.signum() <= 0 ? "out" : "low", ing[0], after);
            realtime.inventory(outletId, ingredientId, (String) ing[0], after.signum() <= 0 ? "out" : "low");
        }
        return after;
    }
}
