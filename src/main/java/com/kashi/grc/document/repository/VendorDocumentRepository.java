package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.VendorDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VendorDocumentRepository extends JpaRepository<VendorDocument, Long> {
    List<VendorDocument> findByVendorId(Long vendorId);
}
