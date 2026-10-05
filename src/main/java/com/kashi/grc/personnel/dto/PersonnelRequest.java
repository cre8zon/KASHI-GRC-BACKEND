package com.kashi.grc.personnel.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Create (POST /v1/personnel) and generic update (PUT /v1/personnel/{id}).
 *
 * Field names match personnel_create_form, personnel_detail_header and
 * personnel_detail_tab_overview exactly.
 *
 * STATUS IS NOT WRITABLE HERE — the header form shows it, and every transition
 * has its own endpoint that records a timestamp and enforces legality.
 *
 * backgroundCheckStatus is not here either: it lives on the screening tab,
 * behind personnel:screen. Accepting it on the general update would route
 * round that permission entirely.
 */
@Getter
@Setter
public class PersonnelRequest {

    @NotBlank(message = "First name is required")
    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String lastName;

    @Email(message = "Work email must be a valid address")
    @Size(max = 255)
    private String workEmail;

    @Size(max = 50)
    private String employeeNumber;

    @Size(max = 30)
    private String personRef;

    @Size(max = 30)
    private String employmentType;

    /** Accepted only when equal to the current status. */
    private String status;

    @Size(max = 255)
    private String department;

    @Size(max = 255)
    private String jobTitle;

    private Long managerPersonnelId;

    @Size(max = 30)
    private String workLocation;

    @Size(max = 300)
    private String location;

    private Long userId;

    private Long vendorId;

    private LocalDate startDate;

    private Boolean hasPrivilegedAccess;

    // ── Sync provenance ───────────────────────────────────────────────────────
    @Size(max = 30)
    private String sourceSystem;

    @Size(max = 200)
    private String externalId;

    private Boolean syncPaused;

    // ── Scope ─────────────────────────────────────────────────────────────────
    private Boolean isInScope;

    @Size(max = 500)
    private String outOfScopeReason;

    /** TAG — arrives comma-separated. */
    @Size(max = 500)
    private String controlTags;

    @Size(max = 500)
    private String frameworkRefs;

    private String notes;
}
