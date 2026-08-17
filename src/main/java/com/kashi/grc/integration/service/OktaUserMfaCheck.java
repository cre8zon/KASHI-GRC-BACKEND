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
 * OktaUserMfaCheck — implementation behind the existing seeded check
 * integration_checks.id = 2  (check_key = OKTA_USER_MFA).
 *
 * check_key:      OKTA_USER_MFA          ← matches the seeded row exactly
 * integration_key: OKTA
 * control_tag:    IAM-02.2  (workforce MFA leaf; seed row's legacy 'MFA_USER'
 *                            realigned). Distinct from OKTA_ADMIN_MFA → IAM-02.3.
 *
 * Complements the already-implemented OktaAdminMfaCheck: that one covers admins
 * (IAM-02.3), this covers all active users (IAM-02.2), which is what ISO A.8.5
 * expects across the workforce.
 *
 * ── AUTH CONFIG ──────────────────────────────────────────────────────────────
 *   {"apiToken":"…","domain":"acme.okta.com"}
 *
 * ── CHECK CONFIG (seed row 2: {"scope":"ALL_ACTIVE_USERS"}) ─────────────────
 *   scope is informational here — this check always evaluates active users.
 *   {"maxFindings":25}
 *
 * pass_criteria on the seed row: {"type":"PERCENTAGE","field":"mfaEnabled","threshold":100}
 * — honoured as PASS iff 100% of active users have a factor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OktaUserMfaCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private static final String TAG = "IAM-02.2";

    @Override public String checkKey()       { return "OKTA_USER_MFA"; }
    @Override public String integrationKey() { return "OKTA"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Require MFA for all users: Okta Admin console > Security > Authentication policies "
                        + "> add a rule for Any user requiring a second factor on every sign-in. Enrolment "
                        + "policies alone do not block access -- users without a factor keep signing in until "
                        + "an authentication policy requires one.";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth = objectMapper.readTree(authConfig);
            String apiToken = auth.get("apiToken").asText();
            String domain   = auth.get("domain").asText();
            String baseUrl  = "https://" + domain;

            int maxFindings = 25;
            if (checkConfig != null && !checkConfig.isBlank()) {
                maxFindings = objectMapper.readTree(checkConfig).path("maxFindings").asInt(25);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "SSWS " + apiToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> req = new HttpEntity<>(headers);

            List<Map<String, Object>> users = new ArrayList<>();
            List<String> noMfa = new ArrayList<>();

            String url = baseUrl + "/api/v1/users?filter=status+eq+%22ACTIVE%22&limit=200";
            int guard = 0;
            while (url != null && guard++ < 50) {
                ResponseEntity<JsonNode> resp = restTemplate.exchange(
                        url, HttpMethod.GET, req, JsonNode.class);
                JsonNode body = resp.getBody();
                if (body == null) break;

                for (JsonNode u : body) {
                    String userId = u.get("id").asText();
                    String email  = u.path("profile").path("email").asText();

                    String factorsUrl = baseUrl + "/api/v1/users/" + userId + "/factors";
                    ResponseEntity<JsonNode> fr = restTemplate.exchange(
                            factorsUrl, HttpMethod.GET, req, JsonNode.class);
                    boolean hasMfa = fr.getBody() != null && fr.getBody().size() > 0;

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("userId", userId);
                    r.put("email", email);
                    r.put("mfaEnabled", hasMfa);
                    r.put("factorCount", fr.getBody() != null ? fr.getBody().size() : 0);
                    users.add(r);
                    if (!hasMfa) noMfa.add(email);
                }
                url = nextLink(resp.getHeaders().get(HttpHeaders.LINK));
            }

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",     checkKey(),
                    "checkedAt",    LocalDateTime.now().toString(),
                    "totalUsers",   users.size(),
                    "compliant",    users.size() - noMfa.size(),
                    "nonCompliant", noMfa.size(),
                    "users",        users));

            if (users.isEmpty()) {
                return CheckResult.fail(
                        "No active users returned — verify token scope",
                        rawPayload, "Okta User MFA", TAG);
            }
            if (noMfa.isEmpty()) {
                return CheckResult.pass(
                        "All " + users.size() + " active users have MFA enrolled",
                        rawPayload, "Okta User MFA", TAG);
            }
            List<String> shown = noMfa.size() > maxFindings ? noMfa.subList(0, maxFindings) : noMfa;
            return CheckResult.fail(
                    noMfa.size() + " user(s) missing MFA: " + String.join(", ", shown)
                            + (noMfa.size() > maxFindings ? " …" : ""),
                    rawPayload, "Okta User MFA", TAG);

        } catch (Exception e) {
            log.error("[OKTA-USER-MFA] Failed: {}", e.getMessage());
            return CheckResult.error("Okta API error: " + e.getMessage(), TAG);
        }
    }

    private String nextLink(List<String> linkHeaders) {
        if (linkHeaders == null) return null;
        for (String h : linkHeaders) {
            if (h.contains("rel=\"next\"")) {
                int lt = h.indexOf('<'), gt = h.indexOf('>');
                if (lt >= 0 && gt > lt) return h.substring(lt + 1, gt);
            }
        }
        return null;
    }
}