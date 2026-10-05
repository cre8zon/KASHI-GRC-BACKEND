package com.kashi.grc.training.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * POST /v1/training/courses/{id}/assign — the training_assign_form.
 *
 * Either a list of people or a department, or both. A department assignment
 * resolves to its in-scope, non-offboarded members at the moment it runs: a
 * joiner next month is not retrospectively assigned, which is correct — they
 * get it through onboarding instead.
 */
@Getter @Setter
public class TrainingAssignRequest {

    /**
     * Still accepted by the API for callers that can send an array.
     *
     * The FORM cannot populate it: DynamicForm's MULTI_SELECT renders from
     * config.components[optionsComponentKey] and ignores lookup_api_path
     * entirely, so a MULTI_SELECT pointed at /v1/personnel renders
     * "No options — add a UiComponent first". There is no multi-lookup field
     * type; see GAPS item 9. The form therefore sends personnelId, department
     * or assignAllInScope instead.
     */
    private List<Long> personnelIds;

    /** Single person, from a LOOKUP — what the form can actually render. */
    private Long personnelId;

    /**
     * Every in-scope, non-offboarded person in the tenant.
     *
     * The common case for mandatory awareness training, and the one that is
     * unusable when the picker cannot list people.
     */
    private Boolean assignAllInScope;

    private String department;

    /** Blank uses 30. */
    private Integer dueInDays;
}