package com.kashi.grc.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Standard API response wrapper used by ALL controllers.
 * Ensures the UI always knows exactly what shape to expect.
 *
 * Success:  { status: "SUCCESS", data: {...} }
 * Error:    { status: "ERROR",   error: {...} }
 */
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private String status;
    private T data;
    private ErrorResponse error;
    private final LocalDateTime timestamp = LocalDateTime.now();

    // ── Factory: success ──────────────────────────────────────────
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> res = new ApiResponse<>();
        res.status = "SUCCESS";
        res.data = data;
        return res;
    }

    public static <T> ApiResponse<T> success() {
        ApiResponse<T> res = new ApiResponse<>();
        res.status = "SUCCESS";
        return res;
    }

    // ── Factory: warning ────────────────────────────────────────────
    public static <T> ApiResponse<T> warning(T data) {
        ApiResponse<T> res = new ApiResponse<>();
        res.status = "WARNING";
        res.data = data;
        return res;
    }

    // ── Factory: error ────────────────────────────────────────────
    public static <T> ApiResponse<T> error(ErrorResponse error) {
        ApiResponse<T> res = new ApiResponse<>();
        res.status = "ERROR";
        res.error = error;
        return res;
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return error(new ErrorResponse(code, message));
    }

    // ── Factory: special statuses (e.g., PASSWORD_RESET_REQUIRED) ─
    public static <T> ApiResponse<T> withStatus(String status, T data) {
        ApiResponse<T> res = new ApiResponse<>();
        res.status = status;
        res.data = data;
        return res;
    }
}
