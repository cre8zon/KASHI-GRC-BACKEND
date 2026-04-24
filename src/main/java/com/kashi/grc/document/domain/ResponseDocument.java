package com.kashi.grc.document.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "response_documents")
@Getter @Setter @lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class ResponseDocument extends BaseEntity {

    @Column(name = "response_id", nullable = false)
    private Long responseId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;
}
