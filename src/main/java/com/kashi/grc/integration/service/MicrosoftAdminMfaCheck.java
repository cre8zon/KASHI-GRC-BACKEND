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
 * MicrosoftAdminMfaCheck — verifies PRIVILEGED (admin) users have MFA registered
 * in Microsoft Entra ID. Uses the same registration report as the user check but
 * filters to admins (isAdmin=true), because privileged-account MFA is the highest
 * priority control (an admin without MFA is a critical finding).
 *
 * check_key:      MICROSOFT_ADMIN_MFA
 * integration_key: MICROSOFT
 * control_tag:    IAM-02.3  (privileged/admin MFA leaf)
 * capability:     MFA_ADMIN   ← tests bound to MFA_ADMIN resolve here for a
 *                               tenant that connected Microsoft (e.g. tests 91,188).
 *
 * The registration report flags admins via "isAdmin". We only evaluate those.
 * Requires Entra ID P1.
 *
 * authConfig: { tenantId, clientId, clientSecret }
 * Graph permissions: AuditLog.Read.All + Reports.Read.All
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MicrosoftAdminMfaCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private static final String TAG = "IAM-02.3";

    @Override public String checkKey()       { return "MICROSOFT_ADMIN_MFA"; }
    @Override public String integrationKey() { return "MICROSOFT"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Require MFA for admins: Entra admin center > Protection > Conditional Access > "
                        + "Create policy, target the privileged directory roles, and grant only with MFA. "
                        + "Exclude one break-glass account with a long, stored credential -- a policy covering "
                        + "every admin can lock you out of your own tenant. https://entra.microsoft.com";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth = objectMapper.readTree(authConfig);
            String token = MicrosoftGraphAuth.accessToken(auth, restTemplate);
            HttpEntity<Void> req = MicrosoftGraphAuth.authGet(token);

            int adminTotal = 0, adminMfa = 0;
            List<String> missing = new ArrayList<>();

            // Filter server-side to admins where supported; still guard client-side.
            String url = MicrosoftGraphAuth.GRAPH_BASE
                    + "/reports/authenticationMethods/userRegistrationDetails"
                    + "?$filter=isAdmin eq true&$top=500";
            int pages = 0;
            while (url != null && pages < 50) {
                ResponseEntity<JsonNode> resp = restTemplate.exchange(url, HttpMethod.GET, req, JsonNode.class);
                JsonNode body = resp.getBody();
                if (body == null) break;
                for (JsonNode u : body.path("value")) {
                    if (!u.path("isAdmin").asBoolean(false)) continue;   // client-side guard
                    adminTotal++;
                    if (u.path("isMfaRegistered").asBoolean(false)) adminMfa++;
                    else if (missing.size() < 50) missing.add(u.path("userPrincipalName").asText("(unknown)"));
                }
                url = body.hasNonNull("@odata.nextLink") ? body.get("@odata.nextLink").asText() : null;
                pages++;
            }

            int adminMissing = adminTotal - adminMfa;

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",        checkKey(),
                    "checkedAt",       LocalDateTime.now().toString(),
                    "totalAdmins",     adminTotal,
                    "adminsWithMfa",   adminMfa,
                    "adminsMissingMfa", adminMissing,
                    "missingSample",   missing));

            if (adminTotal == 0) {
                // No admins returned — could be a permissions/licensing issue rather
                // than a genuine "zero admins" state. Report as error to prompt review.
                return CheckResult.error(
                        "No admin users returned from Microsoft Graph — verify permissions/licensing (Entra ID P1)", TAG);
            }
            if (adminMissing == 0) {
                return CheckResult.pass(
                        "All " + adminTotal + " admin account(s) have MFA registered in Microsoft Entra",
                        rawPayload, "Microsoft Entra Admin MFA", TAG);
            }
            List<String> shown = missing.size() > 25 ? missing.subList(0, 25) : missing;
            return CheckResult.fail(
                    adminMissing + " of " + adminTotal + " admin account(s) have NO MFA: "
                            + String.join(", ", shown) + (missing.size() > 25 ? " …" : ""),
                    rawPayload, "Microsoft Entra Admin MFA", TAG);

        } catch (Exception e) {
            log.error("[MS-ADMIN-MFA] Failed: {}", e.getMessage());
            return CheckResult.error("Microsoft Graph error: " + e.getMessage(), TAG);
        }
    }
}