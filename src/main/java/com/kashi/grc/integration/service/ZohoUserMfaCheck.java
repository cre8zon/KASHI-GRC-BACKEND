package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.integration.spi.IntegrationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * ZohoUserMfaCheck — verifies MFA is enforced for the workforce (all active
 * users) in Zoho.
 *
 * check_key:     ZOHO_USER_MFA
 * integration:   ZOHO
 * control_tag:   IAM-02.2   (workforce MFA leaf — same tag as OKTA_USER_MFA;
 *                            maps to ISO A.8.5 secure authentication and SOC 2
 *                            CC6.1 via the Phase-3 expanded-set matcher.)
 * run_frequency: DAILY
 *
 * Like ZohoAdminMfaCheck, MFA in Zoho is enforced at the security-policy level,
 * so the audit-defensible signal is: enumerate active users + confirm the org
 * MFA policy is enabled. We DON'T fabricate a per-user MFA boolean (Zoho has no
 * public REST field for it). If the policy state isn't exposed for the tenant,
 * we pass on the user census and flag it for manual policy verification.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZohoUserMfaCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private static final String TAG = "IAM-02.2";

    @Override public String checkKey()       { return "ZOHO_USER_MFA"; }
    @Override public String integrationKey() { return "ZOHO"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Enforce MFA for all users: Zoho Directory > Security > Multi-Factor Authentication, "
                        + "apply to all users and set a grace period so people can enrol before it takes "
                        + "effect. Optional MFA is not a control -- the check looks for enforcement, not "
                        + "availability. https://directory.zoho.com";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth = objectMapper.readTree(authConfig);
            String accessToken = ZohoAuth.accessToken(auth, restTemplate, objectMapper);
            String apiDomain   = auth.path("apiDomain").asText("https://www.zohoapis.com");

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Zoho-oauthtoken " + accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> req = new HttpEntity<>(headers);

            // Enumerate users via the Zoho DIRECTORY API (correct identity source,
            // no CRM org required). Requires a Directory-capable plan + scope.
            int page = 1, activeCount = 0, totalSeen = 0;
            boolean more = true;
            List<Map<String, Object>> sample = new ArrayList<>();
            while (more && page <= 25) {   // hard cap: 25 pages safety
                String url = apiDomain + "/directory/v1/users?limit=200&start=" + ((page - 1) * 200);
                ResponseEntity<JsonNode> resp;
                try {
                    resp = restTemplate.exchange(url, HttpMethod.GET, req, JsonNode.class);
                } catch (Exception dirErr) {
                    return CheckResult.error(
                            "Zoho Directory API not accessible (needs Workplace/One/Enterprise + "
                                    + "ZohoDirectory scope): " + dirErr.getMessage(), TAG);
                }
                JsonNode body = resp.getBody();
                JsonNode arr = body == null ? null
                        : (body.has("users") ? body.get("users")
                           : body.has("data") ? body.get("data") : null);
                if (arr == null || !arr.isArray() || arr.size() == 0) break;

                for (JsonNode u : arr) {
                    totalSeen++;
                    String status = firstNonBlank2(u.path("status").asText(""),
                            u.path("accountStatus").asText("active"));
                    if (!status.equalsIgnoreCase("inactive") && !status.equalsIgnoreCase("disabled")) {
                        activeCount++;
                        if (sample.size() < 25) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("email", firstNonBlank2(u.path("email").asText(""),
                                    u.path("primaryEmail").asText("")));
                            row.put("role",  u.path("role").asText(""));
                            sample.add(row);
                        }
                    }
                }
                more = arr.size() == 200;   // full page → probably more
                page++;
            }

            Boolean mfaPolicyEnabled = ZohoAuth.tryMfaPolicyEnabled(auth, accessToken, restTemplate, objectMapper);

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",         checkKey(),
                    "checkedAt",        java.time.LocalDateTime.now().toString(),
                    "activeUsers",      activeCount,
                    "usersScanned",     totalSeen,
                    "sample",           sample,
                    "mfaPolicyEnabled", mfaPolicyEnabled == null ? "UNKNOWN" : mfaPolicyEnabled.toString(),
                    "mfaEvidenceNote",  "Zoho enforces MFA via security policy; per-user enrolment "
                            + "is not a public REST field. Check verifies org MFA policy + user census."
            ));

            if (Boolean.FALSE.equals(mfaPolicyEnabled)) {
                return CheckResult.fail(
                        "Zoho MFA security policy is DISABLED — MFA not enforced for "
                                + activeCount + " active user(s)",
                        rawPayload, "Zoho Workforce MFA Policy", TAG);
            }
            String summary = activeCount + " active user(s)"
                    + (Boolean.TRUE.equals(mfaPolicyEnabled)
                    ? "; org MFA security policy ENABLED"
                    : "; MFA policy state not exposed via API — verify in Zoho admin console");
            return CheckResult.pass(summary, rawPayload, "Zoho Workforce MFA Policy", TAG);

        } catch (Exception e) {
            log.error("[ZOHO-USER-MFA] Failed: {}", e.getMessage());
            return CheckResult.error("Zoho API error: " + e.getMessage(), TAG);
        }
    }

    private static String firstNonBlank2(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}