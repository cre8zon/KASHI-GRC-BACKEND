package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * ZohoAuth — shared OAuth2 helper for Zoho integration checks.
 *
 * Zoho access tokens expire in ~1 hour, so checks exchange a long-lived refresh
 * token for a fresh access token on each run (checks must be idempotent and are
 * scheduled, so we don't cache across runs). All calls use the region-specific
 * accounts/API domains supplied in authConfig.
 */
final class ZohoAuth {

    private ZohoAuth() {}

    /**
     * Exchange the stored refresh token for a fresh access token.
     * authConfig must contain clientId, clientSecret, refreshToken and
     * (optionally) accountsDomain (defaults to the .com region).
     */
    static String accessToken(JsonNode auth, RestTemplate rt, ObjectMapper om) {
        String clientId     = auth.get("clientId").asText();
        String clientSecret = auth.get("clientSecret").asText();
        String refreshToken = auth.get("refreshToken").asText();
        String accountsBase = auth.path("accountsDomain").asText("https://accounts.zoho.com");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id",     clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type",    "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> req = new HttpEntity<>(form, headers);

        ResponseEntity<JsonNode> resp = rt.exchange(
                accountsBase + "/oauth/v2/token", HttpMethod.POST, req, JsonNode.class);

        JsonNode body = resp.getBody();
        if (body == null || !body.has("access_token")) {
            String err = body != null ? body.toString() : "empty response";
            throw new IllegalStateException("Zoho token refresh failed: " + err);
        }
        return body.get("access_token").asText();
    }

    /**
     * Best-effort probe of the org-level MFA security policy state.
     * Returns TRUE/FALSE if determinable, or null if the tenant/region/plan does
     * not expose it via API (caller treats null as "verify manually", never as a
     * fabricated pass/fail). Never throws — a probe failure must not fail the
     * whole check, which still has valid admin-enumeration evidence.
     */
    static Boolean tryMfaPolicyEnabled(JsonNode auth, String accessToken,
                                       RestTemplate rt, ObjectMapper om) {
        try {
            String apiDomain = auth.path("apiDomain").asText("https://www.zohoapis.com");
            String orgId     = auth.path("orgId").asText(auth.path("zsoid").asText(""));

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Zoho-oauthtoken " + accessToken);
            HttpEntity<Void> req = new HttpEntity<>(headers);

            // Zoho Directory security-policy surface. Endpoint shape varies by
            // region/plan; if it 404s or isn't authorised, we return null.
            String url = apiDomain + "/directory/v1/security/policies"
                    + (orgId.isBlank() ? "" : "?orgId=" + orgId);
            ResponseEntity<JsonNode> resp = rt.exchange(url, HttpMethod.GET, req, JsonNode.class);
            JsonNode body = resp.getBody();
            if (body == null) return null;

            // Look for any policy with MFA enabled. Field names are defensive —
            // Zoho's payload uses variants like mfa_enabled / mfaEnabled / isMfaEnabled.
            JsonNode policies = body.has("policies") ? body.get("policies") : body;
            if (policies.isArray()) {
                for (JsonNode p : policies) {
                    if (p.path("mfa_enabled").asBoolean(false)
                            || p.path("mfaEnabled").asBoolean(false)
                            || p.path("isMfaEnabled").asBoolean(false)
                            || p.path("mfa").path("enabled").asBoolean(false)) {
                        return Boolean.TRUE;
                    }
                }
                // Policies present but none had MFA on → explicitly disabled.
                return Boolean.FALSE;
            }
            return null;
        } catch (Exception e) {
            // Not exposed / not authorised / different shape — treat as unknown.
            return null;
        }
    }
}