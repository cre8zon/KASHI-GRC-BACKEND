package com.kashi.grc.ai.provider;

import com.kashi.grc.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a provider call fails in a way the caller must know about.
 *
 * Extends BusinessException so GlobalExceptionHandler maps it without a special
 * case, and carries `retryable` so LlmProviderRegistry can distinguish a 429 or
 * 503 (back off and try again) from a 400 or 401 (trying again just burns
 * latency and produces the same error).
 *
 * ── TWO MESSAGES ─────────────────────────────────────────────────────────────
 * getMessage() is what the user sees: GlobalExceptionHandler puts it straight
 * into the toast. It names no vendor and carries no provider response — a raw
 * Google JSON body in a toast is both unreadable and a sub-processor leak.
 * getDetail() keeps the technical story (vendor, HTTP status, response body)
 * for the logs and the ai_interactions row.
 */
public class AiProviderException extends BusinessException {

    private final boolean retryable;
    private final String  detail;

    public AiProviderException(String errorCode, String message, HttpStatus status, boolean retryable) {
        this(errorCode, message, status, retryable, message);
    }

    public AiProviderException(String errorCode, String userMessage, HttpStatus status,
                               boolean retryable, String detail) {
        super(errorCode, userMessage, status);
        this.retryable = retryable;
        this.detail    = detail;
    }

    public boolean isRetryable() { return retryable; }

    /** Technical detail for logs and the interaction record — never shown to users. */
    public String getDetail() { return detail != null ? detail : getMessage(); }

    public static AiProviderException rateLimited(String provider) {
        return new AiProviderException("AI_RATE_LIMITED",
                "The AI assistant is handling a lot of requests right now. Please try again in a minute.",
                HttpStatus.TOO_MANY_REQUESTS, true,
                provider + " rate limit reached");
    }

    public static AiProviderException unavailable(String provider, String detail) {
        return new AiProviderException("AI_PROVIDER_UNAVAILABLE",
                "The AI assistant is busy right now. Please try again in a minute.",
                HttpStatus.SERVICE_UNAVAILABLE, true,
                provider + " is unavailable: " + detail);
    }

    public static AiProviderException badRequest(String provider, String detail) {
        return new AiProviderException("AI_PROVIDER_BAD_REQUEST",
                "The AI assistant couldn't process this request. Try shortening or rephrasing it.",
                HttpStatus.UNPROCESSABLE_ENTITY, false,
                provider + " rejected the request: " + detail);
    }

    public static AiProviderException notConfigured(String provider) {
        return new AiProviderException("AI_PROVIDER_NOT_CONFIGURED",
                "The AI assistant isn't available right now. Please contact your administrator.",
                HttpStatus.SERVICE_UNAVAILABLE, false,
                "No API key configured for " + provider);
    }
}