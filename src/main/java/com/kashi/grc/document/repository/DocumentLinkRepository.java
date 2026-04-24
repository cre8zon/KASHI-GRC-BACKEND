package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.DocumentLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentLinkRepository extends JpaRepository<DocumentLink, Long> {

    /**
     * Core query: all active documents linked to a specific entity.
     * Used for: "show all evidence attached to this question response"
     *           "show all reports for this assessment"
     */
    @Query("""
        SELECT dl FROM DocumentLink dl
        JOIN Document d ON d.id = dl.documentId
        WHERE dl.entityType = :entityType
          AND dl.entityId   = :entityId
          AND dl.linkType   = :linkType
          AND d.status      = 'ACTIVE'
        ORDER BY dl.displayOrder ASC, d.version DESC
    """)
    List<DocumentLink> findActiveByEntity(
            @Param("entityType") String entityType,
            @Param("entityId")   Long entityId,
            @Param("linkType")   String linkType);

    /**
     * All document links for an entity (any link type, active docs only).
     * Used for: "show all files associated with this vendor" (reports + attachments)
     */
    @Query("""
        SELECT dl FROM DocumentLink dl
        JOIN Document d ON d.id = dl.documentId
        WHERE dl.entityType = :entityType
          AND dl.entityId   = :entityId
          AND d.status      = 'ACTIVE'
        ORDER BY dl.linkType, dl.displayOrder
    """)
    List<DocumentLink> findAllActiveByEntity(
            @Param("entityType") String entityType,
            @Param("entityId")   Long entityId);

    /**
     * Find specific link (for deduplication check before adding reference).
     */
    Optional<DocumentLink> findByDocumentIdAndEntityTypeAndEntityIdAndLinkType(
            Long documentId, String entityType, Long entityId, String linkType);

    /**
     * Cross-module evidence reuse: find all entities that reference the same document.
     * Used for: "where else is this SOC 2 cert used?"
     */
    List<DocumentLink> findByDocumentId(Long documentId);

    /**
     * Report version history for a specific entity.
     * Returns all REPORT links ordered newest-first.
     */
    @Query("""
        SELECT dl FROM DocumentLink dl
        JOIN Document d ON d.id = dl.documentId
        WHERE dl.entityType = :entityType
          AND dl.entityId   = :entityId
          AND dl.linkType   = 'REPORT'
        ORDER BY d.version DESC
    """)
    List<DocumentLink> findReportVersions(
            @Param("entityType") String entityType,
            @Param("entityId")   Long entityId);

    /** Count active attachments on an entity (for quota checks). */
    @Query("""
        SELECT COUNT(dl) FROM DocumentLink dl
        JOIN Document d ON d.id = dl.documentId
        WHERE dl.entityType = :entityType
          AND dl.entityId   = :entityId
          AND dl.linkType   = 'ATTACHMENT'
          AND d.status      = 'ACTIVE'
    """)
    long countActiveAttachments(
            @Param("entityType") String entityType,
            @Param("entityId")   Long entityId);
}