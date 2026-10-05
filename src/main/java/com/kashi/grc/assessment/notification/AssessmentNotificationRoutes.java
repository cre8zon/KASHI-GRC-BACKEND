package com.kashi.grc.assessment.notification;

import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.notification.spi.NotificationRouteContributor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Where a vendor-assessment notification opens.
 *
 * The assessment module's answer to NotificationRouteContributor — read that
 * interface first; it explains why this exists rather than an actionUrl
 * argument on 55 call sites.
 *
 * ── THE ONE THAT MATTERED ─────────────────────────────────────────────────
 *
 * Eleven of the module's notifications are about a QUESTION_RESPONSE, and the
 * client sent every one of them to /action-items?highlight… — a list page. The
 * route needs the ASSESSMENT's id and the notification carries the QUESTION's,
 * so the client could not have done better; only something that can read a
 * question instance can answer this, and that is this module.
 *
 * ── THE TAB DEPENDS ON THE RECIPIENT, NOT THE EVENT ───────────────────────
 *
 * The same remediation notifies the vendor user who must fix it and the
 * organisation user who raised it. Sending both to the same tab puts one of
 * them on a screen with nothing to do.
 *
 * Resolved from the question's own assignments rather than from the recipient's
 * role, for the reason sql/91 gives: the question records who answers it and
 * who evaluates it, and a role can be changed after the fact. Reviewer first,
 * because somebody who is both should see the evaluation screen — the answering
 * screen would invite them to edit an answer they may not own.
 *
 * ── WHY NO SECTION_INSTANCE ───────────────────────────────────────────────
 *
 * SECTION_REOPENED notifications carry a section instance id, and a section has
 * no screen of its own — it is a heading inside a tab. Routing it to the
 * assessment would be right but says nothing the client's own fallback does not
 * already say, so it is left to the fallback rather than written twice. Add it
 * here the day a section gets a screen.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssessmentNotificationRoutes implements NotificationRouteContributor {

    private final AssessmentQuestionInstanceRepository questionInstanceRepository;

    /** The module page, as sql/84 D2 wrote it into ui_navigation. */
    private static final String BASE = "/module/vendor_assessment/";

    @Override
    public boolean supports(String entityType) {
        return "QUESTION_RESPONSE".equals(entityType) || "ASSESSMENT".equals(entityType);
    }

    @Override
    public String routeFor(String entityType, Long entityId, String type, Long userId) {
        if ("ASSESSMENT".equals(entityType)) {
            // The client sent this to /assessments/{id} — the hardcoded page.
            return BASE + entityId;
        }

        AssessmentQuestionInstance qi = questionInstanceRepository.findById(entityId).orElse(null);
        if (qi == null || qi.getAssessmentId() == null) {
            // A question instance from somewhere else, or one that has been
            // deleted. Null, not a guess — see the interface.
            return null;
        }

        boolean orgSide = userId != null && userId.equals(qi.getReviewerAssignedUserId());
        String tab = orgSide ? "review" : "fill";

        // questionInstanceId is what useDrawerFromUrl reads to open the drawer
        // on the right question and scroll the list to it. Without it the
        // recipient lands on a tab holding forty questions and has to find the
        // one they were told about.
        return BASE + qi.getAssessmentId() + "?tab=" + tab + "&questionInstanceId=" + entityId;
    }
}