package com.kashi.grc.common.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplateConfig — provides a shared RestTemplate bean for integration checks.
 *
 * Used by: OktaAdminMfaCheck and any future IntegrationCheck implementations.
 *
 * Timeouts:
 *   connectTimeout = 5s  — max time to establish connection to external API
 *   readTimeout    = 15s — max time to wait for API response
 *                          (Okta /factors endpoint can be slow for large orgs)
 *
 * Using RestTemplateBuilder (Spring Boot's recommended approach) rather than
 * raw HttpComponentsClientHttpRequestFactory — no extra dependency needed.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .build();
    }
}