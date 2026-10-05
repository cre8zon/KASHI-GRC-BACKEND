package com.kashi.grc.risk.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Create (POST /v1/risks) and generic update (PUT /v1/risks/{id}).
 *
 * Field names match the field_key values on risk_create_form,
 * risk_detail_header and risk_detail_tab_overview exactly. A rename on either
 * side silently stops that field saving, so they are listed here in seed order
 * to make a diff obvious.
 *
 * STATUS IS NOT A WRITABLE FIELD HERE — see RiskService.update. The header form
 * carries a status dropdown for display, and submitting the value it is already
 * showing is a no-op. Submitting a DIFFERENT value is refused, because every
 * real transition has its own endpoint, its own permission and its own side
 * effects (timestamps, acceptance record, workflow advance). Allowing the
 * generic PUT to set status would route around all three.
 */
@Getter
@Setter
public class RiskRequest {

    @NotBlank(message = "Risk title is required")
    @Size(max = 500)
    private String title;

    private String description;

    @Size(max = 50)
    private String category;

    @Size(max = 50)
    private String riskSource;

    /** Accepted only when equal to the current status. See class javadoc. */
    private String status;

    @Size(max = 30)
    private String riskRef;

    private Long ownerId;

    @Size(max = 200)
    private String ownerTeam;

    /** TAG field — DynamicForm joins the array to a comma-separated string. */
    @Size(max = 500)
    private String controlTags;

    /** TAG field — comma-separated. */
    @Size(max = 500)
    private String frameworkRefs;

    @Min(1) @Max(5)
    private Integer inherentLikelihood;

    @Min(1) @Max(5)
    private Integer inherentImpact;

    private LocalDate nextReviewDate;

    @Min(1) @Max(120)
    private Integer reviewFrequencyMonths;

    /**
     * Optional workflow blueprint to start on create. Null means no workflow —
     * unlike ISSUE, a risk is useful without one, so this logs at debug rather
     * than warn and the risk is created either way.
     */
    private Long workflowId;
}
