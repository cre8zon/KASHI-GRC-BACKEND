package com.kashi.grc.incident.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detail payload for GET /v1/incidents/{id}.
 *
 * Property names are the field_key values from incident_detail_header,
 * _tab_overview, _tab_response and _tab_regulatory. UniversalModulePage reads
 * entity[field.fieldKey] directly, so a name that does not match renders blank
 * with no error anywhere.
 *
 * applicableFrameworks goes OUT as a List because the form field is a
 * MULTI_SELECT and its renderer compares against array members. The column is
 * a comma-separated string; the service splits it here. Sending the raw string
 * would render an empty control over stored data, which looks like data loss.
 */
@Getter
@Builder
public class IncidentResponse {

    private Long    id;
    private String  incidentRef;
    private String  title;
    private String  description;
    private String  incidentType;
    private String  severity;
    private String  status;

    // ── Provenance ────────────────────────────────────────────────────────────
    private String  sourceModule;
    private String  sourceEntityType;
    private Long    sourceEntityId;
    private String  detectionSource;

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    private LocalDateTime occurredAt;
    private LocalDateTime detectedAt;
    private LocalDateTime triagedAt;
    private LocalDateTime containedAt;
    private LocalDateTime eradicatedAt;
    private LocalDateTime recoveredAt;
    private LocalDateTime closedAt;

    /** Hours from occurrence to detection. Null unless both are recorded. */
    private Double  timeToDetectHours;

    /** Hours from detection to containment. Null unless both are recorded. */
    private Double  timeToContainHours;

    // ── Internal SLA ──────────────────────────────────────────────────────────
    private LocalDateTime responseDueAt;
    private LocalDateTime resolutionDueAt;
    private Boolean slaBreached;

    // ── Regulatory ────────────────────────────────────────────────────────────

    /** List, not a comma string — see class javadoc. */
    private List<String> applicableFrameworks;

    private Boolean personalDataInvolved;
    private String  personalDataCategories;
    private Integer dataPrincipalsAffected;

    /** Earliest outstanding notification deadline across every regime. */
    private LocalDateTime reportingDueAt;

    /** True when any notification is past its deadline and unreported. */
    private Boolean reportingOverdue;

    private Integer notificationsOutstanding;

    // ── Impact and response ───────────────────────────────────────────────────
    private String  impactSummary;
    private String  affectedServices;
    private String  containmentActions;
    private String  eradicationActions;
    private String  recoveryActions;
    private String  rootCause;
    private String  lessonsLearned;
    private LocalDateTime pirCompletedAt;

    // ── Ownership ─────────────────────────────────────────────────────────────
    private Long    ownerId;
    private String  ownerName;
    private String  ownerTeam;
    private Long    reportedById;
    private String  reportedByName;

    private String  controlTags;
    private String  frameworkRefs;
    private Long    workflowInstanceId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long    createdBy;

    /**
     * False once CLOSED. UniversalModulePage drops the collaboration surface
     * when this is false. A closed incident stays readable as evidence; reopen
     * it if it needs to change.
     */
    private Boolean editable;

    // ── Children ──────────────────────────────────────────────────────────────
    private List<Notification> notifications;

    /**
     * One regulatory obligation. Shaped for the generic LinkedEntitiesTab,
     * which reads id / ref / title / status / badge / linkNote.
     */
    @Getter
    @Builder
    public static class Notification {
        private Long    id;
        private String  ref;            // framework_ref
        private String  title;          // authority name
        private String  status;         // REPORTED / OVERDUE / DUE / NO_DEADLINE
        private String  badge;          // the deadline, or when it was reported
        private String  linkNote;       // reference number and notes
        private LocalDateTime dueAt;
        private LocalDateTime notifiedAt;
        private String  reference;
    }
}
