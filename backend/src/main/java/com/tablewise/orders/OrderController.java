package com.tablewise.orders;

import com.tablewise.common.Db;
import com.tablewise.security.Principals.StaffPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class OrderController {
    record ItemLine(@NotNull UUID menuItemId, @Min(1) int quantity, String notes) {}
    record PlaceOrder(@NotNull @Pattern(regexp = "dine_in|takeaway|delivery") String orderType, UUID tableId, UUID counterId, String customerName, String customerPhone,
                      String notes, @Pattern(regexp = "flat|percent") String discountType, @PositiveOrZero BigDecimal discountValue, @NotEmpty @Valid List<ItemLine> items) {}
    record Cancel(String reason) {}

    private final OrderService orders;
    private final Db db;

    OrderController(OrderService orders, Db db) {
        this.orders = orders;
        this.db = db;
    }

    @PostMapping("/orders") @PreAuthorize("hasAnyRole('OWNER','CASHIER')") @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> place(@Valid @RequestBody PlaceOrder r, @AuthenticationPrincipal StaffPrincipal me) {
        var c = orders.create(me.outletId(), me.staffId(), new OrderService.Spec(r.orderType(), "pos", r.tableId(), r.counterId(), r.customerName(), r.customerPhone(), r.notes(),
                r.discountType(), r.discountValue(), r.items().stream().map(i -> new OrderService.Line(i.menuItemId(), i.quantity(), i.notes())).toList()));
        return orders.board(me, null, null, false, null, c.order().getId()).get(0);
    }

    @GetMapping("/orders")
    List<Map<String, Object>> board(@RequestParam(required = false) String status, @RequestParam(required = false) String type,
                                    @RequestParam(defaultValue = "false") boolean unpaid, @RequestParam(required = false) String q, @AuthenticationPrincipal StaffPrincipal me) {
        return orders.board(me, status, type, unpaid, q, null);
    }

    @PostMapping("/orders/{id}/advance")
    void advance(@PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal me) { orders.advance(id, me); }

    @PostMapping("/orders/{id}/cancel")
    void cancel(@PathVariable UUID id, @RequestBody Cancel c, @AuthenticationPrincipal StaffPrincipal me) { orders.cancel(id, c.reason(), me); }

    /** POS table picker: busy while an order is open or unpaid. */
    @GetMapping("/tables") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    List<Map<String, Object>> tables(@AuthenticationPrincipal StaffPrincipal me) {
        return db.rows("select o.table_id as id, t.label, t.seats, t.area, o.is_busy as busy from pos.v_table_occupancy o join pos.dining_tables t on t.id = o.table_id where o.outlet_id = ? order by t.sort_order, t.label", me.outletId());
    }

    @GetMapping("/counters") @PreAuthorize("hasAnyRole('OWNER','CASHIER')")
    List<Map<String, Object>> counters(@AuthenticationPrincipal StaffPrincipal me) {
        return db.rows("select id, name from pos.counters where outlet_id = ? and is_active order by sort_order, name", me.outletId());
    }
}
