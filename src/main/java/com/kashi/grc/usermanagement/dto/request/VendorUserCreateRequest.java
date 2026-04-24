package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

// ─────────────────────────────────────────────
// MODULE 1 — Auth
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// MODULE 2 — User Provisioning
// tenantId is NEVER accepted from the caller — resolved from JWT.
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// MODULE 3 — Role Management
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// MODULE 4 — ABAC
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// MODULE 5 — SoD
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// MODULE 6 — Delegation
// delegatorUserId resolved from JWT
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// MODULE 7 — Vendor Users
// ─────────────────────────────────────────────

@Data public class VendorUserCreateRequest {
    @NotNull          public Long   vendorId;
    @NotBlank @Email  public String email;
    @NotBlank         public String firstName;
    @NotBlank         public String lastName;
    public String     vendorRole;
    public boolean    isPrimaryContact;
    public String     phone;
    public boolean    sendInviteEmail = true;
}

// ─────────────────────────────────────────────
// MODULE 8 — Access Reviews
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// MODULE 9 — Audit Logs
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// MODULE 10 — Impersonation
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// Password Policy
// ─────────────────────────────────────────────

