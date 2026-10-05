package com.kashi.grc.personnel.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A person on the roster — ISO 27001 A.6.1, A.6.2, A.6.5, A.5.11. UCF domain HRS.
 *
 * ── THIS IS NOT THE users TABLE ───────────────────────────────────────────
 * users is login credentials that happen to carry department and job_title.
 * A roster is different in three ways that matter:
 *
 *   * contractors and vendor staff who never get a login still need screening
 *     records and asset assignments
 *   * the record must OUTLIVE the account, because "did this leaver hand
 *     everything back" is asked after the user row is gone
 *   * employment has dates and a lifecycle; a login has a status
 *
 * userId is therefore NULLABLE and deleting the user does not delete the
 * person. That is the whole design, not an oversight.
 *
 * ── SOFT DELETE, AND WHY IT IS BARELY USED ────────────────────────────────
 * isDeleted comes from AuditableEntity. deletedAt, deletedBy and
 * deletionReason are declared here, following FeatureFlag — the one entity in
 * this codebase that already records who removed something and when. For a
 * people record that IS the audit evidence, so a bare flag is not enough.
 *
 * But deletion is scoped to records created in error. PersonnelService refuses
 * to delete anyone who has ever been ACTIVE: their offboarding evidence is the
 * artifact an auditor tests, and removing it is the opposite of the point.
 * People become OFFBOARDED and stay.
 *
 * ── WHAT IS DELIBERATELY ABSENT ───────────────────────────────────────────
 * No date of birth, home address, salary, national ID or emergency contact.
 * None of it evidences A.6, and a GRC platform that quietly becomes an HRIS
 * inherits an HRIS's breach exposure. The screening record holds an outcome
 * and a reference, never the report itself.
 */
@Entity
@Table(
        name = "personnel",
        uniqueConstraints = {
                // MySQL treats NULLs as distinct in a unique key, so every
                // hand-entered row (externalId NULL) coexists happily. This
                // only bites where it should: two syncs of the same record.
                @UniqueConstraint(name = "uq_personnel_external",
                                  columnNames = {"tenant_id", "source_system", "external_id"})
        },
        indexes = {
                @Index(name = "idx_per_tenant",     columnList = "tenant_id"),
                @Index(name = "idx_per_status",     columnList = "status"),
                @Index(name = "idx_per_user",       columnList = "user_id"),
                @Index(name = "idx_per_manager",    columnList = "manager_personnel_id"),
                @Index(name = "idx_per_vendor",     columnList = "vendor_id"),
                @Index(name = "idx_per_type",       columnList = "employment_type"),
                @Index(name = "idx_per_privileged", columnList = "has_privileged_access"),
                @Index(name = "idx_per_bgc",        columnList = "background_check_status"),
                @Index(name = "idx_per_scope",      columnList = "tenant_id,is_in_scope"),
                @Index(name = "idx_per_source",     columnList = "source_system"),
                @Index(name = "idx_per_dates",      columnList = "start_date,end_date"),
                @Index(name = "idx_per_offb",       columnList = "tenant_id,status,access_revoked_at"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Personnel extends AuditableEntity {

    @Column(name = "person_ref", length = 30)
    private String personRef;

    /** Nullable: a contractor with no platform account is still personnel. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "work_email", length = 255)
    private String workEmail;

    @Column(name = "employee_number", length = 50)
    private String employeeNumber;

    @Column(name = "employment_type", length = 30)
    private String employmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.PENDING_START;

    @Column(name = "department", length = 255)
    private String department;

    @Column(name = "job_title", length = 255)
    private String jobTitle;

    /** Self-referencing. Drives the org chart via blueprint supports_tree. */
    @Column(name = "manager_personnel_id")
    private Long managerPersonnelId;

    @Column(name = "work_location", length = 30)
    private String workLocation;

    @Column(name = "location", length = 300)
    private String location;

    /** vendors.id — contractors engaged through a supplier. */
    @Column(name = "vendor_id")
    private Long vendorId;

    // ── Sync provenance ───────────────────────────────────────────────────────

    /** MANUAL / HRIS / IDP / IMPORT. */
    @Column(name = "source_system", nullable = false, length = 30)
    @Builder.Default
    private String sourceSystem = "MANUAL";

    /** The key in that system. What makes a re-sync update rather than duplicate. */
    @Column(name = "external_id", length = 200)
    private String externalId;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /**
     * Set when someone hand-corrects a synced record. A sync must skip a paused
     * row rather than silently reverting the correction on its next run.
     */
    @Column(name = "sync_paused", nullable = false)
    @Builder.Default
    private boolean syncPaused = false;

    // ── Compliance scope ──────────────────────────────────────────────────────

    /** Whole-person scope. A per-requirement carve-out is a PersonnelExclusion. */
    @Column(name = "is_in_scope", nullable = false)
    @Builder.Default
    private boolean isInScope = true;

    @Column(name = "out_of_scope_reason", length = 500)
    private String outOfScopeReason;

    // ── Employment lifecycle ──────────────────────────────────────────────────

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "last_working_day")
    private LocalDate lastWorkingDay;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "notice_given_at")
    private LocalDateTime noticeGivenAt;

    /** VOLUNTARY / INVOLUNTARY / END_OF_CONTRACT / RETIREMENT / REDUNDANCY / NEVER_STARTED. */
    @Column(name = "separation_reason", length = 30)
    private String separationReason;

    @Column(name = "offboarding_started_at")
    private LocalDateTime offboardingStartedAt;

    @Column(name = "offboarded_at")
    private LocalDateTime offboardedAt;

    // ── Offboarding evidence (A.6.5, A.5.11) ──────────────────────────────────

    /** The timestamp auditors ask for by name. */
    @Column(name = "access_revoked_at")
    private LocalDateTime accessRevokedAt;

    /** Matters most for privileged holders and involuntary exits. */
    @Column(name = "credentials_rotated_at")
    private LocalDateTime credentialsRotatedAt;

    @Column(name = "exit_interview_at")
    private LocalDateTime exitInterviewAt;

    @Column(name = "offboarding_evidence_ref", length = 300)
    private String offboardingEvidenceRef;

    // ── Screening and agreements (A.6.1, A.6.2) ───────────────────────────────

    @Column(name = "background_check_status", nullable = false, length = 30)
    @Builder.Default
    private String backgroundCheckStatus = "NOT_STARTED";

    @Column(name = "background_check_completed_at")
    private LocalDateTime backgroundCheckCompletedAt;

    /** Vendor case number. Never the report. */
    @Column(name = "background_check_reference", length = 200)
    private String backgroundCheckReference;

    @Column(name = "nda_signed_at")
    private LocalDateTime ndaSignedAt;

    @Column(name = "acceptable_use_accepted_at")
    private LocalDateTime acceptableUseAcceptedAt;

    @Column(name = "code_of_conduct_accepted_at")
    private LocalDateTime codeOfConductAcceptedAt;

    @Column(name = "has_privileged_access", nullable = false)
    @Builder.Default
    private boolean hasPrivilegedAccess = false;

    @Column(name = "control_tags", length = 500)
    private String controlTags;

    @Column(name = "framework_refs", length = 500)
    private String frameworkRefs;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ── Soft delete detail (isDeleted is inherited) ───────────────────────────

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "deletion_reason", length = 500)
    private String deletionReason;

    // ── Enums ─────────────────────────────────────────────────────────────────

    /**
     * Must stay identical to module_blueprints.status_flow_json for PERSONNEL
     * and to allowed_statuses_json on each ui_actions row.
     *
     * OFFBOARDED is terminal — there is no rehire transition. A rehire is a new
     * employment period: reusing the row would make startDate a lie and would
     * overwrite the first period's offboarding evidence, which is precisely the
     * record an auditor asks for.
     */
    public enum Status {
        PENDING_START, ACTIVE, ON_LEAVE, NOTICE_PERIOD, OFFBOARDING, OFFBOARDED
    }

    /** Derived by PersonnelService, never stored. See computeComplianceStatus. */
    public enum ComplianceStatus {
        COMPLIANT, GRACE_PERIOD, AT_RISK, NON_COMPLIANT, OUT_OF_SCOPE
    }

    @Transient
    public String getFullName() {
        return (lastName == null || lastName.isBlank()) ? firstName : firstName + " " + lastName;
    }

    /** True once the person has held a real employment period. */
    @Transient
    public boolean hasEmploymentHistory() {
        return status != Status.PENDING_START || startDate != null || offboardedAt != null;
    }
}
