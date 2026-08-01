package com.kashi.grc.evidence.service;

import com.kashi.grc.evidence.domain.EvidenceLink;
import com.kashi.grc.evidence.domain.EvidenceRecord;
import com.kashi.grc.evidence.event.EvidenceRecordCreatedEvent;
import com.kashi.grc.evidence.repository.EvidenceLinkRepository;
import com.kashi.grc.evidence.repository.EvidenceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * KashiLink — bridges the document store into the evidence reuse engine.
 *
 * Called from DocumentController.linkDocument(), which is the single choke
 * point for all three upload paths (presigned confirm, image upload, and
 * explicit link). Registering here means every manual upload enters the reuse
 * engine automatically, with the correct tag, without any frontend change.
 *
 * WHAT IT DOES
 *   1. Resolve the control tag from the target entity via EvidenceTagResolver
 *   2. Create an EvidenceRecord (collectionType=MANUAL, fileUrl=documentId)
 *   3. Create the ACCEPTED origin link back to the entity it was uploaded to
 *      (the auditee consciously attached it here — no review needed)
 *   4. Publish EvidenceRecordCreatedEvent -> propagation fires after commit
 *
 * IDEMPOTENCY
 * Re-confirming the same document against the same entity does not duplicate.
 * The origin link is guarded by the same uk_evidence_link constraint the engine
 * relies on, and the record lookup is by (tenantId, fileUrl).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceRegistrationService {

    private final EvidenceRecordRepository   recordRepository;
    private final EvidenceLinkRepository     linkRepository;
    private final List<EvidenceTagResolver>  resolvers;
    private final ApplicationEventPublisher  eventPublisher;

    /** entityType -> resolver, built once on first use. */
    private Map<String, EvidenceTagResolver> resolverIndex;

    private Map<String, EvidenceTagResolver> index() {
        if (resolverIndex == null) {
            resolverIndex = resolvers.stream().collect(Collectors.toMap(
                    EvidenceTagResolver::entityType, Function.identity(), (a, b) -> a));
            log.info("[KASHILINK] Registered {} tag resolvers: {}",
                    resolverIndex.size(), resolverIndex.keySet());
        }
        return resolverIndex;
    }

    /**
     * Register a freshly linked document as reusable evidence.
     * Safe to call for any entity type — unknown types are skipped quietly.
     *
     * @param linkType only ATTACHMENT is registered; REPORT and REFERENCE are
     *                 not evidence and must not propagate.
     */
    @Transactional
    public void registerFromDocument(Long documentId, String fileName, String mimeType,
                                     Long fileSizeBytes, String entityType, Long entityId,
                                     String linkType, Long tenantId, Long uploadedBy) {

        if (!"ATTACHMENT".equals(linkType)) return;

        EvidenceTagResolver resolver = index().get(entityType);
        if (resolver == null) {
            log.debug("[KASHILINK] No resolver for entityType={} — document {} not registered",
                    entityType, documentId);
            return;
        }

        String tag = resolver.resolveTag(entityId, tenantId);
        if (tag != null) tag = tag.toUpperCase().trim();

        // Reuse the record if this document was already registered for this tenant
        EvidenceRecord record = recordRepository
                .findFirstByTenantIdAndFileUrl(tenantId, String.valueOf(documentId))
                .orElse(null);

        if (record == null) {
            String label = resolver.resolveLabel(entityId, tenantId);
            record = EvidenceRecord.builder()
                    .tenantId(tenantId)
                    .title(label != null ? label + " — " + fileName : fileName)
                    .controlTag(tag)
                    .collectionType(EvidenceRecord.CollectionType.MANUAL)
                    .fileUrl(String.valueOf(documentId))
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .fileSizeBytes(fileSizeBytes)
                    .sourceEntityType(entityType)
                    .sourceEntityId(entityId)
                    .validFrom(LocalDateTime.now())
                    .uploadedBy(uploadedBy)
                    .uploadedAt(LocalDateTime.now())
                    .linkCount(0)
                    .build();
            recordRepository.save(record);
            log.info("[KASHILINK] Registered evidence | recordId={} | docId={} | tag={} | {}:{}",
                    record.getId(), documentId, tag, entityType, entityId);
        }

        // Origin link — the entity the file was actually uploaded against.
        // ACCEPTED, not PENDING_REVIEW: nobody needs to review a deliberate upload.
        if (!linkRepository.existsByEvidenceRecordIdAndTargetEntityTypeAndTargetEntityId(
                record.getId(), entityType, entityId)) {
            linkRepository.save(EvidenceLink.builder()
                    .evidenceRecordId(record.getId())
                    .targetEntityType(entityType)
                    .targetEntityId(entityId)
                    .tenantId(tenantId)
                    .status(EvidenceLink.Status.ACCEPTED)
                    .autoLinked(false)
                    .matchedTagSnapshot(tag)
                    .linkedAt(LocalDateTime.now())
                    .linkedBy(uploadedBy)
                    .reviewedBy(uploadedBy)
                    .reviewedAt(LocalDateTime.now())
                    .build());
            record.setLinkCount(record.getLinkCount() + 1);
            recordRepository.save(record);
        }

        // Fan out to every other instance carrying the same tag — after commit.
        if (tag != null && !tag.isBlank()) {
            eventPublisher.publishEvent(
                    EvidenceRecordCreatedEvent.manual(record.getId(), tenantId, tag));
        }
    }
}