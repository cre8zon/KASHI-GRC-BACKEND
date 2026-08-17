package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.integration.spi.IntegrationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.GetTrailStatusRequest;
import software.amazon.awssdk.services.cloudtrail.model.Trail;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AwsCloudTrailCheck — verifies at least one CloudTrail trail exists AND is
 * actively logging (multi-region preferred). CloudTrail is the audit-log source
 * for AWS; without it, there's no record of API activity.
 *
 * check_key:      AWS_CLOUDTRAIL_ENABLED   ← matches the seeded catalog row
 * integration_key: AWS
 * control_tag:    LOG-01.1  (audit-logging leaf)
 * capability:     AUDIT_LOGGING   ← makes the ISO/SOC2 logging tests resolve here
 *
 * NEW DEPENDENCY REQUIRED (add to pom.xml):
 *   <dependency>
 *     <groupId>software.amazon.awssdk</groupId>
 *     <artifactId>cloudtrail</artifactId>
 *   </dependency>
 *
 * authConfig: {"accessKeyId":"AKIA…","secretAccessKey":"…","region":"ap-south-1"}
 * Read-only IAM: cloudtrail:DescribeTrails, cloudtrail:GetTrailStatus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsCloudTrailCheck implements IntegrationCheck {

    private final ObjectMapper objectMapper;

    private static final String TAG = "LOG-01.1";

    @Override public String checkKey()       { return "AWS_CLOUDTRAIL_ENABLED"; }
    @Override public String integrationKey() { return "AWS"; }

    /** How to make this check pass -- shown beside the failure in the UI. */
    @Override
    public String remediation() {
        return
                "Create a trail: AWS Console > CloudTrail > Trails > Create trail. Tick "
                        + "'Apply trail to all regions' and enable log file validation, otherwise activity "
                        + "outside the trail's home region is not recorded. The first trail is free for "
                        + "management events. https://console.aws.amazon.com/cloudtrail/home";
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

            List<Map<String, Object>> trails = new ArrayList<>();
            int loggingCount = 0;
            boolean anyMultiRegion = false;

            try (CloudTrailClient ct = CloudTrailClient.builder()
                    .region(Region.of(region)).credentialsProvider(creds).build()) {
                for (Trail t : ct.describeTrails().trailList()) {
                    boolean logging = false;
                    try {
                        logging = Boolean.TRUE.equals(ct.getTrailStatus(
                                GetTrailStatusRequest.builder().name(t.trailARN()).build()).isLogging());
                    } catch (Exception ignore) { /* status not readable → treat as not logging */ }

                    boolean multiRegion = Boolean.TRUE.equals(t.isMultiRegionTrail());
                    if (logging) loggingCount++;
                    if (logging && multiRegion) anyMultiRegion = true;

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", t.name());
                    r.put("multiRegion", multiRegion);
                    r.put("logging", logging);
                    trails.add(r);
                }
            }

            String rawPayload = objectMapper.writeValueAsString(Map.of(
                    "checkKey",      checkKey(),
                    "checkedAt",     LocalDateTime.now().toString(),
                    "region",        region,
                    "totalTrails",   trails.size(),
                    "loggingTrails", loggingCount,
                    "anyMultiRegionLogging", anyMultiRegion,
                    "trails",        trails));

            if (loggingCount == 0) {
                return CheckResult.fail(
                        trails.isEmpty()
                                ? "No CloudTrail trails found — API activity is not being logged"
                                : trails.size() + " trail(s) exist but none are actively logging",
                        rawPayload, "AWS CloudTrail Logging", TAG);
            }
            String note = anyMultiRegion ? " (multi-region)" : " (single-region — consider multi-region)";
            return CheckResult.pass(
                    loggingCount + " CloudTrail trail(s) actively logging" + note,
                    rawPayload, "AWS CloudTrail Logging", TAG);

        } catch (Exception e) {
            log.error("[AWS-CLOUDTRAIL] Failed: {}", e.getMessage());
            return CheckResult.error("AWS API error: " + e.getMessage(), TAG);
        }
    }
}