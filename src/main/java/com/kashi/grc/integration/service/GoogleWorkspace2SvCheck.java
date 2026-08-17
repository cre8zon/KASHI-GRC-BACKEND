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
 * GoogleWorkspace2SvCheck — verifies users have 2-Step Verification (2SV, Google's
 * MFA) enrolled. The Admin SDK Directory API exposes isEnrolledIn2Sv per user.
 *
 * check_key:      GWS_2SV
 * integration_key: GOOGLE_WORKSPACE
 * control_tag:    IAM-02.2  (workforce MFA leaf)
 * capability:     MFA_USER   ← a test bound to MFA_USER resolves here for a
 *                              Google-Workspace tenant.
 *
 * No Google SDK — plain REST via GoogleWorkspaceAuth (SA JWT → token).
 *
 * Endpoint: GET /users?customer={id}&maxResults=500&projection=full
 *   → each user has isEnrolledIn2Sv, isAdmin, suspended, primaryEmail.
 *   Paginated via nextPageToken.
 * Read-only scope: admin.directory.user.readonly
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleWorkspace2SvCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private static final String TAG = "IAM-02.2";

    @Override public String checkKey()       { return "GWS_2SV"; }
    @Override public String integrationKey() { return "GOOGLE_WORKSPACE"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Enforce 2-Step Verification: Google Admin console > Security > Authentication > "
                        + "2-Step Verification > Allow users to turn on 2SV, then set Enforcement to 'On'. "
                        + "Enrolment is not enforcement -- users who have not enrolled keep signing in until "
                        + "you set an enforcement date. https://admin.google.com/ac/security";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth = objectMapper.readTree(authConfig);
            String customer = auth.path("customerId").asText("my_customer");
            String token = GoogleWorkspaceAuth.accessToken(auth, restTemplate);
            HttpEntity<Void> req = GoogleWorkspaceAuth.authGet(token);

            int total = 0, enrolled = 0;
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
                    if (u.path("suspended").asBoolean(false)) continue;   // skip suspended
                    total++;
                    if (u.path("isEnrolledIn2Sv").asBoolean(false)) enrolled++;
                    else if (missing.size() < 50) missing.add(u.path("primaryEmail").asText("(unknown)"));
                }
                pageToken = body.hasNonNull("nextPageToken") ? body.get("nextPageToken").asText() : null;
                pages++;
            } while (pageToken != null && pages < 50);

            int notEnrolled = total - enrolled;

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",      checkKey(),
                    "checkedAt",     LocalDateTime.now().toString(),
                    "totalUsers",    total,
                    "enrolled2Sv",   enrolled,
                    "notEnrolled",   notEnrolled,
                    "missingSample", missing));

            if (total == 0) {
                return CheckResult.error(
                        "No users returned from Google Directory — check delegation/scope/admin impersonation", TAG);
            }
            if (notEnrolled == 0) {
                return CheckResult.pass(
                        "All " + total + " active users have 2-Step Verification enrolled",
                        rawPayload, "Google Workspace 2SV", TAG);
            }
            List<String> shown = missing.size() > 25 ? missing.subList(0, 25) : missing;
            return CheckResult.fail(
                    notEnrolled + " of " + total + " user(s) NOT enrolled in 2SV: "
                            + String.join(", ", shown) + (missing.size() > 25 ? " …" : ""),
                    rawPayload, "Google Workspace 2SV", TAG);

        } catch (Exception e) {
            log.error("[GWS-2SV] Failed: {}", e.getMessage());
            return CheckResult.error("Google Workspace API error: " + e.getMessage(), TAG);
        }
    }
}