package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditSectionInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.domain.TaskSectionCompletion;
import com.kashi.grc.workflow.domain.TaskSectionItem;
import com.kashi.grc.workflow.event.TaskSectionEvent;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.repository.TaskSectionCompletionRepository;
import com.kashi.grc.workflow.repository.TaskSectionItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Submit my sections" — the evidence owner's and the tester's own way to say
 * "this is done as it stands", without waiting for every control or for a lead
 * to force the whole step through.
 *
 * ── TWO KINDS, ONE SHAPE ──────────────────────────────────────────────────────
 *   EVIDENCE  (evidence owner)  for each of their controls still open:
 *               evidence uploaded, not yet submitted → submitted normally
 *               nothing uploaded                     → needs a reason; recorded
 *                                                      as "submitted without evidence"
 *   TESTING   (tester)          for each of their controls still NOT_TESTED:
 *               → needs a reason; recorded as "not tested" (a scope limitation
 *                 in the report — the result stays NOT_TESTED, never a conclusion)
 *
 * Either way each control is ticked off the person's checklist (one item per
 * control on the EVIDENCE_UPLOADED / CONTROLS_EVALUATED gate), so their task
 * completes the normal way when the last one is, and the step moves on when
 * everyone's is. Nothing here approves a task directly.
 *
 * ── WHOSE CONTROLS ────────────────────────────────────────────────────────────
 * With a workflow task: exactly the controls on THAT task's checklist — the
 * work the workflow gave this person. Without one (no workflow, or nothing
 * open for them): the controls the access guard says they may act on. Every
 * control is re-checked against the guard either way, so a control reassigned
 * since the checklist was built is skipped, not submitted in someone's name.
 *
 * ── PERMISSIONS ───────────────────────────────────────────────────────────────
 * The same permissions the per-control actions use: audit:control:submit-evidence
 * for EVIDENCE, audit:control:record-test-result for TESTING. No side or role
 * names are consulted.
 *
 * All-or-nothing: if any control that needs a reason has none, nothing is
 * submitted and the error lists them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditSectionSubmissionService {

    public static final String EVIDENCE = "EVIDENCE";
    public static final String TESTING  = "TESTING";

    private static final String PERM_EVIDENCE = "audit:control:submit-evidence";
    private static final String PERM_TESTING  = "audit:control:record-test-result";

    /** The completion events the two gates fire (see AuditEngagementService). */
    private static final String EVENT_EVIDENCE = "EVIDENCE_UPLOADED";
    private static final String EVENT_TESTING  = "TEST_RECORDED";

    private static final int MAX_REASON = 2000;

    private final AuditEngagementService          engagementService;
    private final AuditEngagementRepository       engagementRepository;
    private final AuditSectionInstanceRepository  sectionRepository;
    private final AuditControlInstanceRepository  controlRepository;
    private final ControlAccessGuard              guard;
    private final StepInstanceRepository          stepInstanceRepository;
    private final TaskInstanceRepository          taskInstanceRepository;
    private final TaskSectionCompletionRepository sectionCompletionRepository;
    private final TaskSectionItemRepository       sectionItemRepository;
    private final UtilityService                  utilityService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ══════════════════════ SUBMIT ═══════════════════════════════════════════

    /**
     * body: { kind?, taskId?, remarks?, sectionIds?: [..] (none = all of mine), reasons?: { controlId: "why" } }
     *
     * remarks is the reason applied to every control left empty (what the
     * ui_actions button collects); reasons overrides it per control.
     */
    @Transactional
    public Map<String, Object> submit(Long engagementId, Map<String, Object> body) {
        String kind = str(body.get("kind"));
        Long taskId = longOrNull(body.get("taskId"));
        Scope s = scope(engagementId, kind, taskId, null);

        Set<Long> chosen = new HashSet<>();
        Object raw = body.get("sectionIds");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                Long id = longOrNull(o);
                if (id != null) chosen.addAll(withDescendants(id, s.sections));
            }
        }
        // One reason for every control left empty — the action's remarks (ui_actions
        // requires_remarks). A per-control reason, when one was given, wins.
        String remarks = str(body.get("remarks"));
        if (remarks != null && remarks.length() > MAX_REASON) remarks = remarks.substring(0, MAX_REASON);
        Map<Long, String> reasons = new HashMap<>();
        if (body.get("reasons") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                Long id = longOrNull(k);
                String r = str(v);
                if (id != null && r != null) reasons.put(id, r.length() > MAX_REASON ? r.substring(0, MAX_REASON) : r);
            });
        }

        List<AuditControlInstance> targets = s.controls.stream()
                .filter(c -> chosen.isEmpty() || chosen.contains(c.getSectionInstanceId()))
                .toList();
        if (targets.isEmpty()) {
            throw new BusinessException("SECTION_SUBMIT_NOTHING", "There is nothing of yours to submit in these sections");
        }

        // Validate everything first — all or nothing.
        List<String> missing = new ArrayList<>();
        for (AuditControlInstance c : targets) {
            String st = state(s, c);
            if ("MISSING".equals(st) && !reasons.containsKey(c.getId())) missing.add(label(c));
        }
        if (remarks != null) {
            for (AuditControlInstance c : targets) {
                if ("MISSING".equals(state(s, c))) reasons.putIfAbsent(c.getId(), remarks);
            }
            missing.clear();
        }
        if (!missing.isEmpty()) {
            String what = TESTING.equals(s.kind) ? "left untested" : "with no evidence";
            throw new BusinessException("SECTION_SUBMIT_REASONS_REQUIRED",
                    "Give a reason for each control " + what + " (" + missing.size() + "): "
                            + String.join(", ", missing.subList(0, Math.min(8, missing.size())))
                            + (missing.size() > 8 ? " …" : ""));
        }

        Long me = s.userId;
        Long tenantId = s.engagement.getTenantId();
        int submitted = 0, gaps = 0, already = 0;
        for (AuditControlInstance c : targets) {
            String st = state(s, c);
            if (EVIDENCE.equals(s.kind)) {
                switch (st) {
                    case "READY" -> { engagementService.submitControlEvidence(engagementId, c.getId(), me, tenantId); submitted++; }
                    case "MISSING" -> { engagementService.submitControlWithoutEvidence(c, reasons.get(c.getId()), me, tenantId); gaps++; }
                    case "GAP" -> {
                        if (reasons.containsKey(c.getId())) engagementService.submitControlWithoutEvidence(c, reasons.get(c.getId()), me, tenantId);
                        else engagementService.tickControl(EVENT_EVIDENCE, c.getId(), engagementId, me);
                        already++;
                    }
                    default -> { engagementService.tickControl(EVENT_EVIDENCE, c.getId(), engagementId, me); already++; }
                }
            } else {
                switch (st) {
                    case "MISSING" -> { engagementService.markControlNotTested(c, reasons.get(c.getId()), me, tenantId); gaps++; }
                    case "GAP" -> {
                        if (reasons.containsKey(c.getId())) engagementService.markControlNotTested(c, reasons.get(c.getId()), me, tenantId);
                        else engagementService.tickControl(EVENT_TESTING, c.getId(), engagementId, me);
                        already++;
                    }
                    default -> { engagementService.tickControl(EVENT_TESTING, c.getId(), engagementId, me); already++; }
                }
            }
        }

        // Testing has no roll-up of its own — stamp each touched section whose
        // controls are now all concluded or explained.
        if (TESTING.equals(s.kind)) {
            Set<Long> touched = new LinkedHashSet<>();
            targets.forEach(c -> touched.add(c.getSectionInstanceId()));
            LocalDateTime now = LocalDateTime.now();
            for (Long sid : touched) {
                AuditSectionInstance sec = s.sections.get(sid);
                if (sec == null || sec.getTestingSubmittedAt() != null) continue;
                boolean allDone = controlRepository.findBySectionInstanceIdOrderByOrderNoAsc(sid).stream()
                        .allMatch(AuditSectionSubmissionService::testingDone);
                if (allDone) {
                    sec.setTestingSubmittedAt(now);
                    sec.setTestingSubmittedBy(me);
                    sectionRepository.save(sec);
                }
            }
        }

        closeEmptyGate(s);

        log.info("[AUDIT-SECTION-SUBMIT] {} | engagementId={} | by={} | task={} | controls={} | submitted={} | gaps={} | already={}",
                s.kind, engagementId, me, s.task != null ? s.task.getId() : null, targets.size(), submitted, gaps, already);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", s.kind);
        out.put("submitted", submitted);
        out.put("withReason", gaps);
        out.put("alreadyDone", already);
        return out;
    }

    /**
     * A task whose item-tracked gate has NO items (registered before inherited
     * controls were counted) can never close by ticking items. Once every
     * control of this person's is done or explained, close the gate itself —
     * the one-shot event the gate's listener understands — so the task
     * completes the normal way.
     */
    private void closeEmptyGate(Scope s) {
        if (s.task == null) return;
        TaskSectionCompletion sec = sectionCompletionRepository
                .findByTaskInstanceIdAndSnapCompletionEvent(s.task.getId(), eventOf(s.kind)).orElse(null);
        if (sec == null || sec.isCompleted() || !sec.isSnapTracksItems()) return;
        if (!sectionItemRepository.findByTaskInstanceIdAndSectionKey(s.task.getId(), sec.getSnapSectionKey()).isEmpty()) return;
        List<AuditControlInstance> fresh = controlRepository.findAllById(s.controls.stream().map(AuditControlInstance::getId).toList());
        boolean allDone = fresh.stream().allMatch(c -> EVIDENCE.equals(s.kind)
                ? AuditEngagementService.evidenceDone(c) : testingDone(c));
        if (!allDone) return;
        eventPublisher.publishEvent(TaskSectionEvent.sectionDone(eventOf(s.kind), s.task.getId(), s.userId));
        log.info("[AUDIT-SECTION-SUBMIT] Closed empty {} gate | taskId={} | by={}", s.kind, s.task.getId(), s.userId);
    }

    // ══════════════════════ ONE CONTROL ══════════════════════════════════════

    /** POST …/controls/{cid}/submit-without-evidence { remarks } — the per-control button. */
    @Transactional
    public Map<String, Object> submitWithoutEvidence(Long engagementId, Long controlId, Map<String, Object> body) {
        AuditControlInstance c = control(engagementId, controlId, PERM_EVIDENCE);
        Long me = utilityService.getLoggedInDataContext().getId();
        guard.requireCanSubmitEvidence(c, me);
        if (c.isAuditeeEvidenceSubmitted()) {
            throw new BusinessException("EVIDENCE_ALREADY_SUBMITTED", "Evidence for this control is already submitted");
        }
        engagementService.submitControlWithoutEvidence(c, reason(body), me, c.getTenantId());
        return Map.of("controlId", c.getId(), "evidenceGapReason", c.getEvidenceGapReason());
    }

    /** POST …/controls/{cid}/mark-not-tested { remarks } — the per-control button. */
    @Transactional
    public Map<String, Object> markNotTested(Long engagementId, Long controlId, Map<String, Object> body) {
        AuditControlInstance c = control(engagementId, controlId, PERM_TESTING);
        Long me = utilityService.getLoggedInDataContext().getId();
        guard.requireCanRecordResult(c, me);
        if (c.isTestConcluded()) {
            throw new BusinessException("CONTROL_ALREADY_TESTED",
                    "This control already has a result — change the result instead");
        }
        engagementService.markControlNotTested(c, reason(body), me, c.getTenantId());
        return Map.of("controlId", c.getId(), "notTestedReason", c.getNotTestedReason());
    }

    /**
     * POST …/controls/{cid}/reopen-evidence { remarks } — the evidence side asks
     * for the submitted evidence to be redone, before the auditor concludes.
     * Same people who may delegate the control's evidence (section owner, lead
     * auditee, engagement owner, override) — see ControlAccessGuard.canDelegate.
     */
    @Transactional
    public Map<String, Object> reopenEvidence(Long engagementId, Long controlId, Map<String, Object> body) {
        AuditControlInstance c = control(engagementId, controlId, PERM_EVIDENCE);
        Long me = utilityService.getLoggedInDataContext().getId();
        guard.requireCanDelegate(c, me, true);
        if (!c.isAuditeeEvidenceSubmitted() && !notBlank(c.getEvidenceGapReason())) {
            throw new BusinessException("EVIDENCE_NOT_SUBMITTED", "Nothing has been submitted on this control yet");
        }
        if (c.isTestConcluded()) {
            throw new BusinessException("CONTROL_ALREADY_TESTED",
                    "The auditor has already concluded this control — ask the auditor to send it back");
        }
        engagementService.reopenEvidenceByAuditeeSide(c, reason(body), me, c.getTenantId());
        return Map.of("controlId", c.getId(), "reopened", true);
    }

    private AuditControlInstance control(Long engagementId, Long controlId, String permission) {
        AuditControlInstance c = controlRepository.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", controlId));
        if (!c.getEngagementId().equals(engagementId)) {
            throw new BusinessException("CONTROL_MISMATCH", "Control does not belong to this engagement");
        }
        guard.requireReadable(c.getTenantId(), engagementId);
        if (!guard.callerHolds(permission)) {
            throw new BusinessException("SECTION_SUBMIT_DENIED", "You do not have permission to do this", HttpStatus.FORBIDDEN);
        }
        return c;
    }

    private static String reason(Map<String, Object> body) {
        String r = body == null ? null : str(body.get("remarks"));
        if (r == null) throw new BusinessException("REASON_REQUIRED", "Give a reason");
        return r.length() > MAX_REASON ? r.substring(0, MAX_REASON) : r;
    }

    // ══════════════════════ SCOPE ════════════════════════════════════════════

    private record Scope(AuditEngagement engagement, String kind, Long userId, TaskInstance task,
                         Map<Long, AuditSectionInstance> sections, List<AuditControlInstance> controls,
                         Map<Long, Boolean> hasEvidence) {}

    private Scope scope(Long engagementId, String requestedKind, Long taskId, Long sectionId) {
        AuditEngagement e = engagementRepository.findById(engagementId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", engagementId));
        guard.requireReadable(e.getTenantId(), e.getId());
        Long me = utilityService.getLoggedInDataContext().getId();

        TaskInstance task = myTask(e, me, taskId, requestedKind);
        String kind = requestedKind != null ? requestedKind.toUpperCase()
                : task != null ? kindOf(task)
                  : guard.callerHolds(PERM_EVIDENCE) ? EVIDENCE : TESTING;
        if (!EVIDENCE.equals(kind) && !TESTING.equals(kind)) {
            throw new BusinessException("SECTION_SUBMIT_BAD_KIND", "kind must be EVIDENCE or TESTING");
        }
        if (!guard.callerHolds(EVIDENCE.equals(kind) ? PERM_EVIDENCE : PERM_TESTING)) {
            throw new BusinessException("SECTION_SUBMIT_DENIED",
                    "You do not have permission to submit " + (EVIDENCE.equals(kind) ? "evidence" : "test results"),
                    HttpStatus.FORBIDDEN);
        }
        if (task != null && !kind.equals(kindOf(task))) task = null;

        Map<Long, AuditSectionInstance> sections = new LinkedHashMap<>();
        sectionRepository.findByEngagementIdOrderByPathAscOrderNoAsc(e.getId()).forEach(x -> sections.put(x.getId(), x));

        List<AuditControlInstance> all = controlRepository.findByEngagementId(e.getId());
        boolean auditeeSide = EVIDENCE.equals(kind);
        var ev = guard.evaluator(e.getId(), me).prefetchControls(all);

        Set<Long> onChecklist = null;
        if (task != null) {
            TaskSectionCompletion sec = sectionCompletionRepository
                    .findByTaskInstanceIdAndSnapCompletionEvent(task.getId(), eventOf(kind)).orElse(null);
            if (sec != null && sec.isSnapTracksItems()) {
                Set<Long> refs = new HashSet<>();
                for (TaskSectionItem it : sectionItemRepository.findByTaskInstanceIdAndSectionKey(task.getId(), sec.getSnapSectionKey())) {
                    refs.add(it.getItemRefId());
                }
                // An EMPTY checklist is a task registered before inherited
                // (section-level) controls were counted — not "you own nothing".
                // Fall back to the access guard for those.
                if (!refs.isEmpty()) onChecklist = refs;
            }
        }
        Set<Long> inSection = sectionId == null ? null : withDescendants(sectionId, sections);
        final Set<Long> checklist = onChecklist;
        List<AuditControlInstance> mine = all.stream()
                .filter(c -> checklist == null || checklist.contains(c.getId()))
                .filter(c -> inSection == null || inSection.contains(c.getSectionInstanceId()))
                .filter(c -> ev.canAct(c, auditeeSide))
                .sorted(Comparator
                        .comparing((AuditControlInstance c) -> {
                            AuditSectionInstance sec = sections.get(c.getSectionInstanceId());
                            return sec != null && sec.getPath() != null ? sec.getPath() : "";
                        })
                        .thenComparing(c -> c.getOrderNo() == null ? 0 : c.getOrderNo()))
                .toList();

        Map<Long, Boolean> hasEvidence = new HashMap<>();
        if (auditeeSide) {
            for (AuditControlInstance c : mine) {
                if (!c.isAuditeeEvidenceSubmitted()) hasEvidence.put(c.getId(), engagementService.hasSubmittableEvidence(c));
            }
        }
        return new Scope(e, kind, me, task, sections, mine, hasEvidence);
    }

    /** My open ACTOR task on the engagement's current step that carries one of the two gates. */
    private TaskInstance myTask(AuditEngagement e, Long me, Long taskId, String kind) {
        if (taskId != null) {
            TaskInstance t = taskInstanceRepository.findById(taskId).orElse(null);
            boolean usable = t != null && me.equals(t.getAssignedUserId())
                    && (t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.IN_PROGRESS)
                    && kindOf(t) != null;
            return usable ? t : null;
        }
        if (e.getWorkflowInstanceId() == null) return null;
        for (var step : stepInstanceRepository.findByWorkflowInstanceIdAndStatus(e.getWorkflowInstanceId(), StepStatus.IN_PROGRESS)) {
            List<TaskInstance> tasks = new ArrayList<>(taskInstanceRepository.findByStepInstanceIdAndStatus(step.getId(), TaskStatus.IN_PROGRESS));
            tasks.addAll(taskInstanceRepository.findByStepInstanceIdAndStatus(step.getId(), TaskStatus.PENDING));
            for (TaskInstance t : tasks) {
                if (!me.equals(t.getAssignedUserId()) || t.getTaskRole() != TaskRole.ACTOR) continue;
                String k = kindOf(t);
                if (k != null && (kind == null || k.equalsIgnoreCase(kind))) return t;
            }
        }
        return null;
    }

    /** EVIDENCE / TESTING by which gate the task carries; null when neither. */
    private String kindOf(TaskInstance t) {
        if (sectionCompletionRepository.findByTaskInstanceIdAndSnapCompletionEvent(t.getId(), EVENT_EVIDENCE).isPresent()) return EVIDENCE;
        if (sectionCompletionRepository.findByTaskInstanceIdAndSnapCompletionEvent(t.getId(), EVENT_TESTING).isPresent()) return TESTING;
        return null;
    }

    private static String eventOf(String kind) {
        return EVIDENCE.equals(kind) ? EVENT_EVIDENCE : EVENT_TESTING;
    }

    // ══════════════════════ STATES ═══════════════════════════════════════════

    /**
     * DONE     evidence submitted / result recorded
     * GAP      already explained (submitted without evidence / not tested)
     * READY    evidence uploaded, not yet submitted (EVIDENCE only)
     * MISSING  nothing yet — needs a reason to submit
     */
    private static String state(Scope s, AuditControlInstance c) {
        if (EVIDENCE.equals(s.kind)) {
            if (c.isAuditeeEvidenceSubmitted()) return "DONE";
            if (notBlank(c.getEvidenceGapReason())) return "GAP";
            return Boolean.TRUE.equals(s.hasEvidence.get(c.getId())) ? "READY" : "MISSING";
        }
        if (c.isTestConcluded()) return "DONE";
        return notBlank(c.getNotTestedReason()) ? "GAP" : "MISSING";
    }

    private static boolean testingDone(AuditControlInstance c) {
        return c.isTestConcluded() || notBlank(c.getNotTestedReason());
    }

    // ══════════════════════ HELPERS ══════════════════════════════════════════

    /** The section and every section beneath it, within this engagement. */
    private static Set<Long> withDescendants(Long rootId, Map<Long, AuditSectionInstance> sections) {
        Set<Long> out = new LinkedHashSet<>();
        if (!sections.containsKey(rootId)) return out;
        out.add(rootId);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (AuditSectionInstance x : sections.values()) {
                if (x.getParentInstanceId() != null && out.contains(x.getParentInstanceId()) && out.add(x.getId())) grew = true;
            }
        }
        return out;
    }

    private static String label(AuditControlInstance c) {
        return c.getControlCodeSnapshot() != null && !c.getControlCodeSnapshot().isBlank()
                ? c.getControlCodeSnapshot() : "control " + c.getId();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long longOrNull(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(o.toString().trim()); } catch (NumberFormatException e) { return null; }
    }
}