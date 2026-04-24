package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// ================================================================
// TaskSectionItemCompletion.java  (Case 3)
// ================================================================
@Entity
@Table(name = "task_section_item_completions")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TaskSectionItemCompletion extends BaseEntity {

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "task_instance_id", nullable = false)
    private Long taskInstanceId;

    @Column(name = "section_key", nullable = false, length = 100)
    private String sectionKey;

    @Column(name = "completed_by", nullable = false)
    private Long completedBy;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    @Column(name = "outcome", length = 100)
    private String outcome;

    @Column(name = "score", precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "artifact_type", length = 100)
    private String artifactType;

    @Column(name = "artifact_id")
    private Long artifactId;
}
