package com.kashi.grc.common.config.web;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.ErrorResponse;
import com.kashi.grc.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralised error handler for all controllers.
 * Maps every exception type to a consistent ApiResponse<ErrorResponse> shape
 * so the UI always knows what to parse.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Validation (@Valid) ───────────────────────────────────────
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(err -> {
            String field = ((FieldError) err).getField();
            fieldErrors.put(field, err.getDefaultMessage());
        });
        ErrorResponse error = ErrorResponse.builder()
                .code("VALIDATION_ERROR")
                .message("Request validation failed")
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(error));
    }

    // ── Database constraint violations ────────────────────────────
    // A duplicate unique-key insert, or an FK violation, previously fell
    // through to the generic catch-all below and returned "An unexpected
    // error occurred" — the exact bug reported on tenant creation. This is
    // a backstop, not the primary UX: individual endpoints should still
    // pre-check and throw a specific BusinessException with a field-level
    // message (see TenantController.createTenant for the pattern) so the
    // user sees "Organization name already exists" instead of this generic
    // fallback. This handler exists for every OTHER duplicate/constraint
    // case across the app that doesn't have that pre-check yet — turns a
    // raw 500 into a clean 409 everywhere, in one place, instead of needing
    // a pre-check added to every single create/update endpoint individually.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("[DATA-INTEGRITY] Constraint violation: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA_INTEGRITY_VIOLATION",
                        "This action conflicts with an existing record (a value that must be unique is already in use). " +
                                "Please check your input and try again."));
    }

    // ── Business / domain exceptions ─────────────────────────────
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("Business exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(com.kashi.grc.common.dto.ErrorResponse.builder()
                        .code(ex.getErrorCode())
                        .message(ex.getMessage())
                        .details(ex.getDetails())   // null for the vast majority; @JsonInclude drops it
                        .build()));
    }

    // ── Spring Security ───────────────────────────────────────────
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("AUTH_INVALID_CREDENTIALS", "Invalid email or password"));
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiResponse<Void>> handleLocked(LockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(ApiResponse.error("AUTH_ACCOUNT_LOCKED", ex.getMessage()));
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleDisabled(DisabledException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("AUTH_ACCOUNT_INACTIVE", "Account is not active"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("RBAC_PERMISSION_DENIED", "You do not have permission to perform this action"));
    }

    // ── 404 ───────────────────────────────────────────────────────
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("ENDPOINT_NOT_FOUND", "Endpoint not found: " + ex.getRequestURL()));
    }

    // ── Browser disconnect / client abort ─────────────────────────
    // Fired when the browser cancels a slow request (tab close, navigation away,
    // or timeout) while the server is still writing the response. This is normal
    // user behavior — not a server error. Logging it as ERROR with a full stack
    // trace flooded the logs and buried real errors. Now logged at WARN, no trace.
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException ex) {
        log.warn("[CLIENT-ABORT] Browser disconnected mid-response: {}", ex.getMessage());
        // No response — the connection is already gone.
    }

    // ── Path variable type mismatch ───────────────────────────────
    // Happens when a URL segment that should be a Long ID contains a string
    // (e.g. /v1/assessments/vendor — "vendor" routed to an {assessmentId} Long param).
    // Was previously caught by the catch-all and logged as a full ERROR stack trace.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("[TYPE-MISMATCH] param='{}' value='{}'", ex.getName(), ex.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("INVALID_PARAMETER",
                        "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'"));
    }

    // ── Catch-all ─────────────────────────────────────────────────
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}