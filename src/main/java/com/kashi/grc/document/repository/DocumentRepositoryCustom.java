package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.Document;

import java.time.LocalDateTime;
import java.util.List;

/** Criteria API fragment for DocumentRepository. */
public interface DocumentRepositoryCustom {

    /** PENDING uploads older than the cutoff — abandoned-upload cleanup job. */
    List<Document> findAbandonedUploads(LocalDateTime cutoff);

    /** PENDING → DELETED (CriteriaUpdate; returns rows updated). Caller must be @Transactional. */
    int markDeleted(Long id);

    /** ACTIVE → SUPERSEDED — versioning flow. Caller must be @Transactional. */
    int markSuperseded(Long id);
}
