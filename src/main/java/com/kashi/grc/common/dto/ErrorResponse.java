package com.kashi.grc.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Consistent error payload returned inside ApiResponse for all failures.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    /** Machine-readable code, e.g. AUTH_ACCOUNT_LOCKED, RESOURCE_NOT_FOUND */
    private String code;

    /** Human-readable message */
    private String message;

    /** Validation field errors: { "email": "must not be blank" } */
    private Map<String, String> fieldErrors;

    /** Any extra context the caller might need */
    private Map<String, Object> details;

    /** Convenience constructor used by ApiResponse.error(code, message) */
    public ErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
