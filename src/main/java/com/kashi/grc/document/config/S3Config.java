package com.kashi.grc.document.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3Config — AWS SDK v2 bean configuration.
 *
 * CREDENTIALS: credentialsProvider() is a single shared @Bean used by both
 *   S3Client and S3Presigner so they always sign with the same identity.
 *   Resolution order:
 *     1. ${aws.s3.access-key} / ${aws.s3.secret-key} from .env (local dev / non-EC2 prod)
 *     2. DefaultCredentialsProvider — env vars, ~/.aws/credentials, EC2 instance profile
 *
 * SDK VERSION: software.amazon.awssdk:bom:2.27.21 (import scope in dependencyManagement).
 *   The s3 dependency must have NO explicit <version> — let the BOM manage it.
 *   Mismatching BOM vs artifact version causes S3Presigner to sign with wrong HTTP
 *   method (GET instead of PUT), which produces 403 SignatureDoesNotMatch on upload.
 */
@Configuration
public class S3Config {

    @Value("${aws.s3.region:ap-south-1}")
    private String awsRegion;

    @Value("${aws.s3.access-key:}")
    private String accessKeyId;

    @Value("${aws.s3.secret-key:}")
    private String secretAccessKey;

    /**
     * Single shared credentials provider — both S3Client and S3Presigner use this
     * bean so they are guaranteed to sign with the same credentials.
     *
     * Previously credentialsProvider() was a private method called twice, which
     * meant two separate provider instances. While functionally equivalent for
     * StaticCredentialsProvider, it caused subtle issues with DefaultCredentialsProvider
     * when credential refresh timing differed between the two instances.
     */
    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        if (accessKeyId != null && !accessKeyId.isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        }
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public S3Client s3Client(AwsCredentialsProvider awsCredentialsProvider) {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(awsCredentialsProvider)
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(AwsCredentialsProvider awsCredentialsProvider) {
        return S3Presigner.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(awsCredentialsProvider)
                .build();
    }
}