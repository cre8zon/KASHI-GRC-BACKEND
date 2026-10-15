package com.kashi.grc.billing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts contract currencies into the one reporting currency the metrics use.
 *
 * ── WHY A PROPERTY AND NOT A LIVE FEED ────────────────────────────────────
 * Rates are applied in exactly one place that matters: when the month-end
 * snapshot is written, after which the converted figure is frozen. A live feed
 * would add a sub-processor and a failure mode for a number that is read once
 * a month. Update the property when the rate moves enough to matter.
 *
 *   kashi.billing.reporting-currency=INR
 *   kashi.billing.fx-rates=USD=84.0,EUR=91.0,GBP=108.0,AED=22.9,SGD=63.0
 *
 * Each rate is "1 unit of that currency = N units of the reporting currency".
 */
@Service
public class FxService {

    private final String reportingCurrency;
    private final Map<String, Double> rates = new HashMap<>();

    public FxService(
            @Value("${kashi.billing.reporting-currency:INR}") String reportingCurrency,
            @Value("${kashi.billing.fx-rates:USD=84.0,EUR=91.0,GBP=108.0,AED=22.9,SGD=63.0}") String fxRates) {
        this.reportingCurrency = reportingCurrency.trim().toUpperCase();
        for (String pair : fxRates.split(",")) {
            String[] kv = pair.split("=");
            if (kv.length != 2) continue;
            try {
                rates.put(kv[0].trim().toUpperCase(), Double.parseDouble(kv[1].trim()));
            } catch (NumberFormatException ignored) { /* bad entry is skipped, not fatal */ }
        }
        rates.put(this.reportingCurrency, 1.0);
    }

    public String reportingCurrency() { return reportingCurrency; }

    /** True when a rate is configured; an unknown currency must be visible, not silently 1:1. */
    public boolean supports(String currency) {
        return currency != null && rates.containsKey(currency.toUpperCase());
    }

    /**
     * Minor units in {@code currency} → minor units in the reporting currency.
     * Assumes both currencies use 2 decimal places (true for INR, USD, EUR, GBP,
     * AED, SGD). Throws for an unconfigured currency rather than guessing.
     */
    public long toReporting(long amountMinor, String currency) {
        if (currency == null) currency = reportingCurrency;
        Double r = rates.get(currency.toUpperCase());
        if (r == null) {
            throw new IllegalStateException("No FX rate configured for " + currency
                    + " — add it to kashi.billing.fx-rates");
        }
        return Math.round(amountMinor * r);
    }

    public Map<String, Double> rates() { return Map.copyOf(rates); }
}
