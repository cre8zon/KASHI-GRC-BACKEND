package com.kashi.grc.common.util;

/**
 * Platform-wide constants.
 * Centralised here so no magic strings are scattered across the codebase.
 */
public final class Constants {

    private Constants() {}

    // ── API ───────────────────────────────────────────────────────
    public static final String API_PREFIX      = "/v1";
    public static final int    DEFAULT_PAGE     = 0;       // 0-indexed for Spring Data
    public static final int    DEFAULT_PAGE_SIZE = 50;
    public static final int    MAX_PAGE_SIZE     = 200;

    // ── Security ──────────────────────────────────────────────────
    public static final int    MAX_FAILED_ATTEMPTS  = 5;
    public static final int    LOCKOUT_MINUTES       = 30;
    public static final int    RESET_TOKEN_TTL_MINS  = 15;
    public static final int    PASSWORD_HISTORY_DEPTH = 5;
    public static final int    PASSWORD_EXPIRY_DAYS  = 90;

    // ── Roles: side values ────────────────────────────────────────
    public static final String ROLE_SIDE_SYSTEM       = "SYSTEM";
    public static final String ROLE_SIDE_ORGANIZATION = "ORGANIZATION";
    public static final String ROLE_SIDE_VENDOR       = "VENDOR";

    // ── Status values ─────────────────────────────────────────────
    public static final String STATUS_ACTIVE    = "ACTIVE";
    public static final String STATUS_INACTIVE  = "INACTIVE";
    public static final String STATUS_LOCKED    = "LOCKED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    // ── Header keys ───────────────────────────────────────────────
    public static final String TENANT_HEADER   = "X-Tenant-ID";
    public static final String AUTH_HEADER     = "Authorization";
    public static final String BEARER_PREFIX   = "Bearer ";
}
