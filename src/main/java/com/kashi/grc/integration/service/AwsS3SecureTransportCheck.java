package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.integration.spi.IntegrationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyRequest;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AwsS3SecureTransportCheck — encryption in transit.
 *
 *   check_key       AWS_S3_SECURE_TRANSPORT
 *   integration_key AWS
 *   capability      ENCRYPTION_IN_TRANSIT
 *   control_tag     CRY-01.2   Encryption in transit (TLS)
 *
 * ── WHY BUCKET POLICY AND NOT LOAD BALANCERS ────────────────────────────────
 * The obvious TLS check is ELB listener policies or CloudFront viewer protocol
 * policy. Both need the elbv2 or cloudfront SDK artifact, and your pom carries
 * only s3, iam, cloudtrail, guardduty and sesv2. Adding a dependency for one
 * check is a poor trade when a genuine in-transit control is reachable with
 * what is already there.
 *
 * S3 without an aws:SecureTransport deny accepts plain HTTP. Server-side
 * encryption does not help — the object is protected at rest and travels in
 * clear. So this is a real gap, not a proxy for one, and it is the in-transit
 * finding most likely to appear in an actual audit.
 *
 * ── WHAT PASS MEANS ─────────────────────────────────────────────────────────
 * Every bucket carries a policy statement with Effect=Deny and
 * Condition.Bool."aws:SecureTransport"=false. A bucket with no policy at all
 * fails: absence of a deny is permission.
 *
 * ── AUTH CONFIG ─────────────────────────────────────────────────────────────
 *   {"accessKeyId":"AKIA…","secretAccessKey":"…","region":"ap-south-1"}
 *   Read-only IAM: s3:ListAllMyBuckets, s3:GetBucketPolicy
 *
 * ── CHECK CONFIG (optional) ─────────────────────────────────────────────────
 *   {"region":"ap-south-1","maxFindings":25,"ignoreBuckets":["public-assets"]}
 *
 * The runner takes control_tag from the tenant catalogue row, not from TAG
 * below — see IntegrationRunner. TAG is kept accurate for the ERROR path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsS3SecureTransportCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;

    /** Advisory only — IntegrationRunner uses the tenant catalogue row. */
    private static final String TAG = "CRY-01.2";

    @Override public String checkKey()       { return "AWS_S3_SECURE_TRANSPORT"; }
    @Override public String integrationKey() { return "AWS"; }

    @Override
    public String remediation() {
        return "Attach a bucket policy denying requests where aws:SecureTransport is false. "
             + "Example statement: {\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":\"s3:*\","
             + "\"Resource\":[\"arn:aws:s3:::BUCKET\",\"arn:aws:s3:::BUCKET/*\"],"
             + "\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"false\"}}}. "
             + "Apply to every bucket holding regulated or customer data.";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            JsonNode auth = objectMapper.readTree(authConfig);
            String accessKey = auth.get("accessKeyId").asText();
            String secretKey = auth.get("secretAccessKey").asText();
            String region    = auth.path("region").asText("ap-south-1");

            int maxFindings = 25;
            Set<String> ignore = new HashSet<>();
            if (checkConfig != null && !checkConfig.isBlank()) {
                JsonNode cfg = objectMapper.readTree(checkConfig);
                region      = cfg.path("region").asText(region);
                maxFindings = cfg.path("maxFindings").asInt(25);
                if (cfg.has("ignoreBuckets")) cfg.get("ignoreBuckets").forEach(b -> ignore.add(b.asText()));
            }

            List<Map<String, Object>> results = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            int checked = 0;

            try (S3Client s3 = S3Client.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .build()) {

                for (Bucket bucket : s3.listBuckets().buckets()) {
                    String name = bucket.name();
                    if (ignore.contains(name)) continue;
                    checked++;

                    boolean enforced = false;
                    String detail;
                    try {
                        String policy = s3.getBucketPolicy(
                                GetBucketPolicyRequest.builder().bucket(name).build()).policy();
                        enforced = deniesInsecureTransport(policy);
                        detail = enforced ? "deny present" : "policy present, no SecureTransport deny";
                    } catch (Exception noPolicy) {
                        // No bucket policy at all. Absence of a deny is permission —
                        // the bucket accepts plain HTTP.
                        detail = "no bucket policy";
                    }

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("bucket", name);
                    r.put("secureTransportEnforced", enforced);
                    r.put("detail", detail);
                    results.add(r);
                    if (!enforced && failures.size() < maxFindings) failures.add(name);
                }
            }

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",     checkKey(),
                    "checkedAt",    LocalDateTime.now().toString(),
                    "region",       region,
                    "bucketsChecked", checked,
                    "buckets",      results));

            if (failures.isEmpty()) {
                return CheckResult.pass(
                        "All " + checked + " bucket(s) deny non-TLS requests",
                        rawPayload, "S3 secure transport enforced", TAG);
            }
            return CheckResult.fail(
                    failures.size() + " of " + checked + " bucket(s) accept plain HTTP: "
                            + String.join(", ", failures),
                    rawPayload, "S3 secure transport not enforced", TAG);

        } catch (Exception e) {
            log.warn("[AWS_S3_SECURE_TRANSPORT] failed: {}", e.getMessage());
            return CheckResult.error("AWS S3 error: " + e.getMessage(), TAG);
        }
    }

    /**
     * A policy qualifies when any statement denies on
     * Condition.Bool."aws:SecureTransport" = false.
     *
     * Read as JSON rather than string-matched: "aws:SecureTransport" can appear
     * in an Allow statement or under a different operator, and treating that as
     * enforcement would report a bucket as protected when it is not.
     */
    private boolean deniesInsecureTransport(String policyJson) {
        try {
            JsonNode root = objectMapper.readTree(policyJson);
            JsonNode statements = root.path("Statement");
            if (statements.isObject()) return statementDenies(statements);
            for (JsonNode s : statements) if (statementDenies(s)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean statementDenies(JsonNode s) {
        if (!"Deny".equalsIgnoreCase(s.path("Effect").asText())) return false;
        JsonNode v = s.path("Condition").path("Bool").path("aws:SecureTransport");
        if (v.isMissingNode()) return false;
        // AWS accepts the value as a string or an array of strings.
        if (v.isArray()) {
            for (JsonNode one : v) if ("false".equalsIgnoreCase(one.asText())) return true;
            return false;
        }
        return "false".equalsIgnoreCase(v.asText());
    }
}
