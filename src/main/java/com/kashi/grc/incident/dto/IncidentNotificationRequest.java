package com.kashi.grc.incident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /v1/incidents/{id}/notifications — the incident_notification_form,
 * opened by the INC_RECORD_NOTIFICATION action via __formKey.
 *
 * Upsert by (incidentId, frameworkRef): recording against a regime already on
 * the incident fills in its row rather than failing on the unique key, and
 * recording against one that was not listed adds it — which is what happens
 * when somebody realises mid-response that a second regulator also applies.
 */
@Getter
@Setter
public class IncidentNotificationRequest {

    @NotBlank(message = "Select which regime was notified")
    @Size(max = 50)
    private String frameworkRef;

    /** DATE or ISO datetime. Defaults to now when the form leaves it blank. */
    private String notifiedAt;

    @Size(max = 200)
    private String reference;

    private String notes;
}
