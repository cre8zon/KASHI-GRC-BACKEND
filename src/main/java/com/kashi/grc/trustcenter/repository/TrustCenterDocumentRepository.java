package com.kashi.grc.trustcenter.repository;

import com.kashi.grc.trustcenter.domain.TrustCenterDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrustCenterDocumentRepository extends JpaRepository<TrustCenterDocument, Long> {
    List<TrustCenterDocument> findByTrustCenterIdOrderBySortOrderAsc(Long trustCenterId);
}
