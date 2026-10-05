package com.kashi.grc.assessment.service;

import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.document.domain.DocumentLink;
import com.kashi.grc.document.repository.DocumentLinkRepository;
import com.kashi.grc.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Evidence attached to the answer it supports.
 *
 * ── THERE WAS NEVER A MISSING COLUMN ──────────────────────────────────────
 * The obvious reading of "vendor evidence does not link to answers" is that the
 * schema cannot express it. It can. document_links is polymorphic —
 * document_id, entity_type, entity_id, link_type — and the assessment module
 * already writes to it.
 *
 * It writes entity_type = 'ASSESSMENT'. So every document on an assessment is
 * attached to the assessment as a whole, and the read path fetches all of them
 * with nothing saying which answer each one backs. The evidence is linked; it
 * is linked one level too high.
 *
 * ── WHY THE LEVEL MATTERS MORE THAN IT SOUNDS ─────────────────────────────
 * A reviewer works question by question. "Does this document support THIS
 * claim" is the entire review action, and it cannot be performed against a pile
 * of forty documents belonging to the assessment. The report has the same
 * problem one step later: it cannot print the claim beside its proof.
 *
 * ── ENTITY TYPE IS NOT INVENTED HERE ──────────────────────────────────────
 * 'QUESTION_RESPONSE' is what ActionItem.EntityType already uses for exactly
 * this target (ReviewController lines 610 and 679). Coining a second name for
 * the same thing is how two halves of a module stop being able to join.
 *
 * ── TENANT SCOPING GOES THROUGH THE ASSESSMENT, NOT THE QUESTION ──────────
 * This version corrects the first one, which checked qi.getTenantId().
 *
 * assessment_question_instances HAS a tenant_id column, and nothing in the
 * codebase has ever written to it — it is declared nullable, there is no
 * @PrePersist and no listener, and executeAssessment does not set it. Every row
 * in that table carries NULL. So a check of the form
 *
 *     if (!tenantId.equals(qi.getTenantId())) throw notFound;
 *
 * rejects every request, always. Not a subtle leak: a 404 on every attach.
 *
 * The scope therefore runs through vendor_assessments, which extends
 * TenantAwareEntity and has tenant_id NOT NULL and stamped on write. That is
 * also the model's own intent — section instances and option instances have no
 * tenant column at all, because they hang off the assessment. One authority for
 * "whose is this", and it is populated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentEvidenceService {

    private final DocumentLinkRepository               linkRepository;
    private final DocumentRepository                   documentRepository;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;
    private final VendorAssessmentRepository           assessmentRepository;
    private final UtilityService                       utilityService;

    /** The value ActionItem.EntityType already uses. Do not coin another. */
    public static final String ENTITY_QUESTION   = "QUESTION_RESPONSE";
    public static final String ENTITY_ASSESSMENT = "ASSESSMENT";
    public static final String LINK_EVIDENCE     = "ATTACHMENT";

    // ═════════════════════════════════════════════════════════════════════════
    // ATTACH
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Attaches an already-uploaded document to one question.
     *
     * Takes a documentId rather than a file: uploading belongs to the document
     * module, which handles storage, virus scanning and the filename
     * sanitisation that had to be fixed once already. A second upload path here
     * would be a second place for all three to go wrong.
     *
     * Idempotent, and doubly so — the lookup below returns the existing link,
     * and document_links carries uq_document_link on
     * (document_id, entity_type, entity_id, link_type), so a concurrent pair of
     * requests that both pass the check still cannot write two rows. The
     * application check is for the response; the constraint is for the truth.
     */
    @Transactional
    public DocumentLink attachToQuestion(Long questionInstanceId, Long documentId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        requireQuestionInTenant(questionInstanceId, tenantId);

        documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));

        Optional<DocumentLink> existing = linkRepository
                .findByDocumentIdAndEntityTypeAndEntityIdAndLinkType(
                        documentId, ENTITY_QUESTION, questionInstanceId, LINK_EVIDENCE);
        if (existing.isPresent()) return existing.get();

        // createdBy and createdAt are NOT NULL on document_links. createdAt has
        // a @Builder.Default; createdBy does not, so omitting it is a
        // constraint violation on every insert. Every other build site in the
        // codebase sets both — this follows them rather than inventing a shape.
        DocumentLink link = DocumentLink.builder()
                .tenantId(tenantId)
                .documentId(documentId)
                .entityType(ENTITY_QUESTION)
                .entityId(questionInstanceId)
                .linkType(LINK_EVIDENCE)
                .createdBy(userId)
                .createdAt(LocalDateTime.now())
                .build();
        linkRepository.save(link);

        log.info("[ASSESSMENT-EVIDENCE] Attached | doc={} | question={} | tenant={} | by={}",
                documentId, questionInstanceId, tenantId, userId);
        return link;
    }

    /**
     * Detaches. Removes the link, never the document.
     *
     * The file belongs to the document store and may be attached elsewhere —
     * to the assessment, to an audit, to a policy. Deleting it because somebody
     * unattached it from one question would destroy evidence on sight.
     */
    @Transactional
    public void detachFromQuestion(Long questionInstanceId, Long documentId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        requireQuestionInTenant(questionInstanceId, tenantId);

        linkRepository.findByDocumentIdAndEntityTypeAndEntityIdAndLinkType(
                        documentId, ENTITY_QUESTION, questionInstanceId, LINK_EVIDENCE)
                .filter(l -> tenantId.equals(l.getTenantId()))
                .ifPresent(linkRepository::delete);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Evidence for one question.
     *
     * Uses findByEntityTypeAndEntityIdAndTenantId, added to
     * DocumentLinkRepository alongside this service — two lines, shipped in the
     * same drop. The previous version scanned every document link in the
     * database and filtered in memory, which is correct and unusable: that table
     * grows with every upload in every module, and this is called once per
     * question on a review screen.
     */
    @Transactional(readOnly = true)
    public List<DocumentLink> forQuestion(Long questionInstanceId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        requireQuestionInTenant(questionInstanceId, tenantId);

        return linkRepository.findByEntityTypeAndEntityIdAndTenantId(
                        ENTITY_QUESTION, questionInstanceId, tenantId).stream()
                .sorted(Comparator.comparing(DocumentLink::getId))
                .toList();
    }

    /**
     * Evidence for a whole assessment, question by question.
     *
     * Returns question id -> its documents, so a review screen can render each
     * answer with its own proof in one pass rather than one request per
     * question. A 200-question assessment would otherwise be 200 round trips.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> byQuestionForAssessment(Long assessmentId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        requireAssessmentInTenant(assessmentId, tenantId);

        List<AssessmentQuestionInstance> questions =
                questionInstanceRepository.findByAssessmentIdOrderByOrderNo(assessmentId);

        Map<Long, List<Long>> out = new LinkedHashMap<>();
        for (AssessmentQuestionInstance q : questions) {
            List<Long> docs = linkRepository
                    .findByEntityTypeAndEntityIdAndTenantId(ENTITY_QUESTION, q.getId(), tenantId)
                    .stream()
                    .sorted(Comparator.comparing(DocumentLink::getId))
                    .map(DocumentLink::getDocumentId)
                    .toList();
            if (!docs.isEmpty()) out.put(q.getId(), docs);
        }
        return out;
    }

    /**
     * Moves an assessment-level document down onto a question.
     *
     * For the existing rows. They are not wrong — the document does belong to
     * the assessment — they are simply less specific than they should be, and
     * nothing can work out automatically which answer an old upload was meant
     * for. That is a judgement only the person who uploaded it can make, so
     * this exists to let them make it one at a time rather than being guessed
     * at in a migration.
     *
     * The assessment-level link is kept. It remains a true statement.
     */
    @Transactional
    public DocumentLink promoteToQuestion(Long documentId, Long questionInstanceId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        AssessmentQuestionInstance qi = requireQuestionInTenant(questionInstanceId, tenantId);

        boolean linkedToThisAssessment = linkRepository.findByDocumentId(documentId).stream()
                .anyMatch(l -> ENTITY_ASSESSMENT.equals(l.getEntityType())
                        && tenantId.equals(l.getTenantId())
                        && qi.getAssessmentId().equals(l.getEntityId()));
        if (!linkedToThisAssessment) {
            throw new BusinessException("INVALID_OPERATION",
                    "That document is not attached to this assessment.");
        }
        return attachToQuestion(questionInstanceId, documentId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCOPE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Resolves a question instance and proves it belongs to this tenant.
     *
     * Through the assessment, for the reason in the class note: the question
     * instance's own tenant_id is NULL on every row ever written.
     *
     * Throws ResourceNotFoundException rather than ACCESS_DENIED on a tenant
     * mismatch. A 403 confirms the id exists, which tells a caller in tenant A
     * that tenant B has a question numbered 4,182 — small, but it is free to
     * not say it.
     */
    private AssessmentQuestionInstance requireQuestionInTenant(Long questionInstanceId, Long tenantId) {
        AssessmentQuestionInstance qi = questionInstanceRepository.findById(questionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "AssessmentQuestionInstance", questionInstanceId));
        requireAssessmentInTenant(qi.getAssessmentId(), tenantId);
        return qi;
    }

    private VendorAssessment requireAssessmentInTenant(Long assessmentId, Long tenantId) {
        return assessmentRepository.findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
    }
}
