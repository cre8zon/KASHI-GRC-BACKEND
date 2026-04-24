package com.kashi.grc.document.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "vendor_documents")
@Getter @Setter @lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class VendorDocument extends TenantAwareEntity {

    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;
}
