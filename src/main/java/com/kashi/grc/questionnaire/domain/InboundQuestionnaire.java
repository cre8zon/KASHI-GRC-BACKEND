package com.kashi.grc.questionnaire.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A questionnaire somebody sent TO US.
 *
 * ── NOT VendorAssessment, AND THE DIFFERENCE MATTERS ──────────────────────
 * The assessment module sends questionnaires to vendors: a campaign we control,
 * from a template we wrote. This is the opposite direction — a CAIQ, a SIG
 * Lite, a bank's 200-row spreadsheet — which arrives in whatever shape the
 * customer chose and has to go back in that same shape.
 *
 * That is why sourceDocumentId is kept: a customer who sent a spreadsheet wants
 * a spreadsheet back, not a PDF of our own design.
 */
@Entity
@Table(name = "inbound_questionnaires",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_iq_ref", columnNames = {"tenant_id", "questionnaire_ref"}),
       indexes = @Index(name = "idx_iq_status", columnList = "tenant_id,status,is_deleted"))
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class InboundQuestionnaire extends AuditableEntity {

    @Column(name = "questionnaire_ref", nullable = false, length = 40)
    private String questionnaireRef;

    @Column(nullable = false, length = 250)
    private String title;

    /** Free text, not a vendor link: the sender is a CUSTOMER or prospect, and
     *  the vendor table is the other direction entirely. */
    @Column(name = "requester_org", length = 200)     private String requesterOrg;
    @Column(name = "requester_contact", length = 200) private String requesterContact;

    /** CAIQ | SIG_LITE | VSA | CUSTOM — a known format can be parsed and
     *  exported with its own column layout. */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String format = "CUSTOM";

    @Column(name = "source_document_id") private Long sourceDocumentId;
    @Column(name = "due_date")           private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.RECEIVED;

    @Column(name = "assigned_to")  private Long assignedTo;
    @Column(name = "submitted_at") private LocalDateTime submittedAt;
    @Column(name = "submitted_by") private Long submittedBy;

    public enum Status {
        RECEIVED, PARSING, DRAFTING, IN_REVIEW, COMPLETED, SUBMITTED, CANCELLED
    }
}
