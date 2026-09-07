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
 */
public class AiProviderException extends BusinessException {

    private final boolean retryable;

    public AiProviderException(String errorCode, String message, HttpStatus status, boolean retryable) {
        super(errorCode, message, status);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }

    public static AiProviderException rateLimited(String provider) {
        return new AiProviderException("AI_RATE_LIMITED",
                provider + " rate limit reached — retrying shortly",
                HttpStatus.TOO_MANY_REQUESTS, true);
    }

    public static AiProviderException unavailable(String provider, String detail) {
        return new AiProviderException("AI_PROVIDER_UNAVAILABLE",
                provider + " is unavailable: " + detail,
                HttpStatus.SERVICE_UNAVAILABLE, true);
    }

    public static AiProviderException badRequest(String provider, String detail) {
        return new AiProviderException("AI_PROVIDER_BAD_REQUEST",
                provider + " rejected the request: " + detail,
                HttpStatus.UNPROCESSABLE_ENTITY, false);
    }

    public static AiProviderException notConfigured(String provider) {
        return new AiProviderException("AI_PROVIDER_NOT_CONFIGURED",
                "No API key configured for " + provider,
                HttpStatus.SERVICE_UNAVAILABLE, false);
    }
}
