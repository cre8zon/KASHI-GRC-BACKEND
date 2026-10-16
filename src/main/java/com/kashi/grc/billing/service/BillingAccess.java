package com.kashi.grc.billing.service;

/**
 * Platform permissions for Subscription & Billing. /v1/admin/** is already
 * SIDE_SYSTEM-only; these split what a platform user may do inside it, so a
 * Finance or Sales role can be built from permissions without a code change.
 *
 *   platform:billing:view    plans, subscriptions, invoices, metrics (read)
 *   platform:billing:manage  create / change plans, prices, features,
 *                            subscriptions, invoices and acquisition costs
 *
 * The expressions are compile-time constants for @PreAuthorize.
 */
public final class BillingAccess {
    private BillingAccess() {}

    public static final String PERM_VIEW   = "platform:billing:view";
    public static final String PERM_MANAGE = "platform:billing:manage";

    public static final String VIEW   = "hasAuthority('" + PERM_VIEW + "')";
    public static final String MANAGE = "hasAuthority('" + PERM_MANAGE + "')";
}
