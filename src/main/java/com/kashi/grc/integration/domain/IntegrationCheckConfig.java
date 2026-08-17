package com.kashi.grc.integration.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * IntegrationCheckConfig — runtime config for a specific check on a specific tenant.
 * Global checks (tenant_id=null) are seeded from integration_checks table.
 * Tenant-specific overrides have tenant_id set.
 */
@Entity
@Table(name = "integration_checks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_check_key", columnNames = {"integration_key", "check_key"}))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class IntegrationCheckConfig extends TenantAwareEntity {
    @Column(name = "integration_key",   nullable = false, length = 50)  private String integrationKey;
    @Column(name = "check_key",         nullable = false, length = 100) private String checkKey;
    @Column(name = "display_name",      nullable = false, length = 300) private String displayName;
    @Column(name = "description",       columnDefinition = "TEXT")      private String description;
    @Column(name = "control_tag",       nullable = false, length = 80)  private String controlTag;
    @Column(name = "capability",        length = 60)                    private String capability;   // vendor-neutral capability, e.g. MFA_ADMIN
    @Column(name = "run_frequency",     nullable = false, length = 10)  @Builder.Default private String runFrequency = "DAILY";
    @Column(name = "is_active",         nullable = false)               @Builder.Default private boolean isActive = true;
    @Column(name = "check_config_json", columnDefinition = "TEXT")      private String checkConfigJson;
    @Column(name = "pass_criteria_json",columnDefinition = "TEXT")      private String passCriteriaJson;
    @Column(name = "last_run_at")                                        private LocalDateTime lastRunAt;
    @Column(name = "last_run_status",   length = 10)                    private String lastRunStatus;
    @Column(name = "next_run_at")                                        private LocalDateTime nextRunAt;
}