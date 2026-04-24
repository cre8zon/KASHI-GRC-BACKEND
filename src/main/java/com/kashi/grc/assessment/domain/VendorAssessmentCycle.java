package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vendor_assessment_cycles")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class VendorAssessmentCycle extends TenantAwareEntity {

    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Column(name = "cycle_no")
    private Integer cycleNo;

    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;

    @Column(name = "status")
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    @Column(name = "triggered_by")
    private Long triggeredBy;
}
