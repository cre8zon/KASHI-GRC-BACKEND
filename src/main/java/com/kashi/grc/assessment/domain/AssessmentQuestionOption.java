package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A reusable response option in the library.
 *
 * DESIGN: This entity has NO foreign key to AssessmentQuestion.
 * The option↔question relationship is managed via QuestionOptionMapping (join table).
 * One option can be linked to many questions — zero duplication.
 *
 * tenant_id = null  → global option created by Platform Admin
 * tenant_id = X     → private option created by org X
 */
@Entity
@Table(name = "assessment_question_options")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@org.hibernate.annotations.SQLRestriction("is_deleted = false")
public class AssessmentQuestionOption extends GlobalOrTenantEntity implements com.kashi.grc.common.domain.SoftDeletable {

    @Column(name = "option_value", nullable = false, length = 500)
    private String optionValue;

    @Column(name = "score")
    private Double score;
    // -- SOFT DELETE ----------------------------------------------------------
    //
    // Same four columns and the same @SQLRestriction the audit library carries,
    // for the same reason: deleting a library row used to remove it AND its
    // mappings, so every template built on it lost the row with no trace of
    // what had been there.
    //
    // @SQLRestriction covers HQL, criteria queries and collection loads - which
    // is every module list, since they all run through DbRepository's criteria
    // builder. It is NOT applied to a load by primary key, so findById still
    // returns a retired row; that is what makes restore work.
    //
    // ddl-auto=update adds the columns on the next boot. sql/114 adds the index.

    @Column(name = "is_deleted", nullable = false)
    @lombok.Builder.Default
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private java.time.LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "deletion_reason", length = 500)
    private String deletionReason;
}