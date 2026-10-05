package com.kashi.grc.assessment.service;

import com.kashi.grc.assessment.domain.QuestionComment;
import com.kashi.grc.assessment.repository.AssessmentResponseRepository;
import com.kashi.grc.assessment.repository.QuestionCommentRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The three comment threads on a question.
 *
 *   SHARED      — the conversation between the two organisations
 *   ORG_ONLY    — the assessor's internal thread
 *   VENDOR_ONLY — the vendor's own working thread
 *
 * ── THE ENFORCEMENT IS SERVER-SIDE, AND THAT IS THE WHOLE POINT ───────────
 * A vendor user cannot write ORG_ONLY and an organisation user cannot write
 * VENDOR_ONLY. Not "the UI does not offer it" — the service refuses it.
 *
 * The reason is what these threads are for. An org reviewer writing "this
 * answer is weak, push back hard" into ORG_ONLY is relying on the vendor never
 * seeing it. If that guarantee lives in a dropdown, it holds until somebody
 * posts to the wrong tab, and there is no undo: the vendor has already read it.
 *
 * ── AND THE SIDE IS NEVER TAKEN FROM THE REQUEST ──────────────────────────
 * Visibility is chosen by the caller; the caller's SIDE is derived from their
 * roles. A request that carried its own side would let anyone write anywhere
 * by editing one field.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentCommentService {

    private final QuestionCommentRepository    commentRepository;
    private final AssessmentResponseRepository responseRepository;
    private final AssessmentSideResolver       sideResolver;
    private final UtilityService               utilityService;

    public static final String SHARED      = "SHARED";
    public static final String ORG_ONLY    = "ORG_ONLY";
    public static final String VENDOR_ONLY = "VENDOR_ONLY";

    // ═════════════════════════════════════════════════════════════════════════
    // WRITE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Posts a comment into one of the three threads.
     *
     * Preserves the existing behaviour exactly — same response row check, same
     * fields written — and adds the visibility the old path had no column for.
     */
    @Transactional
    public QuestionComment add(Long responseId, String text, String requestedVisibility) {
        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        responseRepository.findById(responseId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentResponse", responseId));

        if (text == null || text.isBlank()) {
            throw new BusinessException("INVALID_OPERATION", "A comment needs some text.");
        }

        String visibility = resolveWritableVisibility(requestedVisibility);

        QuestionComment comment = QuestionComment.builder()
                .responseId(responseId)
                .commentText(text)
                .commentedBy(userId)
                .tenantId(tenantId)
                .visibility(visibility)
                .build();
        commentRepository.save(comment);

        log.info("[ASSESSMENT-COMMENT] responseId={} | visibility={} | side={} | by={}",
                responseId, visibility, sideResolver.current(), userId);
        return comment;
    }

    /**
     * Decides what this caller is allowed to write, and refuses rather than
     * silently downgrading.
     *
     * Downgrading — quietly turning an attempted ORG_ONLY into SHARED — would
     * be worse than refusing: the author believes it is private, and it is not.
     * Failing loudly is the only outcome where nobody is misled.
     */
    private String resolveWritableVisibility(String requested) {
        AssessmentSideResolver.Side side = sideResolver.current();
        String v = (requested == null || requested.isBlank())
                ? SHARED : requested.trim().toUpperCase();

        if (!Set.of(SHARED, ORG_ONLY, VENDOR_ONLY).contains(v)) {
            throw new BusinessException("INVALID_OPERATION",
                    "Visibility must be SHARED, ORG_ONLY or VENDOR_ONLY.");
        }

        switch (side) {
            case VENDOR -> {
                if (ORG_ONLY.equals(v)) {
                    throw new BusinessException("ACCESS_DENIED",
                            "That is the assessing organisation's private thread. "
                                    + "Post to the shared thread or your own team's.",
                            HttpStatus.FORBIDDEN);
                }
            }
            case ORGANIZATION -> {
                if (VENDOR_ONLY.equals(v)) {
                    throw new BusinessException("ACCESS_DENIED",
                            "That is the vendor's private thread. Post to the shared thread "
                                    + "or your own team's.",
                            HttpStatus.FORBIDDEN);
                }
            }
            case SYSTEM -> {
                // A platform operator is neither party. They may post to the
                // shared thread — support does need to reach both sides — but
                // writing into either organisation's private thread would put
                // words in their mouth.
                if (!SHARED.equals(v)) {
                    throw new BusinessException("ACCESS_DENIED",
                            "Platform users can only post to the shared thread.",
                            HttpStatus.FORBIDDEN);
                }
            }
        }
        return v;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * What this caller may see on one question.
     *
     * Filtered by the caller's derived side, never by a parameter. The tab the
     * UI is showing does not decide what comes back — it only decides what the
     * UI renders out of what it is given.
     */
    @Transactional(readOnly = true)
    public List<QuestionComment> visibleFor(Long responseId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Set<String> allowed = readableVisibilities();

        return commentRepository.findAll().stream()
                .filter(c -> responseId.equals(c.getResponseId()))
                .filter(c -> tenantId.equals(c.getTenantId()))
                .filter(c -> allowed.contains(visibilityOf(c)))
                .sorted(Comparator.comparing(QuestionComment::getId))
                .toList();
    }

    /**
     * Counts per thread, for the tab badges.
     *
     * Uses the same filter as the read. A badge computed any other way is how a
     * vendor sees "3 comments" on a thread showing one, which reads as a bug
     * and is actually a leak: the count discloses that two hidden comments
     * exist.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> countsFor(Long responseId) {
        Set<String> allowed = readableVisibilities();
        Map<String, Long> out = new LinkedHashMap<>();
        for (String v : allowed) out.put(v, 0L);

        for (QuestionComment c : visibleFor(responseId)) {
            out.merge(visibilityOf(c), 1L, Long::sum);
        }
        return out;
    }

    /** Which threads this caller can read at all. */
    public Set<String> readableVisibilities() {
        return switch (sideResolver.current()) {
            case VENDOR       -> Set.of(SHARED, VENDOR_ONLY);
            case ORGANIZATION -> Set.of(SHARED, ORG_ONLY);
            // A platform operator sees the shared thread only, for the same
            // reason they cannot write to the others: support access is not
            // the same as being a party to the conversation.
            case SYSTEM       -> Set.of(SHARED);
        };
    }

    /**
     * Rows written before migration 50 have no visibility set.
     *
     * They default to SHARED because that is what they factually were — visible
     * to both sides since the day they were written. Treating them as anything
     * else would record a claim about who saw what that is not true.
     */
    private String visibilityOf(QuestionComment c) {
        String v = c.getVisibility();
        return (v == null || v.isBlank()) ? SHARED : v;
    }
}
