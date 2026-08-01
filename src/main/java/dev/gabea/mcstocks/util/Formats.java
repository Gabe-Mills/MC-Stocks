package dev.gabea.mcstocks.util;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

public final class Formats {
    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.US);
    private static final DecimalFormat QUANTITY = new DecimalFormat("#,##0.####");
    private static final DecimalFormat PERCENT = new DecimalFormat("+#,##0.00;-#,##0.00");

    private Formats() {
    }

    public static String money(double value) {
        return MONEY.format(value);
    }

    public static String quantity(double value) {
        return QUANTITY.format(value);
    }

    public static String percent(double value) {
        return PERCENT.format(value) + "%";
    }

    public static String rate(double value) {
        return QUANTITY.format(value) + "%";
    }
}
