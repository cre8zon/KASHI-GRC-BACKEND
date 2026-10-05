package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.evidence.spi.EvidenceTargetAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Evidence and document access on audit control and test instances — the same
 * ControlAccessGuard rules the audit endpoints use, applied to the shared
 * evidence/document endpoints so the Evidence tabs cannot be worked around by
 * calling those endpoints directly.
 *
 *   CONTROL  read    tenant + guest engagement scope (as every instance read)
 *            attach  may act on the control on EITHER kind of work — the
 *                    evidence provider uploads, the tester may attach work
 *                    papers or link reused evidence. Nobody else.
 *            review  may record the control's result (accept/reject is an
 *                    auditor judgement on that control).
 *   TEST     read    tenant + guest engagement scope
 *            attach  may record the test's result (work papers are the
 *            review  tester's), which already covers testers of its controls.
 *
 * Unknown ids are refused with the same code as a denial, so ids cannot be
 * walked to learn which exist.
 */
@Component
@RequiredArgsConstructor
public class AuditEvidenceTargetAccessPolicy implements EvidenceTargetAccessPolicy {

    private static final String CONTROL = "AUDIT_CONTROL_INSTANCE";
    private static final String TEST    = "AUDIT_TEST_INSTANCE";

    private final ControlAccessGuard             guard;
    private final AuditControlInstanceRepository controlRepo;
    private final AuditTestInstanceRepository    testRepo;

    @Override
    public boolean supports(String entityType) {
        return CONTROL.equals(entityType) || TEST.equals(entityType);
    }

    @Override
    public void requireReadable(String entityType, Long entityId) {
        if (CONTROL.equals(entityType)) {
            AuditControlInstance c = control(entityId);
            guard.requireReadable(c.getTenantId(), c.getEngagementId());
        } else {
            AuditTestInstance t = test(entityId);
            guard.requireReadable(t.getTenantId(), t.getEngagementId());
        }
    }

    @Override
    public void requireCanAttach(String entityType, Long entityId, Long userId) {
        if (CONTROL.equals(entityType)) {
            AuditControlInstance c = control(entityId);
            guard.requireReadable(c.getTenantId(), c.getEngagementId());
            if (guard.canAct(c, userId, true) || guard.canAct(c, userId, false)) return;
            throw new BusinessException("CONTROL_NOT_ASSIGNED",
                    "You can only add or remove evidence on controls assigned or delegated to you, "
                            + "or in a section you own.",
                    HttpStatus.FORBIDDEN);
        }
        AuditTestInstance t = test(entityId);
        guard.requireReadable(t.getTenantId(), t.getEngagementId());
        guard.requireCanRecordTestResult(t, userId);
    }

    @Override
    public void requireCanReview(String entityType, Long entityId, Long userId) {
        if (CONTROL.equals(entityType)) {
            AuditControlInstance c = control(entityId);
            guard.requireReadable(c.getTenantId(), c.getEngagementId());
            guard.requireCanRecordResult(c, userId);
            return;
        }
        AuditTestInstance t = test(entityId);
        guard.requireReadable(t.getTenantId(), t.getEngagementId());
        guard.requireCanRecordTestResult(t, userId);
    }

    private AuditControlInstance control(Long id) {
        return controlRepo.findById(id).orElseThrow(AuditEvidenceTargetAccessPolicy::notAccessible);
    }

    private AuditTestInstance test(Long id) {
        return testRepo.findById(id).orElseThrow(AuditEvidenceTargetAccessPolicy::notAccessible);
    }

    private static BusinessException notAccessible() {
        return new BusinessException("AUDIT_INSTANCE_NOT_ACCESSIBLE",
                "You do not have access to this record.", HttpStatus.FORBIDDEN);
    }
}