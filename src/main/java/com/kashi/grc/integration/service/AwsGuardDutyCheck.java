package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.integration.spi.IntegrationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.guardduty.GuardDutyClient;
import software.amazon.awssdk.services.guardduty.model.GetDetectorRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AwsGuardDutyCheck — verifies AWS GuardDuty (threat detection) is enabled in the
 * region. GuardDuty continuously monitors for malicious activity; "enabled" means
 * at least one detector exists and is in ENABLED status.
 *
 * check_key:      AWS_GUARDDUTY_ENABLED   ← matches the seeded catalog row
 * integration_key: AWS
 * control_tag:    MON-01.2  (threat-detection / monitoring leaf)
 * capability:     THREAT_DETECTION
 *
 * NEW DEPENDENCY REQUIRED (add to pom.xml):
 *   <dependency>
 *     <groupId>software.amazon.awssdk</groupId>
 *     <artifactId>guardduty</artifactId>
 *   </dependency>
 *
 * authConfig: {"accessKeyId":"AKIA…","secretAccessKey":"…","region":"ap-south-1"}
 * Read-only IAM: guardduty:ListDetectors, guardduty:GetDetector
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsGuardDutyCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;

    private static final String TAG = "MON-01.2";

    @Override public String checkKey()       { return "AWS_GUARDDUTY_ENABLED"; }
    @Override public String integrationKey() { return "AWS"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Enable GuardDuty in this region: AWS Console > GuardDuty > Get started > Enable. "
                        + "GuardDuty is regional, so it must be enabled in every region you run workloads in -- "
                        + "enabling it in one region does not cover the others. "
                        + "https://console.aws.amazon.com/guardduty/home";
    }

    @Override
    public CheckResult run(String authConfig, String checkConfig) {
        try {
            var auth = objectMapper.readTree(authConfig);
            String accessKey = auth.get("accessKeyId").asText();
            String secretKey = auth.get("secretAccessKey").asText();
            String region    = auth.path("region").asText("ap-south-1");
            if (checkConfig != null && !checkConfig.isBlank()) {
                region = objectMapper.readTree(checkConfig).path("region").asText(region);
            }

            var creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));

            List<String> detectorIds;
            int enabledCount = 0;

            try (GuardDutyClient gd = GuardDutyClient.builder()
                    .region(Region.of(region)).credentialsProvider(creds).build()) {
                detectorIds = gd.listDetectors().detectorIds();
                for (String id : detectorIds) {
                    try {
                        var status = gd.getDetector(GetDetectorRequest.builder().detectorId(id).build())
                                .statusAsString();
                        if ("ENABLED".equalsIgnoreCase(status)) enabledCount++;
                    } catch (Exception ignore) { /* skip unreadable detector */ }
                }
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("checkKey", checkKey());
            payload.put("checkedAt", LocalDateTime.now().toString());
            payload.put("region", region);
            payload.put("totalDetectors", detectorIds.size());
            payload.put("enabledDetectors", enabledCount);
            String rawPayload = objectMapper.writeValueAsString(payload);

            if (enabledCount > 0) {
                return CheckResult.pass(
                        "GuardDuty threat detection is ENABLED in " + region,
                        rawPayload, "AWS GuardDuty", TAG);
            }
            return CheckResult.fail(
                    "GuardDuty is NOT enabled in " + region + " — no active threat detection",
                    rawPayload, "AWS GuardDuty", TAG);

        } catch (Exception e) {
            log.error("[AWS-GUARDDUTY] Failed: {}", e.getMessage());
            return CheckResult.error("AWS API error: " + e.getMessage(), TAG);
        }
    }
}