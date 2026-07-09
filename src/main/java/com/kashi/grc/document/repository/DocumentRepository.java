package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Status queries/transitions live in the Custom fragment (Criteria API). */
@Repository
public interface DocumentRepository
        extends JpaRepository<Document, Long>, DocumentRepositoryCustom {

    Optional<Document> findByIdAndTenantId(Long id, Long tenantId);

    // ── Versioning ─────────────────────────────────────────────────────────

    Optional<Document> findBySupersedesId(Long previousDocumentId);
}
