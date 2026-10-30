package com.tablewise.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** README 7.2, the same arithmetic as the prototype's calc(). Totals from the client are never trusted. */
public final class BillCalculator {
    private BillCalculator() {}

    public record Line(BigDecimal unitPrice, int quantity) {}

    public record Result(BigDecimal subtotal, String discountType, BigDecimal discountValue, BigDecimal discountAmount, BigDecimal serviceChargeAmount,
                         BigDecimal taxableAmount, BigDecimal cgst, BigDecimal sgst, BigDecimal roundOff, BigDecimal total) {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** discountType is "flat", "percent" or null. */
    public static Result calc(List<Line> lines, String discountType, BigDecimal discountValue, BigDecimal servicePct, BigDecimal gstPct) {
        BigDecimal subtotal = lines.stream().map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal value = discountValue == null ? BigDecimal.ZERO : discountValue;
        BigDecimal discount = BigDecimal.ZERO;
        if (discountType != null && value.signum() > 0) {
            discount = "percent".equals(discountType)
                    ? subtotal.multiply(value.min(HUNDRED)).divide(HUNDRED, 6, RoundingMode.HALF_UP)
                    : value.min(subtotal);
        }
        discount = round2(discount);
        BigDecimal base = subtotal.subtract(discount);
        BigDecimal service = round2(base.multiply(servicePct).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        BigDecimal taxable = base.add(service);
        BigDecimal gst = taxable.multiply(gstPct).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal cgst = round2(gst.divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP));
        BigDecimal sgst = round2(gst).subtract(cgst);
        BigDecimal exact = taxable.add(gst);
        BigDecimal total = exact.setScale(0, RoundingMode.HALF_UP).setScale(2);
        return new Result(subtotal.setScale(2), discount.signum() > 0 ? discountType : null, discount.signum() > 0 ? value : BigDecimal.ZERO, discount, service, taxable, cgst, sgst,
                round2(total.subtract(exact)), total);
    }

    static BigDecimal round2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
}
