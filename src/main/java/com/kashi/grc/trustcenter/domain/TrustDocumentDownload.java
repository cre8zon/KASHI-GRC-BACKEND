package com.kashi.grc.trustcenter.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Every fetch, logged.
 *
 * This is the table that answers "who has our SOC 2 report, and when did they
 * get it" — a question that gets asked during an incident, a renewal
 * negotiation, or a dispute, and which a signed URL emailed months ago cannot
 * answer at all.
 */
@Entity
@Table(name = "trust_document_downloads",
       indexes = {
           @Index(name = "idx_tdd_request", columnList = "request_id,downloaded_at"),
           @Index(name = "idx_tdd_doc",     columnList = "tenant_id,trust_document_id,downloaded_at"),
       })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TrustDocumentDownload extends TenantAwareEntity {

    @Column(name = "request_id", nullable = false)        private Long requestId;
    @Column(name = "trust_document_id", nullable = false) private Long trustDocumentId;

    @Column(name = "downloaded_at", nullable = false) private LocalDateTime downloadedAt;
    @Column(name = "source_ip", length = 64) private String sourceIp;
}
