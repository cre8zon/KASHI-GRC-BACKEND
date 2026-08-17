package com.kashi.grc.evidence.service;

import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.evidence.domain.EvidenceLink;
import com.kashi.grc.evidence.domain.EvidenceRecord;
import com.kashi.grc.evidence.dto.request.EvidenceLinkReviewRequest;
import com.kashi.grc.evidence.dto.request.EvidenceRecordRequest;
import com.kashi.grc.evidence.dto.request.ManualEvidenceLinkRequest;
import com.kashi.grc.evidence.dto.response.EvidenceLinkResponse;
import com.kashi.grc.evidence.dto.response.EvidenceRecordResponse;
import com.kashi.grc.evidence.repository.EvidenceLinkRepository;
import com.kashi.grc.evidence.repository.EvidenceRecordRepository;
import com.kashi.grc.evidence.event.EvidenceRecordCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * EvidenceService — orchestrates EvidenceRecord + EvidenceLink lifecycle.
 *
 * Responsibilities:
 *   1. Create evidence records and trigger async propagation
 *   2. List/get evidence (paginated, filterable by controlTag)
 *   3. Get links for a specific entity (used by control detail pages)
 *   4. Accept/reject auto-linked evidence
 *   5. Manual linking of evidence to entities
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceService {

    private final EvidenceRecordRepository recordRepository;
    private final EvidenceLinkRepository   linkRepository;
    private final EvidenceReuseEngine      reuseEngine;
    private final UtilityService           utilityService;
    private final ApplicationEventPublisher eventPublisher;

    // ── CREATE ────────────────────────────────────────────────────────────────

    @Transactional
    public EvidenceRecordResponse create(EvidenceRecordRequest req, Long uploadedBy, Long tenantId) {
        EvidenceRecord record = EvidenceRecord.builder()
                .tenantId(tenantId)
                .title(req.getTitle())
                .description(req.getDescription())
                .controlTag(req.getControlTag() != null
                        ? req.getControlTag().toUpperCase().trim() : null)
                .fileUrl(req.getFileUrl())
                .fileName(req.getFileName())
                .fileSizeBytes(req.getFileSizeBytes())
                .mimeType(req.getMimeType())
                .validFrom(req.getValidFrom() != null ? req.getValidFrom() : LocalDateTime.now())
                .validUntil(req.getValidUntil())
                .sourceEntityType(req.getSourceEntityType())
                .sourceEntityId(req.getSourceEntityId())
                .uploadedBy(uploadedBy)
                .uploadedAt(LocalDateTime.now())
                .linkCount(0)
                .build();

        recordRepository.save(record);
        log.info("[EVIDENCE] Created recordId={} tag={} tenantId={}", record.getId(),
                record.getControlTag(), tenantId);

        // KashiLink: fires only AFTER this transaction commits.
        // Calling reuseEngine.propagate() directly here raced the commit — the
        // async thread read the record before it existed and silently skipped.
        if (record.getControlTag() != null) {
            eventPublisher.publishEvent(EvidenceRecordCreatedEvent.manual(
                    record.getId(), tenantId, record.getControlTag()));
        }

        return toRecordResponse(record, null);
    }

    // ── READ ──────────────────────────────────────────────────────────────────

    public List<EvidenceRecordResponse> listForTenant(Long tenantId, String controlTag) {
        List<EvidenceRecord> records = controlTag != null && !controlTag.isBlank()
                ? recordRepository.findByTenantIdAndControlTag(tenantId, controlTag)
                : recordRepository.findByTenantId(tenantId);

        return records.stream().map(r -> toRecordResponse(r, null)).collect(Collectors.toList());
    }

    public EvidenceRecordResponse getById(Long id, Long tenantId) {
        // Tenant-scoped: findById alone leaked other tenants' evidence records.
        EvidenceRecord record = recordRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("EvidenceRecord", id));

        List<EvidenceLink> links = linkRepository.findByEvidenceRecordIdAndTenantId(id, tenantId);
        return toRecordResponse(record, links);
    }

    /**
     * Get all EvidenceLinks for a specific entity instance.
     * Called by control detail, question detail, issue detail pages.
     * e.g. GET /v1/evidence/links?entityType=AUDIT_CONTROL_INSTANCE&entityId=42
     */
    public List<EvidenceLinkResponse> getLinksForEntity(String entityType, Long entityId, Long tenantId) {
        return withRecords(linkRepository
                .findByTargetEntityTypeAndTargetEntityIdAndTenantId(entityType, entityId, tenantId));
    }

    /**
     * Traceability: given a test instance, find which control-level evidence
     * was used to evaluate it (shared evidenceRecordId between the test's
     * evidence link and a control's evidence link). Used by the test detail
     * page to show "Evidence used:" referencing the originating control evidence.
     */
    public List<EvidenceLinkResponse> getControlEvidenceUsedByTest(Long testInstanceId, Long tenantId) {
        return withRecords(linkRepository.findControlEvidenceUsedByTest(testInstanceId, tenantId));
    }

    /**
     * Get all PENDING_REVIEW auto-linked evidence for the tenant.
     * Used by the evidence review inbox / notification badge.
     */
    public List<EvidenceLinkResponse> getPendingReview(Long tenantId) {
        return withRecords(linkRepository.findPendingReviewForTenant(tenantId));
    }

    // ── REVIEW ────────────────────────────────────────────────────────────────

    @Transactional
    public EvidenceLinkResponse reviewLink(Long linkId, EvidenceLinkReviewRequest req,
                                           Long reviewedBy, Long tenantId) {
        if (req.getAction() == EvidenceLinkReviewRequest.Action.ACCEPT) {
            reuseEngine.acceptLink(linkId, reviewedBy, req.getNote(), tenantId);
        } else {
            reuseEngine.rejectLink(linkId, reviewedBy, req.getNote(), tenantId);
        }
        EvidenceLink link = linkRepository.findByIdAndTenantId(linkId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("EvidenceLink", linkId));
        return toLinkResponse(link);
    }

    // ── MANUAL LINK ───────────────────────────────────────────────────────────

    @Transactional
    public EvidenceLinkResponse manualLink(Long evidenceRecordId, ManualEvidenceLinkRequest req,
                                           Long linkedBy, Long tenantId) {
        // Verify the record belongs to this tenant.
        // findById alone allowed attaching ANOTHER tenant's evidence to your own
        // entity — and the resulting link was stamped with YOUR tenantId, so it
        // looked legitimate from then on.
        recordRepository.findByIdAndTenantId(evidenceRecordId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("EvidenceRecord", evidenceRecordId));

        // Idempotent — if link already exists, return it
        if (linkRepository.existsByEvidenceRecordIdAndTargetEntityTypeAndTargetEntityId(
                evidenceRecordId, req.getTargetEntityType(), req.getTargetEntityId())) {
            return linkRepository
                    .findByTargetEntityTypeAndTargetEntityIdAndTenantId(
                            req.getTargetEntityType(), req.getTargetEntityId(), tenantId)
                    .stream().filter(l -> l.getEvidenceRecordId().equals(evidenceRecordId))
                    .findFirst().map(this::toLinkResponse).orElseThrow();
        }

        EvidenceLink link = EvidenceLink.builder()
                .evidenceRecordId(evidenceRecordId)
                .targetEntityType(req.getTargetEntityType())
                .targetEntityId(req.getTargetEntityId())
                .tenantId(tenantId)
                .status(EvidenceLink.Status.ACCEPTED)  // manual links are immediately accepted
                .autoLinked(false)
                .reviewerNote(req.getNote())
                .linkedAt(LocalDateTime.now())
                .linkedBy(linkedBy)
                .reviewedBy(linkedBy)
                .reviewedAt(LocalDateTime.now())
                .build();

        linkRepository.save(link);

        // Update link count
        recordRepository.findByIdAndTenantId(evidenceRecordId, tenantId).ifPresent(r -> {
            r.setLinkCount(r.getLinkCount() + 1);
            recordRepository.save(r);
        });

        log.info("[EVIDENCE] Manual link created | recordId={} → {}:{}",
                evidenceRecordId, req.getTargetEntityType(), req.getTargetEntityId());

        return toLinkResponse(link);
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private EvidenceRecordResponse toRecordResponse(EvidenceRecord r, List<EvidenceLink> links) {
        return EvidenceRecordResponse.builder()
                .id(r.getId())
                .tenantId(r.getTenantId())
                .title(r.getTitle())
                .description(r.getDescription())
                .controlTag(r.getControlTag())
                .fileUrl(r.getFileUrl())
                .fileName(r.getFileName())
                .fileSizeBytes(r.getFileSizeBytes())
                .mimeType(r.getMimeType())
                .validFrom(r.getValidFrom())
                .validUntil(r.getValidUntil())
                .expired(r.isExpired())
                .sourceEntityType(r.getSourceEntityType())
                .sourceEntityId(r.getSourceEntityId())
                .uploadedBy(r.getUploadedBy())
                .uploadedAt(r.getUploadedAt())
                .linkCount(r.getLinkCount())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .links(links != null
                        ? links.stream().map(this::toLinkResponse).collect(Collectors.toList())
                        : null)
                .build();
    }

    /**
     * KashiLink: batch-hydrate links with their parent EvidenceRecord.
     * One extra query for the whole page instead of N — and without this the
     * frontend receives null titles and cannot tell an automated link from a
     * manual one, which is why the "Integration checks" section always rendered
     * as empty.
     */
    private List<EvidenceLinkResponse> withRecords(List<EvidenceLink> links) {
        if (links.isEmpty()) return List.of();
        List<Long> ids = links.stream()
                .map(EvidenceLink::getEvidenceRecordId).distinct().collect(Collectors.toList());
        Map<Long, EvidenceRecord> byId = recordRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(EvidenceRecord::getId, r -> r));
        return links.stream()
                .map(l -> toLinkResponse(l, byId.get(l.getEvidenceRecordId())))
                .collect(Collectors.toList());
    }

    private EvidenceLinkResponse toLinkResponse(EvidenceLink l) {
        return toLinkResponse(l, null);
    }

    private EvidenceLinkResponse toLinkResponse(EvidenceLink l, EvidenceRecord r) {
        EvidenceLinkResponse.EvidenceLinkResponseBuilder b = EvidenceLinkResponse.builder()
                .id(l.getId())
                .evidenceRecordId(l.getEvidenceRecordId())
                .targetEntityType(l.getTargetEntityType())
                .targetEntityId(l.getTargetEntityId())
                .status(l.getStatus())
                .autoLinked(l.isAutoLinked())
                .matchedTagSnapshot(l.getMatchedTagSnapshot())
                .reviewedBy(l.getReviewedBy())
                .reviewedAt(l.getReviewedAt())
                .reviewerNote(l.getReviewerNote())
                .linkedAt(l.getLinkedAt());

        if (r != null) {
            b.evidenceTitle(r.getTitle())
                    .evidenceFileUrl(r.getFileUrl())
                    .evidenceFileName(r.getFileName())
                    .evidenceMimeType(r.getMimeType())
                    .evidenceControlTag(r.getControlTag())
                    .evidenceValidUntil(r.getValidUntil())
                    .evidenceExpired(r.isExpired())
                    .collectionType(r.getCollectionType() != null ? r.getCollectionType().name() : "MANUAL")
                    .automationResult(r.getAutomationResult() != null ? r.getAutomationResult().name() : null)
                    .automationMessage(r.getAutomationMessage())
                    .collectedAt(r.getCollectedAt())
                    .integrationKey(r.getIntegrationKey())
                    .rawPayload(r.getRawPayload());
        }
        return b.build();
    }
}