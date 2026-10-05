package com.kashi.grc.document.event;

/**
 * An existing document was REUSED on a record (a REFERENCE link — picked from
 * another control or engagement rather than uploaded). Reuse is a deliberate
 * choice of evidence, so modules that track "evidence submitted" (audit
 * controls) treat it as a submission. The document module itself knows nothing
 * about them.
 */
public record DocumentReusedEvent(String entityType, Long entityId, Long documentId,
                                  Long reusedBy, Long tenantId) {}