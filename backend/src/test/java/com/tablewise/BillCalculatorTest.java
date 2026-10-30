package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;

import com.tablewise.orders.BillCalculator;
import com.tablewise.orders.BillCalculator.Line;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** README 7.2 arithmetic, no database needed. */
class BillCalculatorTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void plainGstBill() {
        var r = BillCalculator.calc(List.of(new Line(d("260"), 2), new Line(d("60"), 1)), null, null, d("0"), d("5"));
        assertThat(r.subtotal()).isEqualByComparingTo("580");
        assertThat(r.cgst()).isEqualByComparingTo("14.50");
        assertThat(r.sgst()).isEqualByComparingTo("14.50");
        assertThat(r.total()).isEqualByComparingTo("609");
        assertThat(r.roundOff()).isEqualByComparingTo("0");
    }

    @Test
    void percentDiscountServiceChargeAndRoundOff() {
        // 333 - 10 % = 299.70; service 10 % = 29.97; taxable 329.67; gst 5 % = 16.4835; exact 346.1535 -> 346
        var r = BillCalculator.calc(List.of(new Line(d("111"), 3)), "percent", d("10"), d("10"), d("5"));
        assertThat(r.discountAmount()).isEqualByComparingTo("33.30");
        assertThat(r.serviceChargeAmount()).isEqualByComparingTo("29.97");
        assertThat(r.taxableAmount()).isEqualByComparingTo("329.67");
        assertThat(r.cgst().add(r.sgst())).isEqualByComparingTo("16.48");
        assertThat(r.total()).isEqualByComparingTo("346");
        assertThat(r.roundOff()).isEqualByComparingTo("-0.15");
    }

    @Test
    void discountsAreCapped() {
        var flat = BillCalculator.calc(List.of(new Line(d("100"), 1)), "flat", d("500"), d("0"), d("5"));
        assertThat(flat.discountAmount()).isEqualByComparingTo("100");
        assertThat(flat.total()).isEqualByComparingTo("0");
        var pct = BillCalculator.calc(List.of(new Line(d("100"), 1)), "percent", d("250"), d("0"), d("5"));
        assertThat(pct.discountAmount()).isEqualByComparingTo("100");
    }
}
