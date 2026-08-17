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
 * GoogleWorkspaceAdmin2SvCheck — verifies ADMIN (super-admin) users have 2-Step
 * Verification enrolled. Privileged-account MFA is the highest-priority identity
 * control, so admins are evaluated separately from the general workforce.
 *
 * check_key:      GWS_ADMIN_2SV
 * integration_key: GOOGLE_WORKSPACE
 * control_tag:    IAM-02.3  (privileged/admin MFA leaf)
 * capability:     MFA_ADMIN   ← makes Google resolve for admin-MFA tests
 *                               (e.g. tests 91, 188) for a Google-Workspace tenant.
 *
 * Uses the same Directory API + isEnrolledIn2Sv, filtered to isAdmin.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleWorkspaceAdmin2SvCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private static final String TAG = "IAM-02.3";

    @Override public String checkKey()       { return "GWS_ADMIN_2SV"; }
    @Override public String integrationKey() { return "GOOGLE_WORKSPACE"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Enforce 2SV for admins first: Google Admin console > Security > Authentication > "
                        + "2-Step Verification, scoped to the organizational unit holding your super admins. "
                        + "Register a backup security key before enforcing, or you risk locking every admin "
                        + "out of the tenant. https://admin.google.com/ac/security";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth = objectMapper.readTree(authConfig);
            String customer = auth.path("customerId").asText("my_customer");
            String token = GoogleWorkspaceAuth.accessToken(auth, restTemplate);
            HttpEntity<Void> req = GoogleWorkspaceAuth.authGet(token);

            int adminTotal = 0, adminEnrolled = 0;
            List<String> missing = new ArrayList<>();
            String pageToken = null;
            int pages = 0;

            do {
                String url = GoogleWorkspaceAuth.DIR_BASE
                        + "/users?customer=" + customer
                        + "&maxResults=500&projection=full&viewType=admin_view"
                        + (pageToken != null ? "&pageToken=" + pageToken : "");
                ResponseEntity<JsonNode> resp = restTemplate.exchange(url, HttpMethod.GET, req, JsonNode.class);
                JsonNode body = resp.getBody();
                if (body == null) break;
                for (JsonNode u : body.path("users")) {
                    boolean isAdmin = u.path("isAdmin").asBoolean(false)
                            || u.path("isDelegatedAdmin").asBoolean(false);
                    if (!isAdmin || u.path("suspended").asBoolean(false)) continue;
                    adminTotal++;
                    if (u.path("isEnrolledIn2Sv").asBoolean(false)) adminEnrolled++;
                    else if (missing.size() < 50) missing.add(u.path("primaryEmail").asText("(unknown)"));
                }
                pageToken = body.hasNonNull("nextPageToken") ? body.get("nextPageToken").asText() : null;
                pages++;
            } while (pageToken != null && pages < 50);

            int adminMissing = adminTotal - adminEnrolled;

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",         checkKey(),
                    "checkedAt",        LocalDateTime.now().toString(),
                    "totalAdmins",      adminTotal,
                    "adminsEnrolled2Sv", adminEnrolled,
                    "adminsMissing2Sv", adminMissing,
                    "missingSample",    missing));

            if (adminTotal == 0) {
                return CheckResult.error(
                        "No admin users returned from Google Directory — check delegation/scope/impersonation", TAG);
            }
            if (adminMissing == 0) {
                return CheckResult.pass(
                        "All " + adminTotal + " admin account(s) have 2SV enrolled",
                        rawPayload, "Google Workspace Admin 2SV", TAG);
            }
            List<String> shown = missing.size() > 25 ? missing.subList(0, 25) : missing;
            return CheckResult.fail(
                    adminMissing + " of " + adminTotal + " admin account(s) NOT enrolled in 2SV: "
                            + String.join(", ", shown) + (missing.size() > 25 ? " …" : ""),
                    rawPayload, "Google Workspace Admin 2SV", TAG);

        } catch (Exception e) {
            log.error("[GWS-ADMIN-2SV] Failed: {}", e.getMessage());
            return CheckResult.error("Google Workspace API error: " + e.getMessage(), TAG);
        }
    }
}