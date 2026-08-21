package com.kashi.grc.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for all business/domain exceptions.
 * Carry an error code and HTTP status so GlobalExceptionHandler
 * can map them automatically without any if-else chains.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    /**
     * Optional machine-readable context for the caller, surfaced in
     * ErrorResponse.details.
     *
     * Added so a cross-tenant refusal can name the tenant that DOES own the
     * record. The frontend was otherwise reduced to guessing — searching the
     * task inbox, or asking the user to pick from their client list, which for
     * a firm with ten clients is a quiz. The server already knows the answer at
     * the moment it refuses.
     *
     * Only ever populated when the caller demonstrably holds a usable membership
     * in that tenant, so it tells them nothing they were not already entitled to.
     */
    private final java.util.Map<String, Object> details;

    public BusinessException(String errorCode, String message, HttpStatus httpStatus) {
        this(errorCode, message, httpStatus, null);
    }

    public BusinessException(String errorCode, String message, HttpStatus httpStatus,
                             java.util.Map<String, Object> details) {
        super(message);
        this.errorCode  = errorCode;
        this.httpStatus = httpStatus;
        this.details    = details;
    }

    public BusinessException(String errorCode, String message) {
        this(errorCode, message, HttpStatus.BAD_REQUEST);
    }
}