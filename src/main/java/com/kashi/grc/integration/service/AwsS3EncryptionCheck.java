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
import software.amazon.awssdk.services.s3.model.S3Exception;

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

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Enable default encryption: S3 Console > bucket > Properties > Default encryption "
                        + "> Edit. Every bucket has SSE-S3 by default since January 2023, so a failure here "
                        + "usually means requireKms is set and the bucket has no KMS key -- choose SSE-KMS "
                        + "and select a customer-managed key.";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            var auth = objectMapper.readTree(authConfig);
            String accessKey = auth.get("accessKeyId").asText();
            String secretKey = auth.get("secretAccessKey").asText();
            String region    = auth.path("region").asText("ap-south-1");

            int     maxFindings = 25;
            boolean requireKms  = false;
            if (checkConfig != null && !checkConfig.isBlank()) {
                var cfg = objectMapper.readTree(checkConfig);
                region = cfg.path("region").asText(region);
                maxFindings = cfg.path("maxFindings").asInt(25);
                // Since Jan-2023 every bucket returns SSE-S3 by default, so a plain
                // "is encryption configured" test passes trivially. Set requireKms
                // when the control demands customer-managed keys.
                requireKms = cfg.path("requireKms").asBoolean(false);
            }

            var creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));

            List<Map<String, Object>> buckets = new ArrayList<>();
            List<String> unencrypted = new ArrayList<>();
            List<String> unreadable  = new ArrayList<>();

            // listBuckets() is account-global but every per-bucket call is bound to
            // the bucket's own region. Without crossRegionAccessEnabled, a bucket
            // outside `region` returns 301 PermanentRedirect, which the old blanket
            // catch recorded as "not encrypted" — a false FAIL on real evidence.
            try (S3Client s3 = S3Client.builder()
                    .region(Region.of(region))
                    .crossRegionAccessEnabled(true)
                    .credentialsProvider(creds).build()) {
                for (Bucket b : s3.listBuckets().buckets()) {
                    boolean enc       = false;
                    String  algorithm = null;
                    String  kmsKeyId  = null;
                    String  readError = null;
                    try {
                        var rules = s3.getBucketEncryption(GetBucketEncryptionRequest.builder()
                                        .bucket(b.name()).build())
                                .serverSideEncryptionConfiguration().rules();
                        if (!rules.isEmpty()
                                && rules.get(0).applyServerSideEncryptionByDefault() != null) {
                            var def   = rules.get(0).applyServerSideEncryptionByDefault();
                            algorithm = def.sseAlgorithmAsString();   // AES256 | aws:kms | aws:kms:dsse
                            kmsKeyId  = def.kmsMasterKeyID();
                            enc       = !requireKms
                                    || (algorithm != null && algorithm.startsWith("aws:kms"));
                        }
                    } catch (S3Exception s3e) {
                        String code = s3e.awsErrorDetails() != null
                                ? s3e.awsErrorDetails().errorCode() : null;
                        if ("ServerSideEncryptionConfigurationNotFoundError".equals(code)) {
                            enc = false;                 // genuinely unencrypted
                        } else {
                            readError = code != null ? code : s3e.getMessage();
                        }
                    } catch (Exception other) {
                        readError = other.getClass().getSimpleName();
                    }

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("bucket", b.name());
                    r.put("encrypted", readError == null && enc);
                    r.put("algorithm", algorithm);
                    r.put("kmsKeyId", kmsKeyId);
                    if (readError != null) r.put("readError", readError);
                    buckets.add(r);

                    if (readError != null)   unreadable.add(b.name());
                    else if (!enc)           unencrypted.add(b.name());
                }
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("checkKey",     checkKey());
            payload.put("checkedAt",    LocalDateTime.now().toString());
            payload.put("clientRegion", region);
            payload.put("requireKms",   requireKms);
            payload.put("totalBuckets", buckets.size());
            payload.put("encrypted",    buckets.size() - unencrypted.size() - unreadable.size());
            payload.put("unencrypted",  unencrypted.size());
            payload.put("unreadable",   unreadable.size());
            payload.put("buckets",      buckets);
            String rawPayload = objectMapper.writeValueAsString(payload);

            if (buckets.isEmpty()) {
                // ListBuckets is account-global, so this is not a region problem.
                return CheckResult.fail(
                        "No S3 buckets exist in this AWS account — nothing to evidence",
                        rawPayload, "AWS S3 Encryption", TAG);
            }
            if (!unreadable.isEmpty()) {
                // Cannot assert compliance over buckets we could not read. ERROR
                // rather than FAIL — this is an integration gap, not a finding.
                List<String> shownErr = unreadable.size() > maxFindings
                        ? unreadable.subList(0, maxFindings) : unreadable;
                return CheckResult.error(
                        unreadable.size() + " bucket(s) could not be read: "
                                + String.join(", ", shownErr)
                                + (unreadable.size() > maxFindings ? " …" : ""), TAG);
            }
            if (unencrypted.isEmpty()) {
                return CheckResult.pass(
                        "All " + buckets.size() + " S3 buckets encrypted at rest"
                                + (requireKms ? " with KMS keys" : ""),
                        rawPayload, "AWS S3 Encryption", TAG);
            }
            List<String> shown = unencrypted.size() > maxFindings
                    ? unencrypted.subList(0, maxFindings) : unencrypted;
            return CheckResult.fail(
                    unencrypted.size() + (requireKms
                            ? " bucket(s) not encrypted with a KMS key: "
                            : " bucket(s) not encrypted: ") + String.join(", ", shown)
                            + (unencrypted.size() > maxFindings ? " …" : ""),
                    rawPayload, "AWS S3 Encryption", TAG);

        } catch (Exception e) {
            log.error("[AWS-S3-ENCRYPTION] Failed: {}", e.getMessage());
            return CheckResult.error("AWS API error: " + e.getMessage(), TAG);
        }
    }
}