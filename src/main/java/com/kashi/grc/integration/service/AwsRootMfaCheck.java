package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.integration.spi.IntegrationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.GetAccountSummaryResponse;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AwsRootMfaCheck — verifies the AWS account ROOT user has MFA enabled. Root is
 * the most privileged identity in an AWS account; root MFA is a baseline control
 * in every cloud-security benchmark (CIS AWS 1.5).
 *
 * check_key:      AWS_ROOT_MFA          ← matches the seeded catalog row
 * integration_key: AWS
 * control_tag:    IAM-02.3  (privileged-account MFA leaf — SAME tag as the other
 *                            admin-MFA checks, so via capability MFA_ADMIN this
 *                            can back the same MFA tests.)
 * capability:     MFA_ADMIN
 *
 * IAM's GetAccountSummary returns "AccountMFAEnabled" = 1 when the root user has
 * MFA. This is account-global (no region), but IAM is a global service reached
 * via us-east-1.
 *
 * NEW DEPENDENCY REQUIRED (add to pom.xml):
 *   <dependency>
 *     <groupId>software.amazon.awssdk</groupId>
 *     <artifactId>iam</artifactId>
 *   </dependency>
 * (Version is managed by the AWS SDK BOM you already import for s3.)
 *
 * authConfig: {"accessKeyId":"AKIA…","secretAccessKey":"…"}
 * Read-only IAM permission: iam:GetAccountSummary
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsRootMfaCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;

    private static final String TAG = "IAM-02.3";

    @Override public String checkKey()       { return "AWS_ROOT_MFA"; }
    @Override public String integrationKey() { return "AWS"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Enable MFA on the root user: sign in as root > Security credentials > "
                        + "Multi-factor authentication > Assign MFA device. This check covers the root "
                        + "account only; MFA for other IAM users is a separate control. "
                        + "https://console.aws.amazon.com/iam/home#/security_credentials";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            var auth = objectMapper.readTree(authConfig);
            String accessKey = auth.get("accessKeyId").asText();
            String secretKey = auth.get("secretAccessKey").asText();

            var creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));

            int rootMfa;
            try (IamClient iam = IamClient.builder()
                    .region(Region.AWS_GLOBAL)          // IAM is global
                    .credentialsProvider(creds).build()) {
                GetAccountSummaryResponse summary = iam.getAccountSummary();
                rootMfa = summary.summaryMap()
                        .getOrDefault(software.amazon.awssdk.services.iam.model.SummaryKeyType.ACCOUNT_MFA_ENABLED, 0);
            }

            boolean enabled = rootMfa == 1;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("checkKey", checkKey());
            payload.put("checkedAt", LocalDateTime.now().toString());
            payload.put("accountMfaEnabled", enabled);
            String rawPayload = objectMapper.writeValueAsString(payload);

            if (enabled) {
                return CheckResult.pass("AWS account root user has MFA enabled",
                        rawPayload, "AWS Root MFA", TAG);
            }
            return CheckResult.fail("AWS account root user does NOT have MFA enabled — enable it immediately",
                    rawPayload, "AWS Root MFA", TAG);

        } catch (Exception e) {
            log.error("[AWS-ROOT-MFA] Failed: {}", e.getMessage());
            return CheckResult.error("AWS API error: " + e.getMessage(), TAG);
        }
    }
}