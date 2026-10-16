package com.kashi.grc.billing.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Small, pure helpers for money and terms. No Spring, no state — easy to test.
 */
public final class Money {

    private Money() {}

    /** Average days per month (365.25 / 12). Used only to pro-rate a term remainder. */
    private static final double DAYS_PER_MONTH = 30.4375;

    /**
     * Length of a term in months, fractional for a partial month.
     * 2026-04-01 → 2027-04-01 is exactly 12.0.
     */
    public static double termMonths(LocalDate start, LocalDate endExclusive) {
        if (start == null || endExclusive == null || !endExclusive.isAfter(start)) return 1.0;
        long whole = ChronoUnit.MONTHS.between(start, endExclusive);
        LocalDate afterWhole = start.plusMonths(whole);
        long remDays = ChronoUnit.DAYS.between(afterWhole, endExclusive);
        double months = whole + remDays / DAYS_PER_MONTH;
        return Math.max(months, 1.0 / DAYS_PER_MONTH);
    }

    /** Monthly amount from a term total. Rounds half-up to the minor unit. */
    public static long monthly(long termTotalMinor, LocalDate start, LocalDate endExclusive) {
        return Math.round(termTotalMinor / termMonths(start, endExclusive));
    }

    public static String month(LocalDate d) {
        return YearMonth.from(d).toString();
    }

    /** Ratio as a percentage with one decimal, or null when the denominator is zero. */
    public static Double pct(double num, double den) {
        if (den == 0) return null;
        return Math.round(num * 1000.0 / den) / 10.0;
    }

    public static Double ratio(double num, double den) {
        if (den == 0) return null;
        return Math.round(num * 100.0 / den) / 100.0;
    }
}
