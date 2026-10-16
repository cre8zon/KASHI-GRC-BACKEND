package com.kashi.grc.enquiry.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * The enquiry's timeline: notes people write, and a row for every change the
 * system makes (status, owner, conversion). Append-only — a note is corrected
 * by writing another one, so the history of a deal is never rewritten.
 */
@Entity
@Table(name = "enquiry_activities", indexes = @Index(name = "ix_enquiry_activity_enquiry", columnList = "enquiry_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class EnquiryActivity extends BaseEntity {

    @Column(name = "enquiry_id", nullable = false)
    private Long enquiryId;

    /** RECEIVED | NOTE | STATUS | ASSIGNED | PRIORITY | CONVERTED | EMAIL */
    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "from_value", length = 60)
    private String fromValue;

    @Column(name = "to_value", length = 60)
    private String toValue;

    /** NULL for rows written by the system (the website submission). */
    @Column(name = "author_id")
    private Long authorId;
}
