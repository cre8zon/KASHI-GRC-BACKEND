package com.kashi.grc.integration.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * IntegrationConfig — one connected integration per tenant.
 *
 * One row = one tenant has connected one integration (e.g. META tenant's Okta).
 * auth_config is AES-256 encrypted at the application layer before persisting.
 *
 * Supported integration keys:
 *   OKTA | AWS | GITHUB | AZURE | GOOGLE_WORKSPACE | CROWDSTRIKE | JAMF | JIRA
 */
@Entity
@Table(name = "integration_configs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_integration_tenant",
                columnNames = {"tenant_id", "integration_key"}
        ),
        indexes = {
                @Index(name = "idx_ic_tenant", columnList = "tenant_id"),
                @Index(name = "idx_ic_active", columnList = "is_active")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class IntegrationConfig extends TenantAwareEntity {

    @Column(name = "integration_key", nullable = false, length = 50)
    private String integrationKey;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    /**
     * AES-256 encrypted JSON containing auth credentials.
     * Shape varies by integration:
     *   Okta:             { "apiToken": "...", "domain": "company.okta.com" }
     *   AWS:              { "accessKeyId": "...", "secretAccessKey": "...", "region": "us-east-1" }
     *   GitHub:           { "token": "ghp_...", "org": "my-company" }
     *   Azure:            { "tenantId": "...", "clientId": "...", "clientSecret": "..." }
     *   Google Workspace: { "serviceAccountJson": "...", "adminEmail": "admin@company.com" }
     */
    @Column(name = "auth_config", columnDefinition = "TEXT")
    private String authConfig;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_status", length = 10)
    private String lastRunStatus; // SUCCESS | FAILURE | PARTIAL
}