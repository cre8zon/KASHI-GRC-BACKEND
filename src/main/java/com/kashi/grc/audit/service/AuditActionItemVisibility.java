package com.kashi.grc.audit.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.spi.ActionItemEntityVisibility;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditPolicyInstanceRepository;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Who sees the delegations on an audit control / test / policy instance.
 *
 *   read      tenant + guest engagement scope — the same rule as opening the
 *             instance itself (ControlAccessGuard.requireReadable).
 *   see all   whoever may WORK the instance — its assignee, the section owner
 *             above it, the lead tier, override holders, or a delegate (they
 *             already hold the work) — i.e. the people who may delegate it.
 *   otherwise only the items the caller is party to (assignee / creator /
 *             reserved resolver), filtered by the controller.
 *
 * So a colleague who can open the control but has nothing to do with it sees
 * no one's delegations, while the owner sees every delegation they handed out.
 */
@Component
@RequiredArgsConstructor
public class AuditActionItemVisibility implements ActionItemEntityVisibility {

    private final ControlAccessGuard             guard;
    private final AuditControlInstanceRepository controlRepo;
    private final AuditTestInstanceRepository    testRepo;
    private final AuditPolicyInstanceRepository  policyRepo;

    @Override
    public boolean supports(ActionItem.EntityType entityType) {
        return entityType == ActionItem.EntityType.AUDIT_CONTROL_INSTANCE
                || entityType == ActionItem.EntityType.AUDIT_TEST_INSTANCE
                || entityType == ActionItem.EntityType.AUDIT_POLICY_INSTANCE;
    }

    @Override
    public void requireReadable(ActionItem.EntityType entityType, Long entityId) {
        switch (entityType) {
            case AUDIT_CONTROL_INSTANCE -> {
                var c = controlRepo.findById(entityId).orElseThrow(AuditActionItemVisibility::notAccessible);
                guard.requireReadable(c.getTenantId(), c.getEngagementId());
            }
            case AUDIT_TEST_INSTANCE -> {
                var t = testRepo.findById(entityId).orElseThrow(AuditActionItemVisibility::notAccessible);
                guard.requireReadable(t.getTenantId(), t.getEngagementId());
            }
            case AUDIT_POLICY_INSTANCE -> {
                var p = policyRepo.findById(entityId).orElseThrow(AuditActionItemVisibility::notAccessible);
                guard.requireReadable(p.getTenantId(), p.getEngagementId());
            }
            default -> { }
        }
    }

    @Override
    public boolean seesAll(ActionItem.EntityType entityType, Long entityId, Long userId) {
        return switch (entityType) {
            case AUDIT_CONTROL_INSTANCE -> controlRepo.findById(entityId)
                    .map(c -> guard.canAct(c, userId, true) || guard.canAct(c, userId, false))
                    .orElse(false);
            case AUDIT_TEST_INSTANCE -> testRepo.findById(entityId)
                    .map(t -> guard.canActOnTest(t, userId)).orElse(false);
            case AUDIT_POLICY_INSTANCE -> policyRepo.findById(entityId)
                    .map(p -> guard.canActOnPolicy(p, userId)).orElse(false);
            default -> false;
        };
    }

    private static BusinessException notAccessible() {
        return new BusinessException("AUDIT_INSTANCE_NOT_ACCESSIBLE",
                "You do not have access to this record.", HttpStatus.FORBIDDEN);
    }
}