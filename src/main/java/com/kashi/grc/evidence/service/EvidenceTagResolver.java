package com.kashi.grc.evidence.service;

/**
 * KashiLink — resolves the control tag for an entity a document was attached to.
 *
 * THE PROBLEM THIS SOLVES
 * ----------------------
 * EvidenceUploader uploads through DocumentController, which writes documents +
 * document_links. It never created an EvidenceRecord, so manual uploads never
 * entered the reuse engine at all. Only IntegrationRunner produced evidence
 * records, which is why nothing manual ever auto-linked.
 *
 * The naive fix is to make the frontend pass controlTag on upload. That is
 * fragile — every call site has to remember, and a missing or wrong tag fails
 * silently. Instead the server resolves the tag from the entity itself: it
 * already knows entityType=AUDIT_CONTROL_INSTANCE, entityId=42, and that
 * instance already carries control_tag_snapshot.
 *
 * Mirrors the EvidenceTagMatcher SPI: implement, annotate @Component, done.
 * The registration service discovers all implementations automatically.
 *
 * MATCHER vs RESOLVER
 *   EvidenceTagMatcher   tag    -> entities   (fan-out, used when propagating)
 *   EvidenceTagResolver  entity -> tag        (fan-in,  used when registering)
 */
public interface EvidenceTagResolver {

    /** Entity type this resolver handles, e.g. "AUDIT_CONTROL_INSTANCE". */
    String entityType();

    /**
     * Resolve the control tag carried by this entity instance.
     *
     * @return the tag, or null if the entity is untagged or not found.
     *         Null means the evidence record is still created (so the file is
     *         tracked) but no propagation occurs.
     */
    String resolveTag(Long entityId, Long tenantId);

    /**
     * Optional human-readable label for the evidence record title,
     * e.g. the control code. Null falls back to the file name.
     */
    default String resolveLabel(Long entityId, Long tenantId) {
        return null;
    }
}