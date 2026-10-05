package com.kashi.grc.audit.service;

import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditPolicyInstance;
import com.kashi.grc.audit.domain.AuditSectionInstance;
import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditControlInstanceTestMappingRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditPolicyInstanceControlMappingRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who may act on a control instance — and, since the obligation work, on a
 * test instance and a policy instance too.
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
 * THE SECOND HOLE (the one this revision closes)
 *   The previous rule let FOUR tiers act, every one of them permissive: the
 *   control's assignee, the section owner, anyone the live workflow step was
 *   routed to, and the lead. Assignment granted access but never WITHHELD it.
 *   The third tier is what made the product owner's complaint true: an
 *   ASSIGNMENT_SCOPED step routes one task per assigned user, engagement-wide,
 *   so every auditee who held any section passed on every control in the
 *   engagement — including controls in sections owned by somebody else.
 *
 * THE RULE NOW — per side (auditee / auditor), independently
 *
 *     0. Outside the caller's tenant, outside a guest's staffed engagements,
 *        or a vendor user                                           → DENY
 *     1. The caller is the control's assignee on this side          → ALLOW
 *     2. The caller holds a LIVE action item on this control for
 *        this side (a delegation, or a send-back reopen)            → ALLOW
 *     3. The caller holds this side's override PERMISSION           → ALLOW
 *     4. The control has an assignee on this side, and it is not
 *        the caller                                                 → DENY
 *     5. The control is unassigned on this side: the NEAREST section
 *        up the tree with an owner on this side decides — its owner
 *        may act, nobody else                                       → ALLOW/DENY
 *     6. Nothing is assigned anywhere above it: whoever the live
 *        workflow step is routed to (plus step-override holders),
 *        or the engagement lead                                     → ALLOW/DENY
 *
 *   Tier 2 LOOSENS: a delegate who holds no assignment can act.
 *   Tiers 4 and 5 TIGHTEN: an assigned control, or a control in an owned
 *   section, stops being open to the step actor and the lead.
 *   Tier 3 is the escape for the person covering for someone on leave, and it
 *   is a PERMISSION, never a role name, so whoever holds it can do the job
 *   whatever their role is called. It is deliberately not workflow:step:override
 *   — that authority force-advances workflow steps, and granting it to cover a
 *   colleague's control would also let them push the engagement forward.
 *
 * TESTS AND POLICIES
 *   Neither has an assignee of its own. Their "owner" is whoever may act on a
 *   control they are mapped to, auditor side — which already folds in that
 *   control's assignment, section ownership, delegations and override — plus
 *   anyone holding a live delegation on the test/policy itself, plus the
 *   auditor override. A test or policy mapped to no control at all falls back
 *   to tier 6.
 *
 * NON-THROWING FORM, AND THE LIST FORM
 *   canAct / canActOnTest / canActOnPolicy return booleans so payloads can tell
 *   the UI what the server will allow, and the UI mirrors the guard instead of
 *   inventing a rule of its own. evaluator() gives the same answers for many
 *   instances of one engagement with the per-engagement lookups done once —
 *   the engagement controls list used to call canAct twice per row, each call
 *   running the workflow query, so a 100-control list was ~600 queries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlAccessGuard {

    private final AuditSectionInstanceRepository sectionRepo;
    private final com.kashi.grc.workflow.service.WorkflowAccessService workflowAccessService;
    private final AuditEngagementRepository      engagementRepo;
    private final ActionItemRepository           actionItemRepository;
    private final AuditControlInstanceRepository controlRepo;
    private final AuditControlInstanceTestMappingRepository   ctrlTestMappingRepo;
    private final AuditPolicyInstanceControlMappingRepository policyCtrlMappingRepo;
    private final UtilityService                 utilityService;

    // ══════════════════════════════════════════════════════════════════════
    // THROWING FORMS — what the endpoints call
    // ══════════════════════════════════════════════════════════════════════

    /** Auditee actions: uploading evidence, marking a control submitted. */
    public void requireCanSubmitEvidence(AuditControlInstance ctrl, Long userId) {
        if (canAct(ctrl, userId, true)) return;
        throw new BusinessException("CONTROL_NOT_ASSIGNED",
                "You can only submit evidence for controls assigned to you, controls delegated "
                        + "to you, or controls in a section you own. Ask the section owner to "
                        + "assign or delegate it to you.",
                HttpStatus.FORBIDDEN);
    }

    /** Auditor actions: recording a test result, evaluating a control. */
    public void requireCanRecordResult(AuditControlInstance ctrl, Long userId) {
        if (canAct(ctrl, userId, false)) return;
        throw new BusinessException("CONTROL_NOT_ASSIGNED",
                "You can only record results for controls assigned to you, controls delegated "
                        + "to you, or controls in a section you are assigned to. Ask the lead "
                        + "auditor to assign or delegate it to you.",
                HttpStatus.FORBIDDEN);
    }

    /** Auditor action on a test instance (result, notes, work papers). */
    public void requireCanRecordTestResult(AuditTestInstance test, Long userId) {
        if (canActOnTest(test, userId)) return;
        throw new BusinessException("TEST_NOT_ASSIGNED",
                "You are not assigned to this test or to any control it is mapped to, and it "
                        + "has not been delegated to you.",
                HttpStatus.FORBIDDEN);
    }

    // ── DELEGATION — who may hand work on as an action item ─────────────────
    //
    // Narrower than "may act". Acting includes the control's own assignee, a
    // delegate, and the workflow step actor; none of them may pass the work on
    // again — a delegate re-delegating made chains nobody owned. Delegation is
    // for the people who OWN the work on that side:
    //
    //   • the section owner on that side (nearest section up the tree) — the primary
    //   • that side's engagement lead (leadAuditeeId / leadAuditorId)
    //   • the engagement owner — evidence side only: the owner is client staff,
    //     and handing out the auditor's testing would undo the independence the
    //     audit exists for
    //   • whoever holds that side's override permission

    public void requireCanDelegate(AuditControlInstance ctrl, Long userId, boolean auditeeSide) {
        if (ctrl != null && userId != null && evaluator(ctrl.getEngagementId(), userId).canDelegate(ctrl, auditeeSide)) return;
        throw new BusinessException("DELEGATION_NOT_ALLOWED",
                "Only the section owner, the engagement lead" + (auditeeSide ? ", the engagement owner" : "")
                        + " or someone with the override permission can delegate this control.",
                HttpStatus.FORBIDDEN);
    }

    public void requireCanDelegateTest(AuditTestInstance test, Long userId) {
        if (test != null && userId != null && evaluator(test.getEngagementId(), userId).canDelegateTest(test)) return;
        throw new BusinessException("DELEGATION_NOT_ALLOWED",
                "Only the owner of a section this test covers, the lead auditor or someone with the override "
                        + "permission can delegate this test.",
                HttpStatus.FORBIDDEN);
    }

    public void requireCanDelegatePolicy(AuditPolicyInstance policy, Long userId) {
        if (policy != null && userId != null && evaluator(policy.getEngagementId(), userId).canDelegatePolicy(policy)) return;
        throw new BusinessException("DELEGATION_NOT_ALLOWED",
                "Only the owner of a section this policy covers, the lead auditor or someone with the override "
                        + "permission can delegate this policy review.",
                HttpStatus.FORBIDDEN);
    }

    /** Auditor action on a policy instance (adequacy review, contribution). */
    public void requireCanReviewPolicy(AuditPolicyInstance policy, Long userId) {
        if (canActOnPolicy(policy, userId)) return;
        throw new BusinessException("POLICY_NOT_ASSIGNED",
                "You are not assigned to this policy or to any control it is mapped to, and it "
                        + "has not been delegated to you.",
                HttpStatus.FORBIDDEN);
    }

    /**
     * Read access to an instance: same tenant, and for a guest, an engagement
     * they are staffed on (or hold a delegation in). Vendors never.
     *
     * One code and message whether the row exists in another tenant or not, so
     * ids cannot be walked to learn what a different organisation holds.
     */
    public void requireReadable(Long instanceTenantId, Long engagementId) {
        if (isReadable(instanceTenantId, engagementId)) return;
        throw new BusinessException("AUDIT_INSTANCE_NOT_ACCESSIBLE",
                "You do not have access to this record.",
                HttpStatus.FORBIDDEN);
    }

    // ══════════════════════════════════════════════════════════════════════
    // NON-THROWING FORMS
    // ══════════════════════════════════════════════════════════════════════

    /** Non-throwing form, for hiding actions in the UI rather than failing them. */
    public boolean canAct(AuditControlInstance ctrl, Long userId, boolean auditeeSide) {
        if (ctrl == null || userId == null) return false;
        return evaluator(ctrl.getEngagementId(), userId).canAct(ctrl, auditeeSide);
    }

    public boolean canActOnTest(AuditTestInstance test, Long userId) {
        if (test == null || userId == null) return false;
        return evaluator(test.getEngagementId(), userId).canActOnTest(test);
    }

    public boolean canActOnPolicy(AuditPolicyInstance policy, Long userId) {
        if (policy == null || userId == null) return false;
        return evaluator(policy.getEngagementId(), userId).canActOnPolicy(policy);
    }

    /**
     * True when the caller holds {@code permission} — the same resolved set
     * (role grants, permission grants, user overrides) everything else here
     * checks. For engagement-wide actions that are not tied to one control
     * (pull evidence, bulk submit).
     */
    public boolean callerHolds(String permission) {
        return permission != null && currentPermissions().contains(permission);
    }

    /** True when the caller holds that side's override permission. */
    public boolean hasOverride(boolean auditeeSide) {
        return currentPermissions().contains(auditeeSide
                ? AuditObligationTypes.OVERRIDE_AUDITEE_PERMISSION
                : AuditObligationTypes.OVERRIDE_AUDITOR_PERMISSION);
    }

    /**
     * Same answers as the methods above, for many instances of ONE engagement,
     * with the engagement, the section tree, the caller's permissions and the
     * workflow tier each resolved once. Call {@link Evaluator#prefetchControls}
     * (and friends) with the whole id list first to turn the obligation lookups
     * into one query per list instead of one per row.
     */
    public Evaluator evaluator(Long engagementId, Long userId) {
        return new Evaluator(engagementId, userId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ASSIGNMENT AUTHORITY — who may assign / unassign a control's owner
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Ids of the controls the caller may NOT assign (or unassign) on one side.
     * Moved verbatim from AuditEngagementController so every assign endpoint
     * applies one rule:
     *
     *   1. The caller's workflow-resolved permission set for this engagement
     *      holds a SECTION-level assign permission (audit:section:assign-auditor
     *      or -auditee) — the workflow's own signal for "assigns across the
     *      engagement", not a hardcoded role or side → may assign anything; or
     *   2. the caller is recorded as that side's owner of the control's section
     *      or any ancestor section.
     *
     * The permission set is resolved ONCE and section rows are cached across
     * the walk, so a 100-control bulk assign costs one resolution.
     */
    public List<Long> deniedForAssignment(Long engagementId, List<AuditControlInstance> controls,
                                          Long callerId, boolean auditeeSide) {
        if (controls == null || controls.isEmpty()) return List.of();

        var user = utilityService.getLoggedInUserWithRolesAndPermissions();
        var access = workflowAccessService.resolveForModule(user, "AUDIT_ENGAGEMENT", engagementId);
        List<String> perms = access != null ? access.getPermissions() : List.of();

        boolean isSectionLevelAssigner = perms.contains("audit:section:assign-auditor")
                || perms.contains("audit:section:assign-auditee");
        if (isSectionLevelAssigner) return List.of();

        Map<Long, com.kashi.grc.audit.domain.AuditSectionInstance> sectionCache = new HashMap<>();
        List<Long> denied = new java.util.ArrayList<>();
        for (AuditControlInstance control : controls) {
            boolean ownsSection = false;
            Long currentSectionId = control.getSectionInstanceId();
            int hops = 0;
            while (currentSectionId != null && !ownsSection && hops++ < 64) {
                final Long lookupId = currentSectionId;
                var sec = sectionCache.computeIfAbsent(lookupId,
                        k -> sectionRepo.findById(k).orElse(null));
                if (sec == null) break;
                ownsSection = auditeeSide
                        ? callerId.equals(sec.getAuditeeAssignedUserId())
                        : callerId.equals(sec.getAssignedAuditorId());
                currentSectionId = sec.getParentInstanceId();
            }
            if (!ownsSection) denied.add(control.getId());
        }
        return denied;
    }

    /** Throwing single-control form of {@link #deniedForAssignment}. */
    public void requireCanAssign(AuditControlInstance control, Long callerId, boolean auditeeSide) {
        if (control == null) return;
        if (deniedForAssignment(control.getEngagementId(), List.of(control), callerId, auditeeSide).isEmpty()) return;
        throw new BusinessException("ACCESS_DENIED",
                "You can only assign within sections you are the designated owner for.",
                HttpStatus.FORBIDDEN);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SHARED HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /** Non-throwing form of requireReadable — for filtering rows out of a list. */
    public boolean isReadable(Long instanceTenantId, Long engagementId) {
        if (com.kashi.grc.common.config.multitenancy.AccessScope.isVendor()) return false;
        // ONE UtilityService call (it is @Transactional — each call is a DB
        // round trip, and this runs per row in list filters). Same test as
        // utilityService.isSystemUser(), done on the user already in hand.
        User me = utilityService.getLoggedInDataContext();
        boolean system = me.getRoles() != null && me.getRoles().stream()
                .anyMatch(r -> r.getSide() == com.kashi.grc.usermanagement.domain.RoleSide.SYSTEM);
        if (!system && !Objects.equals(me.getTenantId(), instanceTenantId)) return false;
        Set<Long> visible = com.kashi.grc.common.config.multitenancy.AccessScope.engagementIds();
        return visible == null || (engagementId != null && visible.contains(engagementId));
    }

    /**
     * The caller's resolved permissions — role grants, permission grants and
     * user overrides — through the SAME resolution the UI is driven from.
     * WorkflowAccessService caches it per request.
     */
    private List<String> currentPermissions() {
        try {
            User user = utilityService.getLoggedInUserWithRolesAndPermissions();
            List<String> perms = workflowAccessService.resolvePermissions(user);
            return perms != null ? perms : List.of();
        } catch (Exception ex) {
            // No authenticated user (should not happen on a guarded endpoint).
            // Fail closed: no override.
            log.warn("[CONTROL-GUARD] Could not resolve permissions: {}", ex.getMessage());
            return List.of();
        }
    }

    private Long currentUserId() {
        try {
            return utilityService.getLoggedInDataContext().getId();
        } catch (Exception ex) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // EVALUATOR
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Per-request, per-engagement access evaluation. Not thread-safe and not
     * meant to outlive the request that created it.
     */
    public final class Evaluator {

        private final Long engagementId;
        private final Long userId;

        // Lazily resolved, then reused for every instance evaluated.
        private Boolean                         readable;
        private AuditEngagement                 engagement;
        private boolean                         engagementLoaded;
        private Map<Long, AuditSectionInstance> sections;
        private Boolean                         overrideAuditee;
        private Boolean                         overrideAuditor;
        private Boolean                         workflowAuditee;
        private Boolean                         workflowAuditor;

        // Obligation sets — keyed by the ids already asked about.
        private final Map<Long, Boolean> ctrlAuditeeObligation = new HashMap<>();
        private final Map<Long, Boolean> ctrlAuditorObligation = new HashMap<>();
        private final Map<Long, Boolean> ctrlAuditeeDelegated  = new HashMap<>();
        private final Map<Long, Boolean> ctrlAuditorDelegated  = new HashMap<>();
        private final Map<Long, Boolean> testObligation        = new HashMap<>();
        private final Map<Long, Boolean> policyObligation      = new HashMap<>();
        private final Map<Long, AuditControlInstance> controlCache = new HashMap<>();

        // Resolved ONCE. UtilityService is @Transactional at class level, so even
        // its request-cached getLoggedInDataContext() opens and commits a
        // transaction on every call — a database round trip. Asked per row
        // (isCaller() ran ~6-8 times per control), a 156-control list made
        // ~1,100 round trips: 125 s on a remote database, with only 10 SQL
        // statements in the perf log. Never call UtilityService per row.
        private final boolean caller;
        private final Long    callerTenantId;

        private Evaluator(Long engagementId, Long userId) {
            this.engagementId = engagementId;
            this.userId       = userId;
            User me = null;
            try { me = utilityService.getLoggedInDataContext(); } catch (Exception ignored) { /* no auth */ }
            this.caller         = me != null && userId != null && userId.equals(me.getId());
            this.callerTenantId = me != null ? me.getTenantId() : null;
        }

        // ── Prefetch: one query per list ────────────────────────────────────

        public Evaluator prefetchControls(Collection<AuditControlInstance> controls) {
            if (controls == null || controls.isEmpty() || !isCaller()) return this;
            List<Long> ids = controls.stream().map(AuditControlInstance::getId)
                    .filter(Objects::nonNull).distinct().toList();
            controls.forEach(c -> { if (c.getId() != null) controlCache.put(c.getId(), c); });
            fill(ctrlAuditeeObligation, "AUDIT_CONTROL_INSTANCE", ids, AuditObligationTypes.CONTROL_AUDITEE_TYPES);
            fill(ctrlAuditorObligation, "AUDIT_CONTROL_INSTANCE", ids, AuditObligationTypes.CONTROL_AUDITOR_TYPES);
            fillDelegated(ctrlAuditeeDelegated, ids, AuditObligationTypes.CONTROL_AUDITEE_TYPES);
            fillDelegated(ctrlAuditorDelegated, ids, AuditObligationTypes.CONTROL_AUDITOR_TYPES);
            return this;
        }

        public Evaluator prefetchTests(Collection<Long> testIds) {
            if (testIds == null || testIds.isEmpty() || !isCaller()) return this;
            fill(testObligation, "AUDIT_TEST_INSTANCE", testIds.stream().distinct().toList(),
                    AuditObligationTypes.TEST_TYPES);
            return this;
        }

        public Evaluator prefetchPolicies(Collection<Long> policyIds) {
            if (policyIds == null || policyIds.isEmpty() || !isCaller()) return this;
            fill(policyObligation, "AUDIT_POLICY_INSTANCE", policyIds.stream().distinct().toList(),
                    AuditObligationTypes.POLICY_TYPES);
            return this;
        }

        // ── Controls ────────────────────────────────────────────────────────

        public boolean canAct(AuditControlInstance ctrl, boolean auditeeSide) {
            if (ctrl == null || userId == null) return false;
            if (!Objects.equals(ctrl.getEngagementId(), engagementId)) {
                // Defensive: an evaluator is bound to one engagement.
                return ControlAccessGuard.this.canAct(ctrl, userId, auditeeSide);
            }
            if (!readable(ctrl.getTenantId())) return false;

            // 1. Assignee on this side
            Long controlAssignee = auditeeSide
                    ? ctrl.getAuditeeAssignedUserId()
                    : ctrl.getAssignedAuditorId();
            if (userId.equals(controlAssignee)) return true;

            // 2. Live obligation on this control, this side
            if (hasControlObligation(ctrl.getId(), auditeeSide)) return true;

            // 3. Override permission for this side
            if (hasOverrideFor(auditeeSide)) return true;

            // 3b. I delegated this control's work and the delegation is live —
            //     the delegator keeps standing on what they handed out.
            if (delegatedByMe(ctrl.getId(), auditeeSide)) return true;

            // 4. Assigned to somebody else
            if (controlAssignee != null) return false;

            // 5. Nearest section up the tree with an owner on this side decides
            Long sectionOwner = nearestSectionOwner(ctrl.getSectionInstanceId(), auditeeSide);
            if (sectionOwner != null) return userId.equals(sectionOwner);

            // 6. Nothing assigned anywhere above it
            return unassignedFallback(auditeeSide);
        }

        /** True when the caller DELEGATED a live item on this control, this side. */
        public boolean delegatedByMe(Long controlId, boolean auditeeSide) {
            if (controlId == null || !isCaller()) return false;
            Map<Long, Boolean> cache = auditeeSide ? ctrlAuditeeDelegated : ctrlAuditorDelegated;
            if (!cache.containsKey(controlId)) {
                fillDelegated(cache, List.of(controlId), auditeeSide
                        ? AuditObligationTypes.CONTROL_AUDITEE_TYPES
                        : AuditObligationTypes.CONTROL_AUDITOR_TYPES);
            }
            return Boolean.TRUE.equals(cache.get(controlId));
        }

        private void fillDelegated(Map<Long, Boolean> cache, List<Long> ids, List<String> types) {
            if (ids.isEmpty()) return;
            if (callerTenantId == null) { ids.forEach(id -> cache.put(id, false)); return; }
            Set<Long> hits = actionItemRepository.findEntityIdsWithLiveItemDelegatedBy(
                    "AUDIT_CONTROL_INSTANCE", ids, userId, callerTenantId, types);
            ids.forEach(id -> cache.put(id, hits.contains(id)));
        }

        /** True when the caller holds a live delegation/reopen on this control, this side. */
        public boolean hasControlObligation(Long controlId, boolean auditeeSide) {
            if (controlId == null || !isCaller()) return false;
            Map<Long, Boolean> cache = auditeeSide ? ctrlAuditeeObligation : ctrlAuditorObligation;
            if (!cache.containsKey(controlId)) {
                fill(cache, "AUDIT_CONTROL_INSTANCE", List.of(controlId), auditeeSide
                        ? AuditObligationTypes.CONTROL_AUDITEE_TYPES
                        : AuditObligationTypes.CONTROL_AUDITOR_TYPES);
            }
            return Boolean.TRUE.equals(cache.get(controlId));
        }

        // ── Delegation ─────────────────────────────────────────────────────

        /** See requireCanDelegate. */
        public boolean canDelegate(AuditControlInstance ctrl, boolean auditeeSide) {
            if (ctrl == null || userId == null) return false;
            if (!Objects.equals(ctrl.getEngagementId(), engagementId)) {
                return ControlAccessGuard.this.evaluator(ctrl.getEngagementId(), userId).canDelegate(ctrl, auditeeSide);
            }
            if (!readable(ctrl.getTenantId())) return false;
            if (hasOverrideFor(auditeeSide)) return true;
            if (isLead(auditeeSide)) return true;
            return userId.equals(nearestSectionOwner(ctrl.getSectionInstanceId(), auditeeSide));
        }

        public boolean canDelegateTest(AuditTestInstance test) {
            if (test == null || userId == null) return false;
            if (!Objects.equals(test.getEngagementId(), engagementId)) {
                return ControlAccessGuard.this.evaluator(test.getEngagementId(), userId).canDelegateTest(test);
            }
            if (!readable(test.getTenantId())) return false;
            if (hasOverrideFor(false) || isLead(false)) return true;
            List<Long> mapped = test.getId() == null ? List.of()
                    : ctrlTestMappingRepo.findControlInstanceIdsByTestInstanceId(test.getId());
            return mapped != null && loadControls(mapped).stream().anyMatch(c -> canDelegate(c, false));
        }

        public boolean canDelegatePolicy(AuditPolicyInstance policy) {
            if (policy == null || userId == null) return false;
            if (!Objects.equals(policy.getEngagementId(), engagementId)) {
                return ControlAccessGuard.this.evaluator(policy.getEngagementId(), userId).canDelegatePolicy(policy);
            }
            if (!readable(policy.getTenantId())) return false;
            if (hasOverrideFor(false) || isLead(false)) return true;
            List<Long> mapped = policy.getId() == null ? List.of()
                    : policyCtrlMappingRepo.findControlInstanceIdsByPolicyInstanceId(policy.getId());
            return mapped != null && loadControls(mapped).stream().anyMatch(c -> canDelegate(c, false));
        }

        /** That side's engagement lead; on the evidence side the engagement owner too. */
        private boolean isLead(boolean auditeeSide) {
            AuditEngagement e = engagement();
            if (e == null) return false;
            return auditeeSide
                    ? userId.equals(e.getLeadAuditeeId()) || userId.equals(e.getOwnerId())
                    : userId.equals(e.getLeadAuditorId());
        }

        // ── Tests ───────────────────────────────────────────────────────────

        public boolean canActOnTest(AuditTestInstance test) {
            if (test == null || userId == null) return false;
            if (!Objects.equals(test.getEngagementId(), engagementId)) {
                return ControlAccessGuard.this.canActOnTest(test, userId);
            }
            if (!readable(test.getTenantId())) return false;

            if (hasTestObligation(test.getId())) return true;
            if (hasOverrideFor(false)) return true;

            List<Long> mappedControlIds = test.getId() == null ? List.of()
                    : ctrlTestMappingRepo.findControlInstanceIdsByTestInstanceId(test.getId());
            if (mappedControlIds == null || mappedControlIds.isEmpty()) {
                return unassignedFallback(false);
            }
            return loadControls(mappedControlIds).stream().anyMatch(c -> canAct(c, false));
        }

        public boolean hasTestObligation(Long testId) {
            if (testId == null || !isCaller()) return false;
            if (!testObligation.containsKey(testId)) {
                fill(testObligation, "AUDIT_TEST_INSTANCE", List.of(testId), AuditObligationTypes.TEST_TYPES);
            }
            return Boolean.TRUE.equals(testObligation.get(testId));
        }

        // ── Policies ────────────────────────────────────────────────────────

        public boolean canActOnPolicy(AuditPolicyInstance policy) {
            if (policy == null || userId == null) return false;
            if (!Objects.equals(policy.getEngagementId(), engagementId)) {
                return ControlAccessGuard.this.canActOnPolicy(policy, userId);
            }
            if (!readable(policy.getTenantId())) return false;

            if (hasPolicyObligation(policy.getId())) return true;
            if (hasOverrideFor(false)) return true;

            List<Long> mappedControlIds = policy.getId() == null ? List.of()
                    : policyCtrlMappingRepo.findControlInstanceIdsByPolicyInstanceId(policy.getId());
            if (mappedControlIds == null || mappedControlIds.isEmpty()) {
                return unassignedFallback(false);
            }
            return loadControls(mappedControlIds).stream().anyMatch(c -> canAct(c, false));
        }

        public boolean hasPolicyObligation(Long policyId) {
            if (policyId == null || !isCaller()) return false;
            if (!policyObligation.containsKey(policyId)) {
                fill(policyObligation, "AUDIT_POLICY_INSTANCE", List.of(policyId), AuditObligationTypes.POLICY_TYPES);
            }
            return Boolean.TRUE.equals(policyObligation.get(policyId));
        }

        // ── Internals ───────────────────────────────────────────────────────

        /**
         * The obligation and override lookups read the logged-in request's
         * tenant and permissions, so they are only meaningful when this
         * evaluator is for the caller. Asked about anybody else they answer
         * "no", which can only ever deny.
         */
        private boolean isCaller() {
            return caller;
        }

        private boolean readable(Long instanceTenantId) {
            if (readable == null) {
                readable = isCaller() && isReadable(instanceTenantId, engagementId);
            }
            return readable;
        }

        private boolean hasOverrideFor(boolean auditeeSide) {
            if (!isCaller()) return false;
            if (auditeeSide) {
                if (overrideAuditee == null) overrideAuditee = hasOverride(true);
                return overrideAuditee;
            }
            if (overrideAuditor == null) overrideAuditor = hasOverride(false);
            return overrideAuditor;
        }

        private AuditEngagement engagement() {
            if (!engagementLoaded) {
                engagement = engagementId == null ? null
                        : engagementRepo.findById(engagementId).orElse(null);
                engagementLoaded = true;
            }
            return engagement;
        }

        /**
         * Walks up from the control's own section. The first section with an
         * owner on this side is the one that decides — children inherit their
         * parent's assignment unless overridden, which is what
         * AuditSectionInstance documents and what assertCanActOnControlSection
         * already walks.
         */
        private Long nearestSectionOwner(Long sectionInstanceId, boolean auditeeSide) {
            if (sectionInstanceId == null) return null;
            if (sections == null) {
                sections = new HashMap<>();
                if (engagementId != null) {
                    sectionRepo.findByEngagementIdOrderByPathAscOrderNoAsc(engagementId)
                            .forEach(s -> sections.put(s.getId(), s));
                }
            }
            Long current = sectionInstanceId;
            int guard = 0;                      // cycle protection on bad data
            while (current != null && guard++ < 64) {
                AuditSectionInstance s = sections.get(current);
                if (s == null) {
                    s = sectionRepo.findById(current).orElse(null);
                    if (s == null) return null;
                    sections.put(s.getId(), s);
                }
                Long owner = auditeeSide ? s.getAuditeeAssignedUserId() : s.getAssignedAuditorId();
                if (owner != null) return owner;
                current = s.getParentInstanceId();
            }
            return null;
        }

        /**
         * Tier 6: nothing is assigned on this side anywhere above the instance.
         * Whoever the live workflow step is routed to (plus step-override
         * holders), or the engagement lead. ownerId stands in for the auditee
         * lead on engagements created before leadAuditeeId existed.
         */
        private boolean unassignedFallback(boolean auditeeSide) {
            if (auditeeSide) {
                if (workflowAuditee == null) workflowAuditee = workflowAllows(true);
                if (workflowAuditee) return true;
            } else {
                if (workflowAuditor == null) workflowAuditor = workflowAllows(false);
                if (workflowAuditor) return true;
            }
            AuditEngagement e = engagement();
            if (e == null) return false;
            return auditeeSide
                    ? userId.equals(e.getLeadAuditeeId())
                      || (e.getLeadAuditeeId() == null && userId.equals(e.getOwnerId()))
                    : userId.equals(e.getLeadAuditorId());
        }

        /**
         * Decided by the WORK, not by a role side. The side of a live step is
         * whatever the workflow blueprint says — an internal audit's evidence
         * step is often ORGANIZATION-side — so matching it against a fixed
         * "AUDITEE"/"AUDITOR" refused real evidence owners. Instead: the caller
         * holds an open task on a live step of this engagement (or the step
         * override), AND holds the permission for this work — the same code the
         * work's endpoint and ui_action require.
         */
        private boolean workflowAllows(boolean evidenceWork) {
            if (engagementId == null) return false;
            String workPermission = evidenceWork
                    ? "audit:control:submit-evidence"
                    : "audit:control:record-test-result";
            if (!currentPermissions().contains(workPermission)) return false;
            try {
                return workflowAccessService.canActOnEntity(
                        userId, "AUDIT_ENGAGEMENT", engagementId, null);
            } catch (Exception ex) {
                log.warn("[CONTROL-GUARD] Workflow tier failed closed | engagementId={} | {}",
                        engagementId, ex.getMessage());
                return false;
            }
        }

        private List<AuditControlInstance> loadControls(List<Long> ids) {
            List<Long> missing = ids.stream().filter(id -> !controlCache.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                controlRepo.findAllById(missing).forEach(c -> controlCache.put(c.getId(), c));
            }
            return ids.stream().map(controlCache::get).filter(Objects::nonNull).toList();
        }

        private void fill(Map<Long, Boolean> cache, String entityType, List<Long> ids,
                          List<String> remediationTypes) {
            if (ids.isEmpty()) return;
            Long tenantId = callerTenantId;
            if (tenantId == null) {
                ids.forEach(id -> cache.put(id, false));
                return;
            }
            Set<Long> hits = actionItemRepository.findEntityIdsWithLiveItemForAssignee(
                    entityType, ids, userId, tenantId, remediationTypes);
            ids.forEach(id -> cache.put(id, hits.contains(id)));
        }
    }
}