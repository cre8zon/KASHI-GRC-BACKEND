package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.integration.spi.IntegrationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * GithubDeploymentSegregationCheck — segregation of duties in the delivery path.
 *
 *   check_key       GITHUB_DEPLOYMENT_SEGREGATION
 *   integration_key GITHUB
 *   capability      DEPLOYMENT_SEGREGATION
 *   control_tag     IAM-03.3   Segregation of duties
 *
 * ── HOW THIS DIFFERS FROM GITHUB_BRANCH_PROTECTION ──────────────────────────
 * The existing check asks whether a protected branch requires review at all.
 * That is change control (APP-01.3). This asks a narrower question: can the
 * author of a change also approve and merge it?
 *
 * Three conditions together, because any one alone leaves the gap open:
 *
 *   required_approving_review_count >= 1
 *       someone other than the author must approve
 *   dismiss_stale_reviews = true
 *       an approval does not survive the author pushing more commits
 *   enforce_admins = true  OR  require_code_owner_reviews = true
 *       an admin cannot merge past the rule, which is where self-approval
 *       actually happens in practice
 *
 * Requiring a review while letting admins bypass it is the most common way
 * this control passes on paper and fails in reality.
 *
 * ── SCOPE, HONESTLY ─────────────────────────────────────────────────────────
 * This evidences segregation of duties in the DEPLOYMENT PATH. IAM-03.3 covers
 * separation of conflicting duties generally — finance approvals, privileged
 * administration, access grants. The seeded test is therefore HYBRID rather
 * than AUTOMATED: this is strong evidence for part of the control, not a
 * verdict on all of it.
 *
 * That distinction is the same one that stopped AWS_S3_PUBLIC_ACCESS_BLOCK
 * being wired to the least-privilege tests.
 *
 * ── AUTH CONFIG ─────────────────────────────────────────────────────────────
 *   {"token":"ghp_…","org":"your-org"}
 *   Fine-grained PAT, read-only: Administration (read), Contents (read),
 *   Metadata (read)
 *
 * ── CHECK CONFIG (optional) ─────────────────────────────────────────────────
 *   {"repos":["api","web"],"branches":["main"],"minReviewers":1,
 *    "requireAdminEnforcement":true}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubDeploymentSegregationCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private static final String API = "https://api.github.com";

    /** Advisory only — IntegrationRunner uses the tenant catalogue row. */
    private static final String TAG = "IAM-03.3";

    @Override public String checkKey()       { return "GITHUB_DEPLOYMENT_SEGREGATION"; }
    @Override public String integrationKey() { return "GITHUB"; }

    @Override
    public String remediation() {
        return "On each protected branch enable: required pull request reviews with at least one "
             + "approval, 'Dismiss stale pull request approvals when new commits are pushed', and "
             + "'Do not allow bypassing the above settings' (enforce_admins). Without admin "
             + "enforcement, an administrator can merge their own change and the control is "
             + "documentary only.";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth = objectMapper.readTree(authConfig);
            String token = auth.get("token").asText();
            String org   = auth.get("org").asText();

            List<String> branchNames = new ArrayList<>();
            Set<String> repoFilter   = new HashSet<>();
            int minReviewers         = 1;
            boolean requireAdmin     = true;
            if (checkConfig != null && !checkConfig.isBlank()) {
                JsonNode cfg = objectMapper.readTree(checkConfig);
                if (cfg.has("branches")) cfg.get("branches").forEach(b -> branchNames.add(b.asText()));
                if (cfg.has("repos"))    cfg.get("repos").forEach(r -> repoFilter.add(r.asText()));
                minReviewers = cfg.path("minReviewers").asInt(1);
                requireAdmin = cfg.path("requireAdminEnforcement").asBoolean(true);
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

                    List<String> toCheck = branchNames.isEmpty()
                            ? List.of(repo.path("default_branch").asText("main"))
                            : branchNames;

                    boolean allSegregated = true;
                    List<Map<String, Object>> branchStates = new ArrayList<>();

                    for (String branch : toCheck) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("branch", branch);
                        try {
                            String protUrl = API + "/repos/" + org + "/" + name
                                    + "/branches/" + branch + "/protection";
                            JsonNode p = restTemplate.exchange(
                                    protUrl, HttpMethod.GET, req, JsonNode.class).getBody();

                            JsonNode reviews = p == null ? null : p.path("required_pull_request_reviews");
                            boolean hasReviews = reviews != null && !reviews.isMissingNode() && !reviews.isNull();
                            int approvals     = hasReviews ? reviews.path("required_approving_review_count").asInt(0) : 0;
                            boolean dismiss   = hasReviews && reviews.path("dismiss_stale_reviews").asBoolean(false);
                            boolean codeOwner = hasReviews && reviews.path("require_code_owner_reviews").asBoolean(false);
                            boolean admins    = p != null && p.path("enforce_admins").path("enabled").asBoolean(false);

                            boolean bypassBlocked = !requireAdmin || admins || codeOwner;
                            boolean ok = approvals >= minReviewers && dismiss && bypassBlocked;

                            b.put("requiredApprovals",   approvals);
                            b.put("dismissStaleReviews", dismiss);
                            b.put("enforceAdmins",       admins);
                            b.put("requireCodeOwner",    codeOwner);
                            b.put("segregated",          ok);
                            if (!ok) {
                                List<String> why = new ArrayList<>();
                                if (approvals < minReviewers) why.add("needs " + minReviewers + " approval(s), has " + approvals);
                                if (!dismiss)                 why.add("stale approvals not dismissed");
                                if (!bypassBlocked)           why.add("admins can bypass");
                                b.put("gaps", why);
                                allSegregated = false;
                            }
                        } catch (Exception notProtected) {
                            b.put("segregated", false);
                            b.put("gaps", List.of("branch not protected"));
                            allSegregated = false;
                        }
                        branchStates.add(b);
                    }

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("repo", name);
                    r.put("branches", branchStates);
                    r.put("segregated", allSegregated);
                    repoResults.add(r);
                    if (!allSegregated) failures.add(name);
                }
                page++;
                if (body.size() < 100) break;
            }

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",     checkKey(),
                    "checkedAt",    LocalDateTime.now().toString(),
                    "org",          org,
                    "minReviewers", minReviewers,
                    "requireAdminEnforcement", requireAdmin,
                    "reposChecked", repoResults.size(),
                    "repos",        repoResults));

            if (repoResults.isEmpty()) {
                return CheckResult.error("No repositories matched the configured filter", TAG);
            }
            if (failures.isEmpty()) {
                return CheckResult.pass(
                        "All " + repoResults.size() + " repo(s) prevent self-approval on protected branches",
                        rawPayload, "Deployment segregation of duties enforced", TAG);
            }
            return CheckResult.fail(
                    failures.size() + " of " + repoResults.size()
                            + " repo(s) allow a change author to approve or merge their own change: "
                            + String.join(", ", failures.subList(0, Math.min(failures.size(), 25))),
                    rawPayload, "Deployment segregation of duties incomplete", TAG);

        } catch (Exception e) {
            log.warn("[GITHUB_DEPLOYMENT_SEGREGATION] failed: {}", e.getMessage());
            return CheckResult.error("GitHub API error: " + e.getMessage(), TAG);
        }
    }
}
