package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.DocumentLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Document-status-joined queries live in the Custom fragment (Criteria API). */
@Repository
public interface DocumentLinkRepository
        extends JpaRepository<DocumentLink, Long>, DocumentLinkRepositoryCustom {

    Optional<DocumentLink> findByDocumentIdAndEntityTypeAndEntityIdAndLinkType(
            Long documentId, String entityType, Long entityId, String linkType);

    List<DocumentLink> findByDocumentId(Long documentId);
}
