package com.kashi.grc.document.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3Config — AWS SDK v2 bean configuration.
 *
 * CREDENTIALS: DefaultCredentialsProvider resolves in order:
 *   1. Environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY) — local dev
 *   2. ~/.aws/credentials file — local dev
 *   3. EC2 instance profile / ECS task role — PRODUCTION (preferred, no creds in code)
 *
 * pom.xml BOM:
 *   software.amazon.awssdk:bom:2.26.29  (import scope)
 *   software.amazon.awssdk:s3
 *
 * Image conversion:
 *   com.twelvemonkeys.imageio:imageio-core:3.10.1
 *   com.twelvemonkeys.imageio:imageio-jpeg:3.10.1
 *   com.twelvemonkeys.imageio:imageio-tiff:3.10.1
 *   com.twelvemonkeys.imageio:imageio-webp:3.10.1
 *
 * application.properties keys:
 *   aws.s3.region=us-east-1
 *   aws.s3.bucket=kashi-grc-documents-${spring.profiles.active}
 *   aws.s3.kms-key-arn=arn:aws:kms:us-east-1:ACCOUNT_ID:key/KEY_ID
 */
@Configuration
public class S3Config {

    @Value("${aws.s3.region:us-east-1}")
    private String awsRegion;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}