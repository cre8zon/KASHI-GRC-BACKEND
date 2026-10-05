package com.kashi.grc.incident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Create (POST /v1/incidents) and generic update (PUT /v1/incidents/{id}).
 *
 * Property names match the field_key values on incident_create_form,
 * incident_detail_header and incident_detail_tab_overview exactly.
 *
 * ── WHY applicableFrameworks IS A LIST AND controlTags IS A STRING ────────
 * DynamicForm joins TAG fields into a comma-separated string before submit,
 * but deliberately does NOT join MULTI_SELECT — its own comment says
 * "its consumers expect arrays". applicableFrameworks is a MULTI_SELECT, so it
 * arrives as a JSON array; controlTags and frameworkRefs are TAG fields and
 * arrive as strings. Getting this backwards produces a field that renders and
 * saves nothing, with no error on either side.
 *
 * STATUS IS NOT WRITABLE HERE. The header form shows it; submitting the value
 * already on screen is a no-op and changing it is refused. Every transition
 * has its own endpoint that records a timestamp and enforces legality.
 */
@Getter
@Setter
public class IncidentRequest {

    @NotBlank(message = "A short description of what happened is required")
    @Size(max = 500)
    private String title;

    private String description;

    @Size(max = 30)
    private String incidentRef;

    @Size(max = 50)
    private String incidentType;

    private String severity;

    /** Accepted only when equal to the current status. See class javadoc. */
    private String status;

    @Size(max = 50)
    private String detectionSource;

    @Size(max = 50)
    private String sourceModule;

    @Size(max = 50)
    private String sourceEntityType;

    private Long sourceEntityId;

    /** DATE or ISO datetime. Required on create — it starts every clock. */
    private String detectedAt;

    private String occurredAt;

    private Long ownerId;

    @Size(max = 200)
    private String ownerTeam;

    private Long reportedById;

    /** MULTI_SELECT — arrives as an array of framework_ref values. */
    private List<String> applicableFrameworks;

    private Boolean personalDataInvolved;

    /** TAG — arrives comma-separated. */
    @Size(max = 500)
    private String personalDataCategories;

    private Integer dataPrincipalsAffected;

    private String impactSummary;

    /** TAG — comma-separated. */
    @Size(max = 500)
    private String affectedServices;

    /** TAG — comma-separated. */
    @Size(max = 500)
    private String controlTags;

    /** TAG — comma-separated. */
    @Size(max = 500)
    private String frameworkRefs;

    /** Optional workflow blueprint to start on create. */
    private Long workflowId;
}
