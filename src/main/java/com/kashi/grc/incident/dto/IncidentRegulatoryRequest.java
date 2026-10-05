package com.kashi.grc.incident.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * PUT /v1/incidents/{id}/regulatory — the incident_detail_tab_regulatory form.
 *
 * Changing applicableFrameworks re-syncs IncidentNotification rows: a regime
 * added gets its clock, a regime removed has its row deleted ONLY if nothing
 * has been reported against it. A notification carrying a reference number is
 * evidence and is never deleted by an edit to a checkbox.
 */
@Getter
@Setter
public class IncidentRegulatoryRequest {

    /** MULTI_SELECT — array of framework_ref values. */
    private List<String> applicableFrameworks;

    private Boolean personalDataInvolved;

    @Size(max = 500)
    private String personalDataCategories;

    private Integer dataPrincipalsAffected;

    private String impactSummary;
}
