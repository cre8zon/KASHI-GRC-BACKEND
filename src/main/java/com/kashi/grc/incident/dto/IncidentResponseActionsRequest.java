package com.kashi.grc.incident.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * PUT /v1/incidents/{id}/response — the incident_detail_tab_response form.
 *
 * Named ...ActionsRequest rather than IncidentResponseRequest so it cannot be
 * confused with IncidentResponse, the outbound DTO. Two types differing only
 * by the word "Request" in a package where one is the payload and the other
 * the payload's reply is a reliable source of wrong imports.
 *
 * The lifecycle timestamps are editable here because they are frequently
 * reconstructed after the fact — nobody updates a tracker at 03:00 during a
 * live incident, and a timeline that can only be written forward would be
 * abandoned within one real event.
 */
@Getter
@Setter
public class IncidentResponseActionsRequest {

    private String occurredAt;
    private String detectedAt;
    private String triagedAt;

    private String containmentActions;
    private String eradicationActions;
    private String recoveryActions;
    private String rootCause;
    private String lessonsLearned;
}
