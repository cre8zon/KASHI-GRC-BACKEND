package com.kashi.grc.evidence.spi;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Asks every {@link EvidenceTargetAccessPolicy} that claims a target type.
 * No claimant = no extra check, so entity types outside the claiming modules
 * behave exactly as before.
 *
 * Policies are resolved lazily through ObjectProvider: the audit policy depends
 * on audit services, and resolving it at construction time would pull those
 * into the evidence/document beans' constructor graph.
 */
@Component
@RequiredArgsConstructor
public class EvidenceTargetAccess {

    private final ObjectProvider<EvidenceTargetAccessPolicy> policies;

    public void requireReadable(String entityType, Long entityId) {
        if (entityType == null || entityId == null) return;
        policies.orderedStream().filter(p -> p.supports(entityType))
                .forEach(p -> p.requireReadable(entityType, entityId));
    }

    public void requireCanAttach(String entityType, Long entityId, Long userId) {
        if (entityType == null || entityId == null) return;
        policies.orderedStream().filter(p -> p.supports(entityType))
                .forEach(p -> p.requireCanAttach(entityType, entityId, userId));
    }

    public void requireCanReview(String entityType, Long entityId, Long userId) {
        if (entityType == null || entityId == null) return;
        policies.orderedStream().filter(p -> p.supports(entityType))
                .forEach(p -> p.requireCanReview(entityType, entityId, userId));
    }

    /** Non-throwing read check — for filtering rows out of a tenant-wide list. */
    public boolean isReadable(String entityType, Long entityId) {
        try {
            requireReadable(entityType, entityId);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}