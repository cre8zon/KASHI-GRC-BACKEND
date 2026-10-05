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

    /**
     * Everything attached to one entity, within one tenant.
     *
     * The two methods above answer "where else does this document appear" and
     * "is this exact link already there". Neither answers "what is attached to
     * this thing", which is the question every detail screen asks — so callers
     * were reduced to findAll() and an in-memory filter over a table that grows
     * with every upload in every module.
     *
     * Covered by idx_dl_entity (entity_type, entity_id, link_type) and
     * idx_dl_tenant (tenant_id, entity_type), both already on the table.
     *
     * Added for AssessmentEvidenceService (evidence per question), but the
     * signature is deliberately generic — audit, policy and vendor screens have
     * the same need and the same workaround.
     */
    List<DocumentLink> findByEntityTypeAndEntityIdAndTenantId(
            String entityType, Long entityId, Long tenantId);

    /**
     * Every link for a SET of entity ids of one type, in one query.
     *
     * Added for the evidence-required gate on section submit, which has to ask
     * "does each of these forty questions have an attachment" and must not do
     * it forty times. Hits the same idx_dl_entity (entity_type, entity_id) as
     * the single-id method above — an IN over the second column of that index
     * is a range scan, not a table scan.
     *
     * No tenant_id in the signature, deliberately. The caller has already
     * resolved the entity ids from a tenant-scoped parent (the assessment's own
     * question instances), so the ids themselves carry the scope; adding
     * tenant_id here would re-filter on a column that
     * assessment_question_instances leaves NULL and quietly return nothing.
     */
    List<DocumentLink> findByEntityTypeAndEntityIdIn(
            String entityType, java.util.Collection<Long> entityIds);
}