package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Who may act on a control instance.
 *
 * THE HOLE THIS CLOSES
 *   The existing guards read:
 *
 *       if (ctrl.getAssignedAuditorId() != null && !ctrl.getAssignedAuditorId().equals(ctx.getId()))
 *           throw ...
 *
 *   which means an UNASSIGNED control is open to anyone holding the permission.
 *   Since most controls sit unassigned until someone bulk-assigns a section,
 *   that is the normal state, not an edge case — so in practice any auditee
 *   could submit any control, and any auditor could record any test result.
 *   The test-instance path is explicit about it: `|| c.getAssignedAuditorId() == null`.
 *
 *   The mirror problem is that when a control IS assigned, only that exact
 *   person may act. A section owner cannot cover for someone on leave, and the
 *   lead auditor cannot step in at all — which is wrong in the other direction,
 *   because the lead is accountable for the engagement.
 *
 * THE RULE
 *   Three tiers may act, on each side independently:
 *     1. the person assigned to the control
 *     2. the person assigned to the section the control sits in
 *     3. the engagement lead (lead auditor for auditor actions; for auditee
 *        actions there is no "lead auditee" field yet, so the engagement owner
 *        stands in — see the note on ownerId below)
 *
 *   An unassigned control therefore falls to the section owner or the lead, not
 *   to everyone. Permission still gates the endpoint; this decides which
 *   instance that permission applies to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlAccessGuard {

    private final AuditSectionInstanceRepository sectionRepo;
    private final AuditEngagementRepository      engagementRepo;

    /** Auditee actions: uploading evidence, marking a control submitted. */
    public void requireCanSubmitEvidence(AuditControlInstance ctrl, Long userId) {
        if (canAct(ctrl, userId, true)) return;
        throw new BusinessException("CONTROL_NOT_ASSIGNED",
                "You can only submit evidence for controls assigned to you, or for controls "
                        + "in a section you own. Ask the engagement owner to assign it to you.",
                HttpStatus.FORBIDDEN);
    }

    /** Auditor actions: recording a test result, evaluating a control. */
    public void requireCanRecordResult(AuditControlInstance ctrl, Long userId) {
        if (canAct(ctrl, userId, false)) return;
        throw new BusinessException("CONTROL_NOT_ASSIGNED",
                "You can only record results for controls assigned to you, or for controls "
                        + "in a section you are assigned to. Ask the lead auditor to assign it.",
                HttpStatus.FORBIDDEN);
    }

    /** Non-throwing form, for hiding actions in the UI rather than failing them. */
    public boolean canAct(AuditControlInstance ctrl, Long userId, boolean auditeeSide) {
        if (ctrl == null || userId == null) return false;

        Long controlAssignee = auditeeSide
                ? ctrl.getAuditeeAssignedUserId()
                : ctrl.getAssignedAuditorId();
        if (userId.equals(controlAssignee)) return true;

        // Section owner — covers bulk-assigned sections and stand-ins.
        if (ctrl.getSectionInstanceId() != null) {
            boolean ownsSection = sectionRepo.findById(ctrl.getSectionInstanceId())
                    .map(s -> userId.equals(auditeeSide
                            ? s.getAuditeeAssignedUserId()
                            : s.getAssignedAuditorId()))
                    .orElse(false);
            if (ownsSection) return true;
        }

        // Engagement lead: leadAuditorId on the auditor side, leadAuditeeId on the
        // auditee side. ownerId remains a fallback for engagements created before
        // leadAuditeeId existed, which would otherwise have no auditee-side lead
        // and so no one able to act on an unassigned control.
        return engagementRepo.findById(ctrl.getEngagementId())
                .map(e -> auditeeSide
                        ? userId.equals(e.getLeadAuditeeId())
                          || (e.getLeadAuditeeId() == null && userId.equals(e.getOwnerId()))
                        : userId.equals(e.getLeadAuditorId()))
                .orElse(false);
    }
}