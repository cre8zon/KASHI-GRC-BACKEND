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
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AwsS3EncryptionCheck — implementation behind the existing seeded check
 * integration_checks.id = 8  (check_key = AWS_S3_ENCRYPTION).
 *
 * check_key:      AWS_S3_ENCRYPTION       ← MUST match the seeded row exactly
 * integration_key: AWS
 * control_tag:    CRY-01.1  (catalogue leaf; the seed row's legacy
 *                            'ENCRYPTION_AT_REST' is realigned to this in
 *                            integration_checks_align.sql)
 *
 * The runner reads the tag from the tenant_integration_checks row, not from
 * this class, so returning CRY-01.1 here AND aligning the seed keeps them
 * consistent. Evidence then fans out via the Phase 3 matcher to ISO A.8.24,
 * SOC 2 CC6.1, DPDP S.8(5).
 *
 * ── AUTH CONFIG ──────────────────────────────────────────────────────────────
 *   {"accessKeyId":"AKIA…","secretAccessKey":"…","region":"ap-south-1"}
 *   Read-only IAM: s3:ListAllMyBuckets, s3:GetBucketEncryption
 *
 * ── CHECK CONFIG (seed row 8 has none; these are optional) ──────────────────
 *   {"region":"ap-south-1","maxFindings":25}
 *
 * pass_criteria on the seed row: {"type":"ALL_PASS","field":"encrypted","value":true}
 * — honoured implicitly: PASS iff every bucket is encrypted.
 *
 * Uses only the s3 artifact already in your pom.xml (no ec2 needed).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsS3EncryptionCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;

    private static final String TAG = "CRY-01.1";

    @Override public String checkKey()       { return "AWS_S3_ENCRYPTION"; }
    @Override public String integrationKey() { return "AWS"; }

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
            List<String> unencrypted = new ArrayList<>();

            try (S3Client s3 = S3Client.builder()
                    .region(Region.of(region)).credentialsProvider(creds).build()) {
                for (Bucket b : s3.listBuckets().buckets()) {
                    boolean enc;
                    try {
                        s3.getBucketEncryption(GetBucketEncryptionRequest.builder()
                                .bucket(b.name()).build());
                        enc = true;   // succeeds only when SSE is configured
                    } catch (Exception noEnc) {
                        enc = false;  // ServerSideEncryptionConfigurationNotFoundError
                    }
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("bucket", b.name());
                    r.put("encrypted", enc);
                    buckets.add(r);
                    if (!enc) unencrypted.add(b.name());
                }
            }

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",     checkKey(),
                    "checkedAt",    LocalDateTime.now().toString(),
                    "region",       region,
                    "totalBuckets", buckets.size(),
                    "encrypted",    buckets.size() - unencrypted.size(),
                    "unencrypted",  unencrypted.size(),
                    "buckets",      buckets));

            if (buckets.isEmpty()) {
                return CheckResult.fail(
                        "No S3 buckets found in " + region + " — verify region and IAM permissions",
                        rawPayload, "AWS S3 Encryption", TAG);
            }
            if (unencrypted.isEmpty()) {
                return CheckResult.pass(
                        "All " + buckets.size() + " S3 buckets encrypted at rest",
                        rawPayload, "AWS S3 Encryption", TAG);
            }
            List<String> shown = unencrypted.size() > maxFindings
                    ? unencrypted.subList(0, maxFindings) : unencrypted;
            return CheckResult.fail(
                    unencrypted.size() + " bucket(s) not encrypted: " + String.join(", ", shown)
                            + (unencrypted.size() > maxFindings ? " …" : ""),
                    rawPayload, "AWS S3 Encryption", TAG);

        } catch (Exception e) {
            log.error("[AWS-S3-ENCRYPTION] Failed: {}", e.getMessage());
            return CheckResult.error("AWS API error: " + e.getMessage(), TAG);
        }
    }
}