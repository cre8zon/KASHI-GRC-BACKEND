package com.kashi.grc.personnel.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detail payload for GET /v1/personnel/{id}.
 *
 * Property names are the field_key values from personnel_detail_header,
 * _tab_overview, _tab_employment and _tab_screening. UniversalModulePage reads
 * entity[field.fieldKey] directly, so a name that does not match renders blank
 * with no error anywhere.
 *
 * fullName, managerName, parentId and directReportCount are DERIVED. There is
 * no full_name column and there should not be one — it would drift from its
 * own parts on the first rename.
 */
@Getter
@Builder
public class PersonnelResponse {

    private Long    id;
    private String  personRef;
    private String  firstName;
    private String  lastName;

    /** Derived. The org chart and list both read this. */
    private String  fullName;

    private String  workEmail;
    private String  employeeNumber;
    private String  employmentType;
    private String  status;
    private String  department;
    private String  jobTitle;
    private String  workLocation;
    private String  location;

    // ── Relationships ─────────────────────────────────────────────────────────
    private Long    managerPersonnelId;
    private String  managerName;

    /**
     * EntityTreeView builds the hierarchy from parentId, not from
     * managerPersonnelId. Both are emitted: parentId for the tree,
     * managerPersonnelId for the overview form field of that name. One name
     * would break one of the two.
     */
    private Long    parentId;
    private Integer directReportCount;

    private Long    userId;
    private String  userAccountStatus;
    private Long    vendorId;
    private String  vendorName;

    // ── Sync provenance ───────────────────────────────────────────────────────
    private String  sourceSystem;
    private String  externalId;
    private LocalDateTime lastSyncedAt;
    private Boolean syncPaused;

    // ── Scope and compliance ──────────────────────────────────────────────────
    private Boolean isInScope;
    private String  outOfScopeReason;

    /** Derived on every read — COMPLIANT / GRACE_PERIOD / AT_RISK / NON_COMPLIANT / OUT_OF_SCOPE. */
    private String  complianceStatus;

    /** Requirement keys still outstanding, after active exclusions are subtracted. */
    private List<String> outstandingRequirements;

    private Boolean hasPrivilegedAccess;

    // ── Employment ────────────────────────────────────────────────────────────
    private LocalDate     startDate;
    private LocalDate     lastWorkingDay;
    private LocalDate     endDate;
    private LocalDateTime noticeGivenAt;
    private String        separationReason;
    private LocalDateTime offboardingStartedAt;
    private LocalDateTime offboardedAt;

    // ── Offboarding evidence ──────────────────────────────────────────────────
    private LocalDateTime accessRevokedAt;
    private LocalDateTime credentialsRotatedAt;
    private LocalDateTime exitInterviewAt;
    private String        offboardingEvidenceRef;

    /** True for an OFFBOARDED person with no recorded access revocation. */
    private Boolean       offboardingEvidenceMissing;

    /** Hours between offboarding starting and access being revoked. */
    private Double        hoursToRevokeAccess;

    // ── Screening ─────────────────────────────────────────────────────────────
    private String        backgroundCheckStatus;
    private LocalDateTime backgroundCheckCompletedAt;
    private String        backgroundCheckReference;
    private LocalDateTime ndaSignedAt;
    private LocalDateTime acceptableUseAcceptedAt;
    private LocalDateTime codeOfConductAcceptedAt;

    private String  controlTags;
    private String  frameworkRefs;
    private String  notes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long    createdBy;

    /** False once OFFBOARDED — the record becomes evidence. */
    private Boolean editable;

    // ── Children ──────────────────────────────────────────────────────────────
    private List<Exclusion> exclusions;
    private Integer         assetsOutstanding;

    /**
     * One compliance carve-out. Shaped for the generic LinkedEntitiesTab, which
     * reads id / ref / title / status / badge / linkNote.
     */
    @Getter
    @Builder
    public static class Exclusion {
        private Long    id;
        private String  ref;        // requirementKey
        private String  title;      // human label for the requirement
        private String  status;     // ACTIVE / EXPIRED / REVOKED
        private String  badge;      // expiry, or "Indefinite"
        private String  linkNote;   // the reason — what an auditor reads
        private LocalDateTime expiresAt;
        private LocalDateTime excludedAt;
        private String  excludedByName;
    }
}
