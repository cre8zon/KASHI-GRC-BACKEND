package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.DocumentLink;

import java.util.Collection;
import java.util.List;

/**
 * Criteria API fragment for DocumentLinkRepository.
 * These queries join Document (no mapped association) to filter on d.status
 * and sort by d.version — expressed in Criteria as a second root with an
 * equality predicate (identical INNER JOIN semantics).
 */
public interface DocumentLinkRepositoryCustom {

    /** Links of one linkType for an entity where the document is ACTIVE. */
    List<DocumentLink> findActiveByEntity(String entityType, Long entityId, String linkType);

    /** All ACTIVE-document links for an entity, ordered by linkType, displayOrder. */
    List<DocumentLink> findAllActiveByEntity(String entityType, Long entityId);

    /** REPORT links for an entity ordered by document version DESC (any doc status). */
    List<DocumentLink> findReportVersions(String entityType, Long entityId);

    /** Count ACTIVE-document ATTACHMENT links for an entity. */
    long countActiveAttachments(String entityType, Long entityId);

    /** [entityId, count] rows for a batch of entity IDs — avoids N+1 in list views. */
    List<Object[]> countActiveAttachmentsBulk(String entityType, Collection<Long> entityIds);
}
