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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralised error handler for all controllers.
 * Maps every exception type to a consistent ApiResponse<ErrorResponse> shape
 * so the UI always knows what to parse.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Show the underlying cause (exception type + message) next to the plain
     * message for EVERYONE — for a staging or test server. Platform
     * administrators always see it. Off by default: on production a customer
     * sees the plain message and a reference to quote.
     */
    @org.springframework.beans.factory.annotation.Value("${kashi.errors.show-details:false}")
    private boolean showDetailsToAll;

    /** Lazily, so the error handler never depends on security wiring at startup. */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.kashi.grc.common.security.PermissionGate> permissionGate;

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
        String raw = String.valueOf(ex.getMostSpecificCause().getMessage());
        log.warn("[DATA-INTEGRITY] Constraint violation: {}", raw);
        // Name what clashed instead of "a value is already in use" — the
        // database says which value and which column; that is what the person
        // needs to fix it. Only the value they typed and a column name are
        // shown, never SQL.
        String message;
        Matcher m;
        if ((m = DUPLICATE.matcher(raw)).find()) {
            message = "\"" + m.group(1) + "\" is already in use" + keyHint(m.group(2))
                    + ". It must be unique — use a different value.";
        } else if ((m = NOT_NULL.matcher(raw)).find()) {
            message = humanise(m.group(1)) + " is required.";
        } else if ((m = TOO_LONG.matcher(raw)).find()) {
            message = humanise(m.group(1)) + " is too long. Shorten it and try again.";
        } else if (raw.contains("Cannot delete or update a parent row")) {
            message = "This record is still used by other records, so it can't be removed or changed this way. "
                    + "Remove or reassign what uses it first.";
        } else if (raw.contains("Cannot add or update a child row")) {
            message = "This refers to a record that doesn't exist (it may have been deleted). Refresh and try again.";
        } else {
            message = "This conflicts with an existing record. Check your input and try again.";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(error("DATA_INTEGRITY_VIOLATION", message, ex)));
    }

    private static final Pattern DUPLICATE = Pattern.compile("Duplicate entry '(.*?)' for key '([^']*)'");
    private static final Pattern NOT_NULL  = Pattern.compile("Column '([^']*)' cannot be null");
    private static final Pattern TOO_LONG  = Pattern.compile("Data too long for column '([^']*)'");

    /** uk_vendor_tenant_registration_number → " (registration number)". */
    private static String keyHint(String key) {
        String k = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        k = k.replaceFirst("^(uk|uq|ux|unique|idx)_", "");
        if (k.isBlank() || k.equalsIgnoreCase("PRIMARY")) return "";
        return " (" + k.replace('_', ' ') + ")";
    }

    private static String humanise(String column) {
        String s = column.replace('_', ' ').trim();
        return s.isEmpty() ? "A value" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── Malformed request body ────────────────────────────────────
    // A wrong value for a dropdown/enum, a number where text was expected,
    // broken JSON. Was a 500 "unexpected error"; it is the caller's input.
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        Throwable c = ex.getMostSpecificCause();
        String message = "The request could not be read. Check the values entered and try again.";
        if (c instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException ife) {
            String field = path(ife.getPath());
            message = "\"" + ife.getValue() + "\" is not a valid value" + (field.isEmpty() ? "" : " for " + field) + ".";
        } else if (c instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException mie && !mie.getPath().isEmpty()) {
            message = "The value for " + path(mie.getPath()) + " has the wrong format.";
        }
        log.warn("[BAD-REQUEST] Unreadable body: {}", c.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(error("INVALID_REQUEST", message, ex)));
    }

    private static String path(java.util.List<com.fasterxml.jackson.databind.JsonMappingException.Reference> refs) {
        StringBuilder sb = new StringBuilder();
        for (var r : refs) {
            if (r.getFieldName() != null) { if (sb.length() > 0) sb.append('.'); sb.append(r.getFieldName()); }
            else if (r.getIndex() >= 0) sb.append('[').append(r.getIndex() + 1).append(']');
        }
        return sb.toString();
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(org.springframework.web.bind.MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("MISSING_PARAMETER", "Missing required value '" + ex.getParameterName() + "'."));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("METHOD_NOT_ALLOWED",
                        "This action isn't available here (" + ex.getMethod() + " is not supported). "
                                + "The page may be out of date — refresh and try again."));
    }

    // ── Bean validation outside @RequestBody (@Validated params, entity checks) ──
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(jakarta.validation.ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> {
            String p = String.valueOf(v.getPropertyPath());
            fields.put(p.contains(".") ? p.substring(p.lastIndexOf('.') + 1) : p, v.getMessage());
        });
        String first = fields.entrySet().stream().findFirst()
                .map(e -> humanise(e.getKey()) + ": " + e.getValue()).orElse("Some values are not valid.");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ErrorResponse.builder()
                        .code("VALIDATION_ERROR").message(first).fieldErrors(fields).build()));
    }

    // ── A record that is gone ─────────────────────────────────────
    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityGone(jakarta.persistence.EntityNotFoundException ex) {
        log.warn("[NOT-FOUND] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(error("NOT_FOUND",
                        "This record no longer exists — it may have been deleted. Refresh and try again.", ex)));
    }

    // ── Someone else saved first ──────────────────────────────────
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimistic(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        log.warn("[CONFLICT] Concurrent update: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("CONCURRENT_UPDATE",
                        "Someone else changed this at the same time. Refresh to see their change, then try again."));
    }

    // ── A step failed inside a larger save, and was hidden ────────
    // Some code catches an error from a step that runs in the same
    // transaction and carries on. The step has already doomed the save, so the
    // commit is refused here. Nothing was saved; say so, and leave a reference
    // that leads to the log line of the step that actually failed.
    @ExceptionHandler(org.springframework.transaction.UnexpectedRollbackException.class)
    public ResponseEntity<ApiResponse<Void>> handleRollback(org.springframework.transaction.UnexpectedRollbackException ex) {
        String ref = reference();
        log.error("[{}] Save rolled back after a step failed inside it — look for the WARN/ERROR just before this line: {}",
                ref, ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(error("SAVE_ROLLED_BACK",
                        "One step of this action failed, so nothing was saved. Reference " + ref
                                + " — share it with support so they can find the exact cause.", ex, ref)));
    }

    // ── Upload over the server's multipart limit ─────────────────
    // Thrown before any controller runs (photo uploads post through the
    // server). Unhandled, it reached the browser as a bare error or a reset
    // connection, and the upload just stopped with nothing on screen.
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        log.warn("[UPLOAD] Rejected over the multipart limit: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("FILE_TOO_LARGE",
                        "This file is larger than the server accepts for this upload. "
                                + "Photos can be up to 10 MB; larger ones are sent as files."));
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
    // Still the last resort, but never a dead end: the message says nothing
    // was saved, and carries a reference that is also on the log line with the
    // full stack trace — "ERR-7F3A2C" finds the cause in seconds.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        String ref = reference();
        log.error("[{}] Unexpected error: {}", ref, ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(error("INTERNAL_ERROR",
                        "Something went wrong on our side and this wasn't saved. Reference " + ref
                                + " — share it with support so they can find the exact cause.", ex, ref)));
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String reference() {
        return "ERR-" + Long.toHexString(System.nanoTime() ^ Double.doubleToLongBits(Math.random()))
                .toUpperCase().substring(0, 6);
    }

    private ErrorResponse error(String code, String message, Throwable ex) {
        return error(code, message, ex, null);
    }

    /**
     * The plain message, plus — for platform administrators, or everyone when
     * kashi.errors.show-details=true — the underlying cause, so whoever is
     * testing sees the real reason without opening the server log.
     */
    private ErrorResponse error(String code, String message, Throwable ex, String ref) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (ref != null) details.put("reference", ref);
        String shown = message;
        if (ex != null && showDetails()) {
            Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex);
            String cause = root.getClass().getSimpleName()
                    + (root.getMessage() == null ? "" : ": " + truncate(root.getMessage(), 300));
            details.put("cause", cause);
            shown = message + " [" + cause + "]";
        }
        return ErrorResponse.builder().code(code).message(shown)
                .details(details.isEmpty() ? null : details).build();
    }

    private boolean showDetails() {
        if (showDetailsToAll) return true;
        try {
            var gate = permissionGate.getIfAvailable();
            return gate != null && gate.isPlatform();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}