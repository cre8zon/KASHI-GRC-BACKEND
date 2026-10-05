package com.kashi.grc.trustcenter.repository;

import com.kashi.grc.trustcenter.domain.TrustDocumentDownload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrustDocumentDownloadRepository extends JpaRepository<TrustDocumentDownload, Long> {
    List<TrustDocumentDownload> findByRequestIdOrderByDownloadedAtDesc(Long requestId);
    long countByTenantIdAndTrustDocumentId(Long tenantId, Long trustDocumentId);
}
