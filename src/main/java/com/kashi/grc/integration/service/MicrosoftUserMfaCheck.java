package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.integration.spi.IntegrationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * MicrosoftUserMfaCheck — verifies the workforce has MFA registered in Microsoft
 * Entra ID (Azure AD). Uses the authentication-methods registration report, which
 * gives a real per-user "isMfaRegistered" — genuine, audit-grade MFA evidence
 * (unlike Zoho, Microsoft Graph exposes this directly).
 *
 * check_key:      MICROSOFT_USER_MFA
 * integration_key: MICROSOFT
 * control_tag:    IAM-02.2  (workforce MFA leaf)
 * capability:     MFA_USER   ← a test bound to MFA_USER resolves here for a
 *                              tenant that connected Microsoft.
 *
 * Plain REST against Microsoft Graph (no SDK), same style as the GitHub check.
 *
 * Endpoint: GET /reports/authenticationMethods/userRegistrationDetails
 *   → per-user { userPrincipalName, isMfaRegistered, isAdmin, ... }, paginated
 *     via @odata.nextLink.
 * Requires Entra ID P1 (the registration report is a P1 feature).
 *
 * authConfig: { tenantId, clientId, clientSecret }
 * Graph permissions: AuditLog.Read.All + Reports.Read.All (report),
 *   or User.Read.All + UserAuthenticationMethod.Read.All (per-user enumeration).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MicrosoftUserMfaCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private static final String TAG = "IAM-02.2";

    @Override public String checkKey()       { return "MICROSOFT_USER_MFA"; }
    @Override public String integrationKey() { return "MICROSOFT"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Require MFA for all users: Entra admin center > Protection > Conditional Access > "
                        + "Create policy, target All users, grant only with MFA. Roll out in report-only mode "
                        + "first to see who would be blocked, then switch to On. Security defaults are a "
                        + "simpler alternative if you have no Conditional Access licence. https://entra.microsoft.com";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth = objectMapper.readTree(authConfig);
            String token = MicrosoftGraphAuth.accessToken(auth, restTemplate);
            HttpEntity<Void> req = MicrosoftGraphAuth.authGet(token);

            int total = 0, mfaRegistered = 0;
            List<String> missing = new ArrayList<>();

            String url = MicrosoftGraphAuth.GRAPH_BASE
                    + "/reports/authenticationMethods/userRegistrationDetails?$top=500";
            int pages = 0;
            while (url != null && pages < 50) {   // safety cap: 50 pages
                ResponseEntity<JsonNode> resp = restTemplate.exchange(url, HttpMethod.GET, req, JsonNode.class);
                JsonNode body = resp.getBody();
                if (body == null) break;
                for (JsonNode u : body.path("value")) {
                    total++;
                    boolean reg = u.path("isMfaRegistered").asBoolean(false);
                    if (reg) mfaRegistered++;
                    else if (missing.size() < 50) missing.add(u.path("userPrincipalName").asText("(unknown)"));
                }
                url = body.hasNonNull("@odata.nextLink") ? body.get("@odata.nextLink").asText() : null;
                pages++;
            }

            int notRegistered = total - mfaRegistered;

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",       checkKey(),
                    "checkedAt",      LocalDateTime.now().toString(),
                    "totalUsers",     total,
                    "mfaRegistered",  mfaRegistered,
                    "notRegistered",  notRegistered,
                    "missingSample",  missing));

            if (total == 0) {
                return CheckResult.error("No users returned from Microsoft Graph — check permissions/licensing (Entra ID P1)", TAG);
            }
            if (notRegistered == 0) {
                return CheckResult.pass(
                        "All " + total + " users have MFA registered in Microsoft Entra",
                        rawPayload, "Microsoft Entra Workforce MFA", TAG);
            }
            List<String> shown = missing.size() > 25 ? missing.subList(0, 25) : missing;
            return CheckResult.fail(
                    notRegistered + " of " + total + " user(s) have NO MFA registered: "
                            + String.join(", ", shown) + (missing.size() > 25 ? " …" : ""),
                    rawPayload, "Microsoft Entra Workforce MFA", TAG);

        } catch (Exception e) {
            log.error("[MS-USER-MFA] Failed: {}", e.getMessage());
            return CheckResult.error("Microsoft Graph error: " + e.getMessage(), TAG);
        }
    }
}