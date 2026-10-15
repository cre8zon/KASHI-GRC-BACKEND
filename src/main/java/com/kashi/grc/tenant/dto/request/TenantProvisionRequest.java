package com.kashi.grc.tenant.dto.request;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Everything the Create Tenant wizard collects, posted once.
 *
 * Field names match the ui_form_fields keys of the tenant_create form, so the
 * DB-driven wizard posts this shape directly (admin.* are dotted keys, which
 * DynamicForm nests into the admin object).
 */
@Data
public class TenantProvisionRequest {

    // ── Organisation ─────────────────────────────────────────────────────
    private String name;
    private String code;
    private String legalName;
    private String description;
    private String country;
    private String industry;
    private String employeeBand;

    // ── First administrator ──────────────────────────────────────────────
    private Admin admin;

    // ── Commercial ───────────────────────────────────────────────────────
    /** billing_plans.code */
    private String plan;
    /** TRIAL | ACTIVE */
    private String startAs;
    /** Whole-term contract value in MAJOR units (rupees). Converted to minor units server-side. */
    private BigDecimal contractValue;
    private String currency;
    /** Term length for a paid start. Default 12. */
    private Integer termMonths;
    /** MONTHLY | QUARTERLY | ANNUAL | MULTI_YEAR */
    private String billingInterval;
    private String salesMotion;
    private String acquisitionChannel;

    /** Opens an audit-firm verification draft. Never sets is_audit_firm. */
    private Boolean isAuditFirm;

    /**
     * The website enquiry this tenant was converted from, when created from the
     * Enquiries inbox. Published on TenantProvisionedEvent; the enquiry module
     * marks it converted inside the same transaction.
     */
    private Long enquiryId;

    @Data
    public static class Admin {
        private String firstName;
        private String lastName;
        private String email;
        private String jobTitle;
    }
}
