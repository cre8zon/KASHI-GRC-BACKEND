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
 * GithubBranchProtectionCheck — implementation behind the existing seeded check
 * integration_checks.id = 12  (check_key = GITHUB_BRANCH_PROTECTION).
 *
 * check_key:      GITHUB_BRANCH_PROTECTION   ← matches the seeded row exactly
 * integration_key: GITHUB
 * control_tag:    APP-01.3   (seed row's legacy 'CHANGE_MGMT' realigned)
 *
 * ── HONOURS THE SEED ROW'S check_config_json ────────────────────────────────
 *   Row 12: {"branches":["main","master"]}
 * So instead of only checking each repo's default branch, this checks every
 * configured branch name and treats a repo as compliant if ANY of its listed
 * branches is protected with review. Falls back to the default branch when the
 * config is absent.
 *
 * ── AUTH CONFIG ──────────────────────────────────────────────────────────────
 *   {"token":"github_pat_…","org":"acme-inc"}   (Administration: read)
 *
 * ── CHECK CONFIG ─────────────────────────────────────────────────────────────
 *   {"branches":["main","master"], "repos":["api"], "minReviewers":1}
 *
 * pass_criteria on the seed row: {"type":"ALL_PASS","field":"protected","value":true}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubBranchProtectionCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private static final String TAG = "APP-01.3";
    private static final String API = "https://api.github.com";

    @Override public String checkKey()       { return "GITHUB_BRANCH_PROTECTION"; }
    @Override public String integrationKey() { return "GITHUB"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Protect the default branch: GitHub > repository > Settings > Branches > Add branch "
                        + "ruleset. Require a pull request before merging, require at least one approval, and "
                        + "tick 'Do not allow bypassing the above settings' -- an admin bypass leaves the "
                        + "control ineffective even with the rule in place. Apply it to every in-scope repo, "
                        + "not just the busiest one.";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth = objectMapper.readTree(authConfig);
            String token = auth.get("token").asText();
            String org   = auth.get("org").asText();

            List<String> branchNames = new ArrayList<>();
            Set<String> repoFilter = new HashSet<>();
            int minReviewers = 1;
            if (checkConfig != null && !checkConfig.isBlank()) {
                JsonNode cfg = objectMapper.readTree(checkConfig);
                if (cfg.has("branches")) cfg.get("branches").forEach(b -> branchNames.add(b.asText()));
                if (cfg.has("repos"))    cfg.get("repos").forEach(r -> repoFilter.add(r.asText()));
                minReviewers = cfg.path("minReviewers").asInt(1);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            headers.set("Accept", "application/vnd.github+json");
            headers.set("X-GitHub-Api-Version", "2022-11-28");
            HttpEntity<Void> req = new HttpEntity<>(headers);

            List<Map<String, Object>> repoResults = new ArrayList<>();
            List<String> failures = new ArrayList<>();

            int page = 1;
            while (true) {
                String reposUrl = API + "/orgs/" + org + "/repos?per_page=100&page=" + page + "&type=all";
                ResponseEntity<JsonNode> resp = restTemplate.exchange(
                        reposUrl, HttpMethod.GET, req, JsonNode.class);
                JsonNode body = resp.getBody();
                if (body == null || body.isEmpty()) break;

                for (JsonNode repo : body) {
                    if (repo.path("archived").asBoolean(false)) continue;
                    String name = repo.get("name").asText();
                    if (!repoFilter.isEmpty() && !repoFilter.contains(name)) continue;

                    // Branches to inspect: configured list, else the repo default
                    List<String> toCheck = branchNames.isEmpty()
                            ? List.of(repo.path("default_branch").asText("main"))
                            : branchNames;

                    boolean anyProtected = false;
                    List<String> branchStates = new ArrayList<>();
                    for (String branch : toCheck) {
                        String protUrl = API + "/repos/" + org + "/" + name
                                + "/branches/" + branch + "/protection";
                        try {
                            ResponseEntity<JsonNode> prot = restTemplate.exchange(
                                    protUrl, HttpMethod.GET, req, JsonNode.class);
                            JsonNode p = prot.getBody();
                            JsonNode reviews = p == null ? null
                                    : p.path("required_pull_request_reviews");
                            boolean reviewReq = reviews != null && !reviews.isMissingNode()
                                    && !reviews.isNull();
                            int approvals = reviewReq
                                    ? reviews.path("required_approving_review_count").asInt(0) : 0;
                            boolean ok = p != null && reviewReq && approvals >= minReviewers;
                            branchStates.add(branch + (ok ? ":ok" : ":weak"));
                            if (ok) anyProtected = true;
                        } catch (Exception notProtected) {
                            branchStates.add(branch + ":none");
                        }
                    }

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("repo", name);
                    r.put("branches", branchStates);
                    r.put("protected", anyProtected);
                    repoResults.add(r);
                    if (!anyProtected) failures.add(name);
                }
                page++;
                if (body.size() < 100) break;
            }

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",     checkKey(),
                    "checkedAt",    LocalDateTime.now().toString(),
                    "org",          org,
                    "branches",     branchNames.isEmpty() ? List.of("<default>") : branchNames,
                    "minReviewers", minReviewers,
                    "totalRepos",   repoResults.size(),
                    "compliant",    repoResults.size() - failures.size(),
                    "nonCompliant", failures.size(),
                    "repos",        repoResults));

            if (repoResults.isEmpty()) {
                return CheckResult.fail(
                        "No repositories found for org " + org + " — verify org name and token scope",
                        rawPayload, "GitHub Branch Protection", TAG);
            }
            if (failures.isEmpty()) {
                return CheckResult.pass(
                        "All " + repoResults.size() + " repos enforce protected branches with review",
                        rawPayload, "GitHub Branch Protection", TAG);
            }
            return CheckResult.fail(
                    failures.size() + " repo(s) lack a protected reviewed branch: "
                            + String.join(", ", failures),
                    rawPayload, "GitHub Branch Protection", TAG);

        } catch (Exception e) {
            log.error("[GITHUB-BRANCH-CHECK] Failed: {}", e.getMessage());
            return CheckResult.error("GitHub API error: " + e.getMessage(), TAG);
        }
    }
}