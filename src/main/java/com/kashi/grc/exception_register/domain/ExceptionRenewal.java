package com.kashi.grc.exception_register.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One extension, kept forever.
 *
 * "Extended four times" is a different fact from "expires in March", and only
 * the history shows it. Overwriting expires_at without recording what it was
 * loses the only evidence that an exception has been rolling for two years.
 */
@Entity
@Table(name = "exception_renewals",
       indexes = @Index(name = "idx_exc_renewal", columnList = "exception_id,renewed_at"))
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class ExceptionRenewal extends TenantAwareEntity {

    @Column(name = "exception_id", nullable = false)
    private Long exceptionId;

    @Column(name = "previous_expiry", nullable = false)
    private LocalDateTime previousExpiry;

    @Column(name = "new_expiry", nullable = false)
    private LocalDateTime newExpiry;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    @Column(name = "renewed_by")  private Long renewedBy;

    @Column(name = "renewed_at", nullable = false)
    private LocalDateTime renewedAt;
}
