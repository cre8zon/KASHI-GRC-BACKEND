package com.kashi.grc.integration.service;

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
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AwsS3PublicAccessBlockCheck — verifies every S3 bucket has Public Access Block
 * fully enabled (all four flags: BlockPublicAcls, IgnorePublicAcls,
 * BlockPublicPolicy, RestrictPublicBuckets). A bucket with any flag off can be
 * exposed publicly — a common, high-impact misconfiguration.
 *
 * check_key:      AWS_S3_PUBLIC_ACCESS_BLOCK   ← matches the seeded catalog row
 * integration_key: AWS
 * control_tag:    IAM-03.1  (Least privilege / RBAC design)
 *                 was NET-01.2, which is "Firewall ruleset review" — unrelated
 * capability:     PUBLIC_ACCESS_BLOCK
 *
 * Uses ONLY the s3 artifact already in your pom.xml — no new dependency.
 *
 * authConfig: {"accessKeyId":"AKIA…","secretAccessKey":"…","region":"ap-south-1"}
 * Read-only IAM: s3:ListAllMyBuckets, s3:GetBucketPublicAccessBlock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsS3PublicAccessBlockCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;

    /** Advisory only — IntegrationRunner uses the tenant catalogue row. */
    private static final String TAG = "IAM-03.1";

    @Override public String checkKey()       { return "AWS_S3_PUBLIC_ACCESS_BLOCK"; }
    @Override public String integrationKey() { return "AWS"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Block public access: S3 Console > bucket > Permissions > Block public access > "
                        + "Edit, then tick all four settings. Check the account-level setting too (S3 Console "
                        + "> Block Public Access settings for this account), which overrides per-bucket config.";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            var auth = objectMapper.readTree(authConfig);
            String accessKey = auth.get("accessKeyId").asText();
            String secretKey = auth.get("secretAccessKey").asText();
            String region    = auth.path("region").asText("ap-south-1");

            int maxFindings = 25;
            if (checkConfig != null && !checkConfig.isBlank()) {
                var cfg = objectMapper.readTree(checkConfig);
                region = cfg.path("region").asText(region);
                maxFindings = cfg.path("maxFindings").asInt(25);
            }

            var creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));

            List<Map<String, Object>> buckets = new ArrayList<>();
            List<String> exposed    = new ArrayList<>();
            List<String> unreadable = new ArrayList<>();

            // listBuckets() is account-global but GetPublicAccessBlock is bound to
            // the bucket's own region. Without crossRegionAccessEnabled, a bucket
            // outside `region` returns 301 PermanentRedirect, which the old blanket
            // catch recorded as "not blocked" — a false FAIL on real evidence.
            try (S3Client s3 = S3Client.builder()
                    .region(Region.of(region))
                    .crossRegionAccessEnabled(true)
                    .credentialsProvider(creds).build()) {
                for (Bucket b : s3.listBuckets().buckets()) {
                    boolean fullyBlocked = false;
                    String  readError    = null;
                    try {
                        PublicAccessBlockConfiguration pab = s3.getPublicAccessBlock(
                                        GetPublicAccessBlockRequest.builder().bucket(b.name()).build())
                                .publicAccessBlockConfiguration();
                        fullyBlocked = Boolean.TRUE.equals(pab.blockPublicAcls())
                                && Boolean.TRUE.equals(pab.ignorePublicAcls())
                                && Boolean.TRUE.equals(pab.blockPublicPolicy())
                                && Boolean.TRUE.equals(pab.restrictPublicBuckets());
                    } catch (S3Exception s3e) {
                        String code = s3e.awsErrorDetails() != null
                                ? s3e.awsErrorDetails().errorCode() : null;
                        if ("NoSuchPublicAccessBlockConfiguration".equals(code)) {
                            fullyBlocked = false;        // genuinely no bucket-level PAB
                        } else {
                            readError = code != null ? code : s3e.getMessage();
                        }
                    } catch (Exception other) {
                        readError = other.getClass().getSimpleName();
                    }

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("bucket", b.name());
                    r.put("publicAccessBlocked", readError == null && fullyBlocked);
                    if (readError != null) r.put("readError", readError);
                    buckets.add(r);

                    if (readError != null)     unreadable.add(b.name());
                    else if (!fullyBlocked)    exposed.add(b.name());
                }
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("checkKey",      checkKey());
            payload.put("checkedAt",     LocalDateTime.now().toString());
            payload.put("clientRegion",  region);
            payload.put("totalBuckets",  buckets.size());
            payload.put("blocked",       buckets.size() - exposed.size() - unreadable.size());
            payload.put("notBlocked",    exposed.size());
            payload.put("unreadable",    unreadable.size());
            payload.put("scope",         "BUCKET_LEVEL_ONLY");
            payload.put("buckets",       buckets);
            String rawPayload = objectMapper.writeValueAsString(payload);

            if (buckets.isEmpty()) {
                // ListBuckets is account-global, so this is not a region problem.
                return CheckResult.fail(
                        "No S3 buckets exist in this AWS account — nothing to evidence",
                        rawPayload, "AWS S3 Public Access Block", TAG);
            }
            if (!unreadable.isEmpty()) {
                List<String> shownErr = unreadable.size() > maxFindings
                        ? unreadable.subList(0, maxFindings) : unreadable;
                return CheckResult.error(
                        unreadable.size() + " bucket(s) could not be read: "
                                + String.join(", ", shownErr)
                                + (unreadable.size() > maxFindings ? " …" : ""), TAG);
            }
            if (exposed.isEmpty()) {
                return CheckResult.pass(
                        "All " + buckets.size() + " S3 buckets have Public Access Block fully enabled",
                        rawPayload, "AWS S3 Public Access Block", TAG);
            }
            List<String> shown = exposed.size() > maxFindings ? exposed.subList(0, maxFindings) : exposed;
            return CheckResult.fail(
                    exposed.size() + " bucket(s) not fully public-access-blocked: " + String.join(", ", shown)
                            + (exposed.size() > maxFindings ? " …" : ""),
                    rawPayload, "AWS S3 Public Access Block", TAG);

        } catch (Exception e) {
            log.error("[AWS-S3-PAB] Failed: {}", e.getMessage());
            return CheckResult.error("AWS API error: " + e.getMessage(), TAG);
        }
    }
}