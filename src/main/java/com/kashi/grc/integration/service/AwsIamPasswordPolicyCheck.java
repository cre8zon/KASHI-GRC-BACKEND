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
import software.amazon.awssdk.services.iam.model.PasswordPolicy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AwsIamPasswordPolicyCheck — verifies the account has an IAM password policy
 * meeting baseline strength (min length >= 14, requires upper/lower/number/symbol).
 *
 * check_key:      AWS_IAM_PASSWORD_POLICY   ← matches the seeded catalog row
 * integration_key: AWS
 * control_tag:    IAM-03.1  (password-policy leaf)
 * capability:     PASSWORD_POLICY
 *
 * NEW DEPENDENCY: same `iam` artifact as AwsRootMfaCheck (only add it once).
 *
 * authConfig: {"accessKeyId":"AKIA…","secretAccessKey":"…"}
 * checkConfig (optional): {"minLength":14}
 * Read-only IAM permission: iam:GetAccountPasswordPolicy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsIamPasswordPolicyCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;

    private static final String TAG = "IAM-03.1";

    @Override public String checkKey()       { return "AWS_IAM_PASSWORD_POLICY"; }
    @Override public String integrationKey() { return "AWS"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Set a policy: AWS Console > IAM > Account settings > Password policy > Edit. "
                        + "Match or exceed the minimum length and character requirements configured on this "
                        + "check, and enable password expiry and reuse prevention. "
                        + "https://console.aws.amazon.com/iam/home#/account_settings";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            var auth = objectMapper.readTree(authConfig);
            String accessKey = auth.get("accessKeyId").asText();
            String secretKey = auth.get("secretAccessKey").asText();

            int minLength = 14;
            if (checkConfig != null && !checkConfig.isBlank()) {
                minLength = objectMapper.readTree(checkConfig).path("minLength").asInt(14);
            }

            var creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));

            PasswordPolicy policy = null;
            boolean policyExists = true;
            try (IamClient iam = IamClient.builder()
                    .region(Region.AWS_GLOBAL).credentialsProvider(creds).build()) {
                try {
                    policy = iam.getAccountPasswordPolicy().passwordPolicy();
                } catch (software.amazon.awssdk.services.iam.model.NoSuchEntityException none) {
                    policyExists = false;   // no password policy set at all
                }
            }

            if (!policyExists || policy == null) {
                String rawPayload = objectMapper.writeValueAsString(Map.of(
                        "checkKey", checkKey(),
                        "checkedAt", LocalDateTime.now().toString(),
                        "passwordPolicyExists", false));
                return CheckResult.fail(
                        "No IAM password policy is configured for this AWS account",
                        rawPayload, "AWS IAM Password Policy", TAG);
            }

            List<String> gaps = new ArrayList<>();
            int actualMin = policy.minimumPasswordLength() == null ? 0 : policy.minimumPasswordLength();
            if (actualMin < minLength)                        gaps.add("min length " + actualMin + " < " + minLength);
            if (!Boolean.TRUE.equals(policy.requireUppercaseCharacters())) gaps.add("no uppercase required");
            if (!Boolean.TRUE.equals(policy.requireLowercaseCharacters())) gaps.add("no lowercase required");
            if (!Boolean.TRUE.equals(policy.requireNumbers()))            gaps.add("no number required");
            if (!Boolean.TRUE.equals(policy.requireSymbols()))            gaps.add("no symbol required");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("checkKey", checkKey());
            payload.put("checkedAt", LocalDateTime.now().toString());
            payload.put("passwordPolicyExists", true);
            payload.put("minimumLength", actualMin);
            payload.put("requireUppercase", policy.requireUppercaseCharacters());
            payload.put("requireLowercase", policy.requireLowercaseCharacters());
            payload.put("requireNumbers", policy.requireNumbers());
            payload.put("requireSymbols", policy.requireSymbols());
            payload.put("gaps", gaps);
            String rawPayload = objectMapper.writeValueAsString(payload);

            if (gaps.isEmpty()) {
                return CheckResult.pass(
                        "IAM password policy meets baseline (>=" + minLength + " chars, all character classes)",
                        rawPayload, "AWS IAM Password Policy", TAG);
            }
            return CheckResult.fail(
                    "IAM password policy weaknesses: " + String.join("; ", gaps),
                    rawPayload, "AWS IAM Password Policy", TAG);

        } catch (Exception e) {
            log.error("[AWS-IAM-PWD] Failed: {}", e.getMessage());
            return CheckResult.error("AWS API error: " + e.getMessage(), TAG);
        }
    }
}