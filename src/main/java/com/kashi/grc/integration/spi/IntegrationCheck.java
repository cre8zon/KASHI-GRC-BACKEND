package com.kashi.grc.integration.spi;

/**
 * IntegrationCheck — SPI for automated compliance evidence collection.
 *
 * Implement this interface + annotate with @Component to register a new check.
 * IntegrationRunner discovers all implementations via Spring injection.
 * No IntegrationRunner code changes needed when adding new checks.
 *
 * Contract:
 *   - Must be idempotent (running twice produces same result)
 *   - Must catch all exceptions and return Result.ERROR rather than throwing
 *   - rawPayload must be valid JSON (used as audit evidence)
 *   - Should complete within 30 seconds (runner has a per-check timeout)
 *
 * Example: @see OktaAdminMfaCheck
 */
public interface IntegrationCheck {

    /** Must match integration_checks.check_key exactly */
    String checkKey();

    /** Must match integration_checks.integration_key exactly */
    String integrationKey();

    /**
     * Execute the compliance check.
     *
     * @param authConfig   decrypted JSON with integration credentials
     *                     e.g. {"apiToken":"...","domain":"company.okta.com"}
     * @param checkConfig  check-specific config from integration_checks.check_config_json
     *                     e.g. {"scope":"ADMINS"} — may be null
     * @return CheckResult — never null; use Result.ERROR for exceptions
     */
    CheckResult run(String authConfig, String checkConfig);

    record CheckResult(
            Result result,           // PASS | FAIL | ERROR
            String summary,          // human-readable: "All 47 admins have MFA" | "3 missing: ..."
            String rawPayload,       // full JSON API response stored as audit evidence
            String evidenceTitle,    // auto-generated title for EvidenceRecord
            String controlTag        // tag to propagate (usually from check config)
    ) {
        /** Convenience factory for PASS */
        public static CheckResult pass(String summary, String rawPayload, String title, String tag) {
            return new CheckResult(Result.PASS, summary, rawPayload, title, tag);
        }
        /** Convenience factory for FAIL */
        public static CheckResult fail(String summary, String rawPayload, String title, String tag) {
            return new CheckResult(Result.FAIL, summary, rawPayload, title, tag);
        }
        /** Convenience factory for ERROR */
        public static CheckResult error(String errorMessage, String tag) {
            return new CheckResult(Result.ERROR, errorMessage, "{}", "Check Error", tag);
        }
    }

    enum Result { PASS, FAIL, ERROR }
}