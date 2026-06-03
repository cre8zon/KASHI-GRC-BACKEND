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
import java.util.stream.Collectors;

/**
 * OktaAdminMfaCheck — verifies all Okta admin users have MFA enrolled.
 *
 * check_key: OKTA_ADMIN_MFA
 * control_tag: MFA_ADMIN
 * run_frequency: HOURLY
 *
 * Algorithm:
 *   1. GET /api/v1/groups?q=admins → find admin group
 *   2. GET /api/v1/groups/{id}/users → list admin users
 *   3. For each user: GET /api/v1/users/{id}/factors → check enrolled factors
 *   4. PASS if all admins have at least one MFA factor
 *   5. FAIL with list of non-compliant users
 *
 * Evidence stored: JSON array of admin users with their MFA status.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OktaAdminMfaCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Override public String checkKey()        { return "OKTA_ADMIN_MFA"; }
    @Override public String integrationKey()  { return "OKTA"; }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth   = objectMapper.readTree(authConfig);
            String apiToken = auth.get("apiToken").asText();
            String domain   = auth.get("domain").asText();
            String baseUrl  = "https://" + domain;

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "SSWS " + apiToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> req = new HttpEntity<>(headers);

            // 1. Find admin groups
            String groupsUrl = baseUrl + "/api/v1/groups?q=admin&limit=10";
            ResponseEntity<JsonNode> groupsResp = restTemplate.exchange(
                    groupsUrl, HttpMethod.GET, req, JsonNode.class);

            List<Map<String, Object>> allAdminUsers = new ArrayList<>();

            // 2. For each admin group, get members
            if (groupsResp.getBody() != null) {
                for (JsonNode group : groupsResp.getBody()) {
                    String groupId  = group.get("id").asText();
                    String usersUrl = baseUrl + "/api/v1/groups/" + groupId + "/users?limit=200";
                    ResponseEntity<JsonNode> usersResp = restTemplate.exchange(
                            usersUrl, HttpMethod.GET, req, JsonNode.class);

                    if (usersResp.getBody() != null) {
                        for (JsonNode user : usersResp.getBody()) {
                            if ("ACTIVE".equals(user.path("status").asText())) {
                                String userId    = user.get("id").asText();
                                String email     = user.path("profile").path("email").asText();
                                String login     = user.path("profile").path("login").asText();

                                // 3. Check MFA factors for this user
                                String factorsUrl = baseUrl + "/api/v1/users/" + userId + "/factors";
                                ResponseEntity<JsonNode> factorsResp = restTemplate.exchange(
                                        factorsUrl, HttpMethod.GET, req, JsonNode.class);

                                boolean hasMfa = factorsResp.getBody() != null
                                        && factorsResp.getBody().size() > 0;

                                Map<String, Object> userResult = new LinkedHashMap<>();
                                userResult.put("userId",    userId);
                                userResult.put("email",     email);
                                userResult.put("login",     login);
                                userResult.put("groupId",   groupId);
                                userResult.put("groupName", group.path("profile").path("name").asText());
                                userResult.put("mfaEnabled", hasMfa);
                                userResult.put("factorCount", factorsResp.getBody() != null
                                        ? factorsResp.getBody().size() : 0);
                                allAdminUsers.add(userResult);
                            }
                        }
                    }
                }
            }

            // 4. Deduplicate (user may be in multiple admin groups)
            Map<String, Map<String, Object>> deduplicated = new LinkedHashMap<>();
            allAdminUsers.forEach(u -> deduplicated.merge(
                    u.get("userId").toString(), u, (existing, newEntry) -> existing));
            List<Map<String, Object>> uniqueAdmins = new ArrayList<>(deduplicated.values());

            List<String> noMfa = uniqueAdmins.stream()
                    .filter(u -> !(Boolean) u.get("mfaEnabled"))
                    .map(u -> u.get("email").toString())
                    .collect(Collectors.toList());

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",    checkKey(),
                    "checkedAt",   java.time.LocalDateTime.now().toString(),
                    "totalAdmins", uniqueAdmins.size(),
                    "compliant",   uniqueAdmins.size() - noMfa.size(),
                    "nonCompliant", noMfa.size(),
                    "users",       uniqueAdmins
            ));

            if (noMfa.isEmpty()) {
                return CheckResult.pass(
                        "All " + uniqueAdmins.size() + " admin users have MFA enrolled",
                        rawPayload,
                        "Okta Admin MFA Status",
                        "MFA_ADMIN"
                );
            } else {
                return CheckResult.fail(
                        noMfa.size() + " admin users missing MFA: " + String.join(", ", noMfa),
                        rawPayload,
                        "Okta Admin MFA Status",
                        "MFA_ADMIN"
                );
            }

        } catch (Exception e) {
            log.error("[OKTA-MFA-CHECK] Failed: {}", e.getMessage());
            return CheckResult.error("Okta API error: " + e.getMessage(), "MFA_ADMIN");
        }
    }
}