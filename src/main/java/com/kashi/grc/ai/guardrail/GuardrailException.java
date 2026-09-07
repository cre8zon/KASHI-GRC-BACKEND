package com.kashi.grc.ai.guardrail;

import com.kashi.grc.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A guardrail refused the call.
 *
 * Distinct from AiProviderException on purpose: this is the system working, not
 * failing. It maps to an InteractionStatus of BLOCKED rather than FAILED so that
 * "we stopped 40 injection attempts this month" and "the vendor 500'd 40 times
 * this month" never appear as the same number on a dashboard.
 */
public class GuardrailException extends BusinessException {

    public GuardrailException(String errorCode, String message, HttpStatus status) {
        super(errorCode, message, status);
    }

    public GuardrailException(String errorCode, String message, HttpStatus status, Map<String, Object> details) {
        super(errorCode, message, status, details);
    }

    public static GuardrailException budgetExceeded(long used, long limit) {
        return new GuardrailException("AI_BUDGET_EXCEEDED",
                "This organisation's monthly AI allowance is exhausted",
                HttpStatus.PAYMENT_REQUIRED,
                Map.of("tokensUsed", used, "tokenLimit", limit));
    }

    public static GuardrailException promptTooLarge(int chars, int max) {
        return new GuardrailException("AI_PROMPT_TOO_LARGE",
                "The assembled context is too large to send — narrow the selection and retry",
                HttpStatus.PAYLOAD_TOO_LARGE,
                Map.of("chars", chars, "maxChars", max));
    }

    public static GuardrailException injectionSuspected(String detail) {
        return new GuardrailException("AI_INJECTION_SUSPECTED",
                "The source document contains instructions aimed at the AI and was not used: " + detail,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public static GuardrailException invalidOutput(String detail) {
        return new GuardrailException("AI_INVALID_OUTPUT",
                "The model returned a response that failed validation: " + detail,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public static GuardrailException fabricatedIds(java.util.List<String> bad, String kind) {
        return new GuardrailException("AI_FABRICATED_REFERENCES",
                "The model referenced " + kind + " that do not exist and the result was rejected",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("invalidReferences", bad, "kind", kind));
    }

    public static GuardrailException tenantDisabled() {
        return new GuardrailException("AI_DISABLED_FOR_TENANT",
                "AI features are switched off for this organisation",
                HttpStatus.FORBIDDEN);
    }
}
