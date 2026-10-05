package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.dto.request.*;
import com.kashi.grc.audit.dto.response.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.evidence.repository.EvidenceLinkRepository;
import com.kashi.grc.document.repository.DocumentLinkRepository;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.event.TaskSectionEvent;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.repository.TaskSectionCompletionRepository;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEngagementService {

    private final AuditProjectRepository                    projectRepository;
    private final AuditProjectInstanceRepository            projectInstanceRepository;
    private final AuditEngagementRepository                 engagementRepository;
    private final AuditEngagementTemplateInstanceRepository templateInstanceRepository;
    private final AuditSectionInstanceRepository            sectionInstanceRepository;
    private final AuditControlInstanceRepository            controlInstanceRepository;
    private final AuditTemplateRepository                   templateRepository;
    private final AuditTemplateSectionMappingRepository     templateSectionMappingRepository;
    private final AuditSectionRepository                    sectionRepository;
    private final AuditSectionService                       sectionService;
    private final WorkflowEngineService                     workflowEngineService;
    private final WorkflowRepository                        workflowRepository;
    private final NotificationService                       notificationService;
    private final com.kashi.grc.usermanagement.repository.UserRepository userRepository;

    // Self-injected via the Spring proxy — NOT the same as `this`. Needed
    // because completeEngagementProvisioning() calls snapshotTemplate() and
    // markSnapshotFailed(), both in this same class. A plain `this.method()`
    // call bypasses Spring's AOP proxy entirely, which means @Transactional
    // on the callee is silently ignored — each JPA save/JDBC insert inside
    // would commit independently instead of as one atomic unit. That was the
    // actual root cause of an infinite Kafka retry loop: a later insert
    // failing couldn't roll back an earlier insert that had already
    // committed on its own, so every retry hit a duplicate-key error on the
    // same already-committed row, forever. Calling through `self` instead
    // routes through the proxy, so @Transactional actually applies.
    //
    // NOT constructor-injected via @RequiredArgsConstructor: Lombok does not
    // copy field-level annotations onto the constructor parameter it
    // generates, so @Lazy here was silently dropped and Spring tried to
    // eagerly resolve AuditEngagementService while still constructing
    // AuditEngagementService — a genuine, unresolvable circular dependency
    // ("Requested bean is currently in creation"). Field injection with
    // @Autowired keeps @Lazy on the actual injection point, which defers
    // resolution until first use and correctly breaks the cycle.
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private AuditEngagementService self;
    private final AuditTestPolicySnapshotService            testPolicySnapshotService;
    private final WorkflowInstanceRepository                workflowInstanceRepository;
    private final StepInstanceRepository                    stepInstanceRepository;
    private final TaskInstanceRepository                    taskInstanceRepository;
    // FIX: use ApplicationEventPublisher + TaskSectionEvent instead of calling
    // sectionCompletionService.onSectionEvent() directly — the service method
    // takes a TaskSectionEvent record, not separate parameters.
    private final TaskSectionCompletionRepository           taskSectionCompletionRepository;
    private final com.kashi.grc.workflow.service.TaskSectionCompletionService taskSectionCompletionService;
    private final ApplicationEventPublisher                 eventPublisher;
    private final EvidenceLinkRepository evidenceLinkRepository;
    private final DocumentLinkRepository documentLinkRepository;
    // ObjectProvider, not a direct dependency — KafkaEventPublisher only exists
    // as a bean when kashi.kafka.enabled=true. getIfAvailable() returning null
    // is the signal to fall back to synchronous snapshotting (see create()).
    private final org.springframework.beans.factory.ObjectProvider<com.kashi.grc.common.kafka.KafkaEventPublisher> kafkaEventPublisherProvider;
    // Who may act on a control (one rule for every endpoint) and the per-control
    // obligations that rule honours. Neither depends back on this service.
    private final ControlAccessGuard                        controlAccessGuard;
    private final AuditObligationService                    obligationService;
    private final AuditControlInstanceTestMappingRepository controlTestMappingRepository;
    private final com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository membershipRepository;
    private final com.kashi.grc.usermanagement.service.role.PermissionHolderService permissionHolderService;

    // ── OWNER ELIGIBILITY ─────────────────────────────────────────────────────

    /**
     * The engagement owner is the CLIENT's accountable person: workflow steps on
     * the ORGANIZATION side resolve to them (AuditEngagementEntityResolver), and
     * on engagements without a lead auditee they are the one who assigns evidence
     * owners (ControlAccessGuard.unassignedFallback). An auditor in that seat
     * would approve their own audit on the client's behalf and pick who supplies
     * the evidence they test. So the owner must:
     *
     *   • be one of the client's own people — a usable HOME membership in this
     *     tenant. An invited external auditor's membership here is GUEST.
     *   • not be the engagement's lead auditor.
     *
     *   • hold audit:section:assign-auditee in this tenant — the owner assigns
     *     evidence owners when there is no lead auditee, so they must be someone
     *     allowed to. This is also what keeps the client's own internal-audit
     *     staff out of the seat, without naming a role or a side.
     *
     * Decided by membership, permission and the engagement's own assignments —
     * no role names or role sides. A null owner (or tenant) is left to the caller.
     */
    public static final String OWNER_PERMISSION = "audit:section:assign-auditee";

    public void requireEligibleOwner(Long ownerId, Long leadAuditorId, Long tenantId) {
        if (ownerId == null || tenantId == null) return;
        if (ownerId.equals(leadAuditorId)) {
            throw new BusinessException("OWNER_IS_LEAD_AUDITOR",
                    "The engagement owner cannot also be its lead auditor");
        }
        boolean clientStaff = membershipRepository.findByUserIdAndTenantId(ownerId, tenantId)
                .filter(m -> m.isUsable() && "HOME".equalsIgnoreCase(m.getMembershipType()))
                .isPresent();
        if (!clientStaff) {
            throw new BusinessException("OWNER_NOT_CLIENT_STAFF",
                    "The engagement owner must be a member of your organisation, not an invited external auditor");
        }
        if (!permissionHolderService.holds(ownerId, tenantId, OWNER_PERMISSION)) {
            throw new BusinessException("OWNER_LACKS_PERMISSION",
                    "The engagement owner must be someone allowed to assign evidence owners ("
                            + OWNER_PERMISSION + ")");
        }
    }

    // ── CREATE ────────────────────────────────────────────────────────────────

    @Transactional
    public AuditEngagementResponse create(AuditEngagementRequest req, Long createdBy, Long tenantId) {
        return create(req, createdBy, tenantId, true);
    }

    /**
     * @param startWorkflow if false, skips startWorkflowIfConfigured() — used when this
     *                       engagement is being cascaded as part of a project-level
     *                       "Start Project" action, where a single workflow-16 instance
     *                       governs ALL engagements in the project. Workflow 14 (per-
     *                       engagement) must NOT start in that case.
     */
    public AuditEngagementResponse create(AuditEngagementRequest req, Long createdBy, Long tenantId, boolean startWorkflow) {
        // Project is optional — standalone engagement without project is supported.
        // findByTenantIdAndId() only matches tenant-owned projects and misses GLOBAL
        // projects (tenantId=NULL in DB). Use findById() + explicit filter instead so
        // org users can create engagements under a global library project (e.g. id=3).
        if (req.getProjectId() != null) {
            projectRepository.findById(req.getProjectId())
                    .filter(p -> p.getTenantId() == null || p.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new ResourceNotFoundException("AuditProject", req.getProjectId()));
        }

        String ref = buildEngagementRef(tenantId);

        // Deduplication guard — reject rapid double-clicks (same name+template within 60 seconds)
        // CHECK BEFORE SAVE so we don't create then throw and leave orphan rows
        boolean duplicate = engagementRepository
                .existsByTenantIdAndNameAndTemplateIdAndCreatedAtAfter(
                        tenantId, req.getName(), req.getTemplateId(),
                        LocalDateTime.now().minusSeconds(60));
        if (duplicate) {
            throw new BusinessException("DUPLICATE_ENGAGEMENT",
                    "This engagement was just created — please wait a moment before trying again");
        }

        // An owner named on the form must be one of the client's own people and
        // not the lead auditor. The default (the creator) is not checked —
        // whoever may create the engagement may own it.
        if (req.getOwnerId() != null) {
            requireEligibleOwner(req.getOwnerId(), req.getLeadAuditorId(), tenantId);
        }

        AuditEngagement engagement = AuditEngagement.builder()
                .engagementRef(ref)
                .projectId(req.getProjectId())
                .tenantId(tenantId)
                .name(req.getName())
                .description(req.getDescription())
                .templateId(req.getTemplateId())
                .frameworkRef(req.getFrameworkRef())
                .auditType(req.getAuditType() != null ? req.getAuditType() : AuditTemplate.AuditType.INTERNAL)
                // Project-governed engagements (projectInstanceId set) start as FIELDWORK
                // since the project workflow (WF16) governs their lifecycle — they should
                // never show the individual "Activate" button (which checks for PLANNING).
                // FIELDWORK is the status that activate() would set anyway.
                // Standalone engagements start as PLANNING and require individual activation.
                .status(req.getProjectInstanceId() != null
                        ? AuditEngagement.Status.FIELDWORK
                        : AuditEngagement.Status.PLANNING)
                .leadAuditorId(req.getLeadAuditorId())
                // Was accepted on the request and used for the notification below,
                // but never written — so the column stayed NULL and every reader of
                // it (list/overview, the AUDITEE-ASSIGN step resolver, both finding
                // escalation paths) silently fell back to someone else.
                .leadAuditeeId(req.getLeadAuditeeId())
                .ownerId(req.getOwnerId() != null ? req.getOwnerId() : createdBy)
                .createdBy(createdBy)
                // FIX: request has LocalDate, domain has LocalDateTime — convert with atStartOfDay()
                .plannedStart(req.getPlannedStart() != null ? req.getPlannedStart().atStartOfDay() : null)
                .plannedEnd(req.getPlannedEnd()     != null ? req.getPlannedEnd().atStartOfDay()   : null)
                .snapshotStatus(req.getTemplateId() != null ? "PROVISIONING" : null)
                .build();

        engagementRepository.save(engagement);

        log.info("[AUDIT] Created | ref={} | type={} | tenantId={}", ref, engagement.getAuditType(), tenantId);

        // Link engagement to a project instance.
        //
        // FAST PATH (createProjectInstance cascade): controller pre-creates the AuditProjectInstance
        // and passes its id via req.projectInstanceId — use it directly. This is the ONLY valid
        // path for programme-level engagements. Multiple runs of the same project (2026, 2027…)
        // each create their own AuditProjectInstance first, then cascade N engagements under it.
        //
        // STANDALONE PATH (direct POST /v1/audit/engagements with a projectId but no instance):
        // Not used in the current project-instance flow, but kept for backwards compatibility.
        // Creates a fresh instance — never tries to find an existing one, since there can be
        // many instances per project and there is no way to know which one to attach to.
        if (req.getProjectId() != null) {
            AuditProjectInstance projInst;
            if (req.getProjectInstanceId() != null) {
                // Fast path — instance already created by the controller, use it directly
                projInst = projectInstanceRepository.findById(req.getProjectInstanceId())
                        .orElseThrow(() -> new ResourceNotFoundException("AuditProjectInstance", req.getProjectInstanceId()));
            } else {
                // Standalone path — create a fresh instance (never query for an existing one;
                // multiple instances per project are valid and there is no unique one to reuse)
                AuditProject project = projectRepository.findById(req.getProjectId())
                        .orElseThrow(() -> new ResourceNotFoundException("AuditProject", req.getProjectId()));
                projInst = projectInstanceRepository.save(
                        AuditProjectInstance.builder()
                                .originalProjectId(project.getId())
                                .tenantId(tenantId)
                                .projectNameSnapshot(project.getName())
                                .projectRefSnapshot(project.getProjectRef())
                                .descriptionSnapshot(project.getDescription())
                                .ownerIdSnapshot(project.getOwnerId())
                                .plannedStartSnapshot(project.getPlannedStart())
                                .plannedEndSnapshot(project.getPlannedEnd())
                                .statusAtSnapshot(project.getStatus() != null ? project.getStatus().name() : "ACTIVE")
                                .snapshottedAt(LocalDateTime.now())
                                .snapshottedBy(createdBy)
                                .build());
            }
            engagement.setProjectInstanceId(projInst.getId());
            engagementRepository.save(engagement);
            log.info("[AUDIT] Project instance linked | projectInstanceId={}", projInst.getId());
        }

        if (req.getTemplateId() != null) {
            com.kashi.grc.common.kafka.KafkaEventPublisher publisher = kafkaEventPublisherProvider.getIfAvailable();
            if (publisher != null) {
                // Async path: return fast, snapshot + workflow start happen in
                // AuditEngagementSnapshotConsumer. engagement.snapshotStatus stays
                // PROVISIONING (set at build time above via .snapshotStatus(...))
                // until the consumer flips it to READY/FAILED. Same "flip a flag,
                // zero blast radius" contract as every other Kafka producer call —
                // publisher==null (Kafka disabled) falls through to the synchronous
                // branch below unchanged.
                publisher.publish(
                        com.kashi.grc.common.kafka.KafkaTopics.AUDIT_ENGAGEMENT_SNAPSHOT_REQUESTED,
                        "AUDIT_ENGAGEMENT_SNAPSHOT_REQUESTED",
                        String.valueOf(engagement.getId()),
                        Map.of(
                                "engagementId", engagement.getId(),
                                "templateId", req.getTemplateId(),
                                "createdBy", createdBy,
                                "startWorkflow", startWorkflow,
                                "workflowId", req.getWorkflowId() != null ? req.getWorkflowId() : -1L),
                        tenantId, createdBy);
                log.info("[AUDIT] Template snapshot dispatched via Kafka | engagementId={} | templateId={}",
                        engagement.getId(), req.getTemplateId());
            } else {
                completeEngagementProvisioning(engagement, req.getTemplateId(), tenantId,
                        startWorkflow, req.getWorkflowId(), createdBy);
            }
        } else {
            // No template supplied — nothing to snapshot (validation normally
            // requires templateId, but this branch preserves the original
            // unconditional behavior in case create() is ever called directly
            // with a request that bypassed bean validation).
            if (startWorkflow) {
                startWorkflowIfConfigured(engagement, req.getWorkflowId(), createdBy, tenantId);
            } else {
                log.info("[AUDIT] Skipping per-engagement workflow start (project-governed) | engagementId={}", engagement.getId());
            }
        }

        // The lead auditor was already told. The other two named people were not,
        // so an engagement could be created naming an owner and a lead auditee who
        // never found out they were expected to do anything — and step 2 sits
        // waiting on one of them.
        if (req.getLeadAuditorId() != null) {
            notificationService.send(req.getLeadAuditorId(), "AUDIT_ENGAGEMENT_ASSIGNED",
                    "Audit engagement " + ref + " has been assigned to you as lead auditor",
                    "AUDIT_ENGAGEMENT", engagement.getId());
        }
        if (req.getLeadAuditeeId() != null
                && !req.getLeadAuditeeId().equals(req.getLeadAuditorId())) {
            notificationService.send(req.getLeadAuditeeId(), "AUDIT_ENGAGEMENT_LEAD_AUDITEE_ASSIGNED",
                    "You are the evidence lead for audit engagement " + ref
                            + ". Assign control owners in your organization to begin evidence collection.",
                    "AUDIT_ENGAGEMENT", engagement.getId());
        }
        if (req.getOwnerId() != null
                && !req.getOwnerId().equals(req.getLeadAuditorId())
                && !req.getOwnerId().equals(req.getLeadAuditeeId())) {
            notificationService.send(req.getOwnerId(), "AUDIT_ENGAGEMENT_OWNER_ASSIGNED",
                    "Audit engagement " + ref + " has been created under your ownership",
                    "AUDIT_ENGAGEMENT", engagement.getId());
        }

        return toResponse(engagement);
    }

    /**
     * Tells a lead auditee named AFTER creation (overview inline edit) what
     * create() tells one named up front. Best-effort: a failed notification
     * must not undo the assignment.
     */
    public void notifyLeadAuditeeAssigned(AuditEngagement engagement) {
        if (engagement == null || engagement.getLeadAuditeeId() == null) return;
        try {
            notificationService.send(engagement.getLeadAuditeeId(), "AUDIT_ENGAGEMENT_LEAD_AUDITEE_ASSIGNED",
                    "You are the evidence lead for audit engagement " + engagement.getEngagementRef()
                            + ". Assign control owners in your organization to begin evidence collection.",
                    "AUDIT_ENGAGEMENT", engagement.getId());
        } catch (Exception ex) {
            log.warn("[AUDIT] Lead auditee notification failed | engagementId={} — {}",
                    engagement.getId(), ex.getMessage());
        }
    }

    /**
     * Does the actual template-snapshot + optional-workflow-start work, and
     * updates snapshotStatus accordingly. Called from two places:
     *   - create()'s synchronous fallback (Kafka disabled) — runs inline,
     *     same request thread, same as before this async pattern existed.
     *   - AuditEngagementSnapshotConsumer — runs on a Kafka listener thread,
     *     after create() already returned a PROVISIONING engagement to the caller.
     *
     * Public (not private) specifically so the consumer, a different class,
     * can call it — kept in this service rather than duplicated in the
     * consumer so there is exactly one place that knows how to provision an
     * engagement's snapshot.
     *
     * NOT itself @Transactional — snapshotTemplate() carries its own
     * transaction boundary (correctly: a failed snapshot must roll back ALL
     * of its section/control inserts, not leave a half-built tree). Marking
     * this method @Transactional too would have put the FAILED status update
     * below inside that SAME transaction — meaning if snapshotTemplate threw,
     * the "FAILED" write would roll back right along with it, and the
     * engagement would silently stay at PROVISIONING forever with no signal
     * anything went wrong. markSnapshotFailed() below runs in its own fresh
     * transaction specifically so it survives the failure it's recording.
     */
    public void completeEngagementProvisioning(AuditEngagement engagement, Long templateId, Long tenantId,
                                               boolean startWorkflow, Long overrideWorkflowId, Long createdBy) {
        try {
            self.snapshotTemplate(engagement, templateId, tenantId);
            if (startWorkflow) {
                startWorkflowIfConfigured(engagement, overrideWorkflowId, createdBy, tenantId);
            } else {
                log.info("[AUDIT] Skipping per-engagement workflow start (project-governed) | engagementId={}",
                        engagement.getId());
            }
            engagement.setSnapshotStatus("READY");
            engagementRepository.save(engagement);
        } catch (BusinessException e) {
            // ResourceNotFoundException extends BusinessException — catching
            // the parent alone already covers both; a multi-catch listing
            // both is invalid Java (class + its own subclass together).
            // Non-retryable — bad/missing template data. Mark FAILED (own
            // transaction, see javadoc) so the engagement doesn't sit at
            // PROVISIONING forever with no signal; rethrow so the caller
            // (consumer) can decide retry/DLT policy — the synchronous
            // fallback path just lets it propagate as before.
            self.markSnapshotFailed(engagement.getId());
            throw e;
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markSnapshotFailed(Long engagementId) {
        engagementRepository.findById(engagementId).ifPresent(e -> {
            e.setSnapshotStatus("FAILED");
            engagementRepository.save(e);
        });
    }

    // ── TEMPLATE SNAPSHOT (recursive) ────────────────────────────────────────

    @Transactional
    public void snapshotTemplate(AuditEngagement engagement, Long templateId, Long tenantId) {
        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        AuditEngagementTemplateInstance tmplInstance = templateInstanceRepository.save(
                AuditEngagementTemplateInstance.builder()
                        .engagementId(engagement.getId())
                        .tenantId(tenantId)
                        .originalTemplateId(templateId)
                        .templateNameSnapshot(template.getName())
                        .templateVersionSnapshot(template.getVersion())
                        .frameworkRefSnapshot(template.getFrameworkRef())
                        .snapshottedAt(LocalDateTime.now())
                        .build()
        );

        List<AuditTemplateSectionMapping> rootMappings =
                templateSectionMappingRepository.findByTemplateIdOrderByOrderNoAsc(templateId);

        // BATCHED — was one sectionRepository.findById() per root mapping.
        // Root section count is usually small (top-level category nodes),
        // but no reason to leave even that as N+1 when findAllById is free.
        List<Long> rootSectionIds = rootMappings.stream()
                .map(AuditTemplateSectionMapping::getSectionId).toList();
        Map<Long, AuditSection> rootSectionMap = sectionRepository.findAllById(rootSectionIds)
                .stream().collect(java.util.stream.Collectors.toMap(AuditSection::getId, s -> s));
        List<AuditSection> rootSections = rootMappings.stream()
                .map(m -> rootSectionMap.get(m.getSectionId()))
                .filter(Objects::nonNull)
                .toList();

        // See AuditSectionService.snapshotSectionTree javadoc for what changed
        // here — was per-node recursion (2 saves + 1 query per section, plus a
        // per-section saveAll() for controls that didn't actually batch at the
        // JDBC level), now BFS-batched level-by-level with real JDBC batch inserts.
        sectionService.snapshotSectionTree(rootSections, engagement.getId(), tmplInstance.getId(), tenantId);

        int totalControls = (int) controlInstanceRepository.countByEngagementId(engagement.getId());
        if (totalControls == 0) {
            throw new BusinessException("EMPTY_TEMPLATE",
                    "Template '" + template.getName() + "' has no sections/controls to snapshot — "
                            + "fix the template in the Audit Library before using it for an engagement");
        }
        engagement.setTotalControls(totalControls);
        engagementRepository.save(engagement);

        // Snapshot tests and policies — full isolation from library changes
        Long createdBy = engagement.getCreatedBy() != null ? engagement.getCreatedBy() : tenantId;
        testPolicySnapshotService.snapshotTestsAndPolicies(
                engagement.getId(), tenantId, createdBy);

        log.info("[AUDIT] Template snapshotted | engagementId={} | rootSections={} | totalControls={}",
                engagement.getId(), rootMappings.size(), totalControls);
    }

    // ── SECTION ASSIGNMENT ────────────────────────────────────────────────────

    @Transactional
    public void assignSection(Long engagementId, Long sectionInstanceId,
                              Long auditorId, boolean cascadeToChildren, Long tenantId) {
        assignSection(engagementId, sectionInstanceId, auditorId, cascadeToChildren, tenantId, null);
    }

    public void assignSection(Long engagementId, Long sectionInstanceId,
                              Long auditorId, boolean cascadeToChildren, Long tenantId, Long performedBy) {
        assignSectionInternal(engagementId, sectionInstanceId, auditorId, cascadeToChildren,
                tenantId, performedBy, true, null);
    }

    /**
     * Same as the public assignSection(), plus a checkOnboardedGate toggle.
     *
     * WHY THIS SPLIT EXISTS: the ENGAGEMENTS_ONBOARDED completeness check
     * (see checkAndFireEngagementsOnboardedGate below) re-fetches EVERY
     * section in the engagement and re-evaluates "is everything assigned
     * yet" on every single call. For a single-section assign that's fine —
     * it's exactly the check that decides whether to fire the gate. But
     * bulkAssignSections calls assignSection once per section in the
     * request, which meant this same engagement-wide fetch ran N times for
     * one bulk request, and — because "all assigned" can flip true on an
     * early section (if other sections were already assigned before this
     * bulk call) and then stays true — the completion event could actually
     * FIRE MULTIPLE TIMES within one bulk call, not just once. Checking
     * once after the whole bulk loop gives the identical true/false result
     * (it's a pure function of final DB state, monotonically only becoming
     * true as more sections get assigned within one bulk request) while
     * fixing that duplicate-fire risk as a side effect.
     */
    private void assignSectionInternal(Long engagementId, Long sectionInstanceId,
                                       Long auditorId, boolean cascadeToChildren, Long tenantId, Long performedBy,
                                       boolean checkOnboardedGate, SectionAssignmentTarget preResolvedTarget) {
        AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSectionInstance", sectionInstanceId));

        if (!section.getEngagementId().equals(engagementId))
            throw new BusinessException("SECTION_MISMATCH", "Section does not belong to this engagement");

        Long previousSectionAuditor = section.getAssignedAuditorId();
        List<Long> affectedSectionIds = new ArrayList<>(List.of(sectionInstanceId));
        section.setAssignedAuditorId(auditorId);
        sectionInstanceRepository.save(section);

        if (cascadeToChildren) {
            // Cascade auditor assignment down to all descendant SECTIONS only.
            // Controls are NOT assigned here — the section's assigned auditor
            // will explicitly assign themselves (or another auditor) to specific
            // controls via assignAuditorToControl(). This allows:
            //   - Lead auditor assigns Section A to Rohit
            //   - Rohit assigns CC6.1, CC6.2 to himself
            //   - Rohit assigns CC6.3 to Kavya (sub-specialist)
            // Controls that are never explicitly assigned fall back to the
            // section's assignedAuditorId in AuditWorkflowActorResolver.
            // NOTE: saving children directly here — NOT calling assignSection() recursively
            // to avoid firing the workflow gate event once per child, which would
            // complete the gate prematurely on the first child and advance the workflow
            // before all sections are actually assigned.
            List<AuditSectionInstance> descendants =
                    sectionInstanceRepository.findAllDescendants(sectionInstanceId, section.getPath());
            for (AuditSectionInstance child : descendants) {
                child.setAssignedAuditorId(auditorId);
                sectionInstanceRepository.save(child);
                affectedSectionIds.add(child.getId());
            }
        }

        // Controls with no auditor of their own inherit this section's owner, so
        // testing delegations on them lapse with the old owner — same rule as a
        // control reassignment (AuditObligationService.closeOnSectionReassignment).
        if (!Objects.equals(previousSectionAuditor, auditorId)) {
            obligationService.closeOnSectionReassignment(engagementId, affectedSectionIds, false,
                    auditorId, performedBy, tenantId);
        }

        if (auditorId != null) {
            notificationService.send(auditorId, "AUDIT_SECTION_ASSIGNED",
                    "Audit section '" + section.getSectionNameSnapshot() + "' assigned to you",
                    "AUDIT_SECTION_INSTANCE", sectionInstanceId);
        }

        // Also set auditor on the root ancestor (depth=0) if cascading, so no section
        // is left unassigned. The cascade goes downward only — without this the root
        // category node itself would have assignedAuditorId=null.
        //
        // Root id is read directly from the materialized path ("/rootId/.../thisId/")
        // instead of walking parentInstanceId one level at a time — that walk was
        // one findById() PER TREE LEVEL (up to depth queries) for data the path
        // column already encodes in one string, no query needed to get there.
        if (cascadeToChildren && auditorId != null) {
            AuditSectionInstance root = section.getParentInstanceId() == null
                    ? section
                    : sectionInstanceRepository.findById(rootIdFromPath(section.getPath())).orElse(null);
            if (root != null && root.getAssignedAuditorId() == null) {
                root.setAssignedAuditorId(auditorId);
                sectionInstanceRepository.save(root);
            }
        }

        // Fire the section-level compound-task item event — was previously dead
        // code (fireSectionAssignmentEvent existed but nothing called it). This
        // handles both standalone (WF14) and project-governed (WF16) engagements
        // internally by resolving the correct workflow instance.
        if (auditorId != null && performedBy != null) {
            if (preResolvedTarget != null) {
                fireSectionAssignmentEventWithTarget(preResolvedTarget, sectionInstanceId, performedBy,
                        "SECTIONS_ASSIGNED_AUDITOR");
            } else {
                fireSectionAssignmentEvent("SECTIONS_ASSIGNED_AUDITOR", sectionInstanceId, engagementId, performedBy);
            }
        } else if (auditorId == null && performedBy != null) {
            uncompleteSectionAssignmentEvent("SECTIONS_ASSIGNED_AUDITOR", sectionInstanceId, engagementId);
        }

        // Fire ENGAGEMENTS_ONBOARDED once ALL sections of this engagement
        // (every depth) have an auditor assigned — no section left unassigned.
        // When unassigning (auditorId=null), reset the engagement item so the gate re-opens.
        if (checkOnboardedGate) {
            if (auditorId != null && performedBy != null) {
                checkAndFireEngagementsOnboardedGate(engagementId, performedBy);
            } else if (auditorId == null && performedBy != null) {
                resetEngagementsOnboardedGate(engagementId);
            }
        }

        log.info("[AUDIT] Section assigned | sectionInstanceId={} | auditorId={} | cascade={}",
                sectionInstanceId, auditorId, cascadeToChildren);
    }

    /**
     * Extracts the root section instance id from a materialized path like
     * "/12/45/78/" (returns 12L). Path format is fixed by
     * AuditSectionService.snapshotSectionTree — always "/" + id + "/" for
     * root, parentPath + id + "/" for children, so the first segment after
     * the leading slash is always the root id.
     */
    private Long rootIdFromPath(String path) {
        String[] parts = path.split("/");
        // parts[0] is "" (text before the leading slash); parts[1] is the root id.
        return Long.parseLong(parts[1]);
    }

    private void checkAndFireEngagementsOnboardedGate(Long engagementId, Long performedBy) {
        // COUNT instead of fetch-all-and-stream — this runs on every single
        // section assignment (not just bulk), so pulling every full
        // AuditSectionInstance row across the wire just to check one boolean
        // was real, avoidable cost on the individual assign endpoint too.
        long total      = sectionInstanceRepository.countByEngagementId(engagementId);
        long unassigned = sectionInstanceRepository.countByEngagementIdAndAssignedAuditorIdIsNull(engagementId);
        boolean allAssigned = total > 0 && unassigned == 0;
        if (allAssigned) {
            AuditEngagement eng = engagementRepository.findById(engagementId).orElse(null);
            if (eng != null && eng.getProjectInstanceId() != null) {
                fireProjectSectionEvent(eng.getProjectInstanceId(), "ENGAGEMENTS_ONBOARDED",
                        engagementId, performedBy);
            }
        }
    }

    private void resetEngagementsOnboardedGate(Long engagementId) {
        AuditEngagement eng = engagementRepository.findById(engagementId).orElse(null);
        if (eng != null && eng.getProjectInstanceId() != null) {
            uncompleteEngagementItem(eng.getProjectInstanceId(), "ENGAGEMENTS_ONBOARDED", engagementId);
        }
    }

    // ── AUDITEE SECTION ASSIGNMENT ────────────────────────────────────────────

    /**
     * Assigns an auditee user as the evidence owner for a section and its entire subtree.
     *
     * Cascades to all descendant section nodes (all depths) and to all control instances
     * whose sectionPath falls within this section's path prefix — exactly mirroring
     * the auditor cascade in assignSection().
     *
     * Fired by Step 3 (Assign Evidence Owners) in the SOC 2 workflow blueprint.
     * The compound section gate SECTIONS_ASSIGNED_AUDITEE is advanced when the lead
     * auditor calls this endpoint for each section item registered by AuditSectionItemRegistrar.
     *
     * @param engagementId      Parent engagement — validates section ownership
     * @param sectionInstanceId The section node being assigned (typically depth=0)
     * @param auditeeUserId     The auditee user who will upload evidence. Null = un-assign.
     * @param cascadeToChildren When true (default), all child sections and controls inherit
     *                          this auditee assignment. Set false only for leaf-level overrides.
     * @param tenantId          Caller's tenant — used for security scoping
     */
    @Transactional
    public void assignAuditeeToSection(Long engagementId, Long sectionInstanceId,
                                       Long auditeeUserId, boolean cascadeToChildren,
                                       Long tenantId) {
        assignAuditeeToSection(engagementId, sectionInstanceId, auditeeUserId, cascadeToChildren, tenantId, null);
    }

    public void assignAuditeeToSection(Long engagementId, Long sectionInstanceId,
                                       Long auditeeUserId, boolean cascadeToChildren,
                                       Long tenantId, Long performedBy) {
        assignAuditeeToSectionInternal(engagementId, sectionInstanceId, auditeeUserId,
                cascadeToChildren, tenantId, performedBy, true, null);
    }

    /** Same as the public assignAuditeeToSection(), plus a checkOnboardedGate
     *  toggle and an optional pre-resolved section-assignment target — see
     *  assignSectionInternal's javadoc for why both exist (identical
     *  reasoning, mirrored for the EVIDENCE_OWNERS_ASSIGNED gate). */
    private void assignAuditeeToSectionInternal(Long engagementId, Long sectionInstanceId,
                                                Long auditeeUserId, boolean cascadeToChildren,
                                                Long tenantId, Long performedBy, boolean checkOnboardedGate,
                                                SectionAssignmentTarget preResolvedTarget) {
        AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSectionInstance", sectionInstanceId));

        if (!section.getEngagementId().equals(engagementId))
            throw new BusinessException("SECTION_MISMATCH", "Section does not belong to this engagement");

        Long previousSectionAuditee = section.getAuditeeAssignedUserId();
        List<Long> affectedSectionIds = new ArrayList<>(List.of(sectionInstanceId));
        section.setAuditeeAssignedUserId(auditeeUserId);
        sectionInstanceRepository.save(section);

        if (cascadeToChildren) {
            // Cascade auditee assignment down to all descendant SECTIONS only.
            // Controls are NOT assigned here — the section's assigned auditee
            // will explicitly assign themselves (or another auditee) to specific
            // controls via assignAuditeeToControl(). Mirrors assignSection()'s
            // auditor-side pattern: section-level assignment establishes who owns
            // the section; control-level assignment is a separate, later, individual
            // act performed by that section owner. This was previously inconsistent —
            // this method cascaded to controls while assignSection() deliberately did
            // not, silently bypassing the intended per-control assignment step on the
            // auditee side only.
            List<AuditSectionInstance> descendants =
                    sectionInstanceRepository.findAllDescendants(sectionInstanceId, section.getPath());
            for (AuditSectionInstance child : descendants) {
                child.setAuditeeAssignedUserId(auditeeUserId);
                sectionInstanceRepository.save(child);
                affectedSectionIds.add(child.getId());
            }
            // NOTE: control cascade deliberately removed — see assignSection() for
            // the matching auditor-side rationale. Controls inherit the section's
            // auditee implicitly via AuditWorkflowActorResolver /
            // AuditProjectWorkflowActorResolver until explicitly overridden by
            // assignAuditeeToControl().
        }

        // Evidence delegations on controls that inherit this section's owner
        // lapse with the old owner — see assignSectionInternal.
        if (!Objects.equals(previousSectionAuditee, auditeeUserId)) {
            obligationService.closeOnSectionReassignment(engagementId, affectedSectionIds, true,
                    auditeeUserId, performedBy, tenantId);
        }

        if (auditeeUserId != null) {
            notificationService.send(auditeeUserId, "AUDIT_SECTION_AUDITEE_ASSIGNED",
                    "Audit section '" + section.getSectionNameSnapshot() + "' assigned to you for evidence",
                    "AUDIT_SECTION_INSTANCE", sectionInstanceId);
        }

        // Also set auditee on the root ancestor if cascading, so no section is left
        // unassigned. Root id read from the materialized path — see assignSectionInternal
        // for why (one query instead of one per tree level).
        if (cascadeToChildren && auditeeUserId != null) {
            AuditSectionInstance root = section.getParentInstanceId() == null
                    ? section
                    : sectionInstanceRepository.findById(rootIdFromPath(section.getPath())).orElse(null);
            if (root != null && root.getAuditeeAssignedUserId() == null) {
                root.setAuditeeAssignedUserId(auditeeUserId);
                sectionInstanceRepository.save(root);
            }
        }

        // Fire the section-level compound-task item event — was previously dead
        // code (fireSectionAssignmentEvent existed but nothing called it). This
        // handles both standalone (WF14) and project-governed (WF16) engagements
        // internally by resolving the correct workflow instance.
        if (auditeeUserId != null && performedBy != null) {
            if (preResolvedTarget != null) {
                fireSectionAssignmentEventWithTarget(preResolvedTarget, sectionInstanceId, performedBy,
                        "SECTIONS_ASSIGNED_AUDITEE");
            } else {
                fireSectionAssignmentEvent("SECTIONS_ASSIGNED_AUDITEE", sectionInstanceId, engagementId, performedBy);
            }
        } else if (auditeeUserId == null && performedBy != null) {
            uncompleteSectionAssignmentEvent("SECTIONS_ASSIGNED_AUDITEE", sectionInstanceId, engagementId);
        }

        // Fire EVIDENCE_OWNERS_ASSIGNED once ALL sections of this engagement
        // have an auditee assigned — no section left unassigned.
        // When unassigning (auditeeUserId=null), reset the engagement item so gate re-opens.
        if (checkOnboardedGate) {
            if (auditeeUserId != null && performedBy != null) {
                checkAndFireEvidenceOwnersAssignedGate(engagementId, performedBy);
            } else if (auditeeUserId == null && performedBy != null) {
                resetEvidenceOwnersAssignedGate(engagementId);
            }
        }

        log.info("[AUDIT] Auditee assigned to section | sectionInstanceId={} | auditeeUserId={} | cascade={}",
                sectionInstanceId, auditeeUserId, cascadeToChildren);
    }

    private void checkAndFireEvidenceOwnersAssignedGate(Long engagementId, Long performedBy) {
        long total      = sectionInstanceRepository.countByEngagementId(engagementId);
        long unassigned = sectionInstanceRepository.countByEngagementIdAndAuditeeAssignedUserIdIsNull(engagementId);
        boolean allAssigned = total > 0 && unassigned == 0;
        if (allAssigned) {
            AuditEngagement eng = engagementRepository.findById(engagementId).orElse(null);
            if (eng != null && eng.getProjectInstanceId() != null) {
                fireProjectSectionEvent(eng.getProjectInstanceId(), "EVIDENCE_OWNERS_ASSIGNED",
                        engagementId, performedBy);
            }
        }
    }

    private void resetEvidenceOwnersAssignedGate(Long engagementId) {
        AuditEngagement eng = engagementRepository.findById(engagementId).orElse(null);
        if (eng != null && eng.getProjectInstanceId() != null) {
            uncompleteEngagementItem(eng.getProjectInstanceId(), "EVIDENCE_OWNERS_ASSIGNED", engagementId);
        }
    }

    // ── AUDITEE CONTROL ASSIGNMENT ────────────────────────────────────────────

    @Transactional
    public void assignAuditeeToControl(Long engagementId, Long controlInstanceId,
                                       Object auditeeUserIdRaw,
                                       java.time.LocalDate evidenceDueDate, Long tenantId) {
        Long auditeeUserId = auditeeUserIdRaw != null
                ? Long.parseLong(auditeeUserIdRaw.toString()) : null;
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", controlInstanceId));
        if (!control.getEngagementId().equals(engagementId))
            throw new BusinessException("CONTROL_MISMATCH", "Control does not belong to this engagement");

        Long previousAuditee = control.getAuditeeAssignedUserId();
        control.setAuditeeAssignedUserId(auditeeUserId);
        if (evidenceDueDate != null) control.setEvidenceDueDate(evidenceDueDate);
        controlInstanceRepository.save(control);

        // A live delegation is an access grant; when the control changes hands
        // on this side, the new owner did not choose those delegates.
        if (!Objects.equals(previousAuditee, auditeeUserId)) {
            obligationService.closeOnReassignment(controlInstanceId, true, auditeeUserId, null, tenantId);
        }

        if (auditeeUserId != null) {
            String dueDateStr = evidenceDueDate != null ? " (due " + evidenceDueDate + ")" : "";
            notificationService.send(auditeeUserId, "AUDIT_EVIDENCE_REQUESTED",
                    "Evidence requested for audit control: " + control.getControlNameSnapshot() + dueDateStr,
                    "AUDIT_CONTROL_INSTANCE", controlInstanceId);
        }
    }

    /** Backward-compat overload — no due date */
    public void assignAuditeeToControl(Long engagementId, Long controlInstanceId,
                                       Object auditeeUserIdRaw, Long tenantId) {
        assignAuditeeToControl(engagementId, controlInstanceId, auditeeUserIdRaw, null, tenantId);
    }

    public void assignAuditorToControl(Long engagementId, Long controlInstanceId,
                                       Long auditorId, Long tenantId) {
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance",
                        controlInstanceId));

        if (!control.getEngagementId().equals(engagementId))
            throw new BusinessException("CONTROL_MISMATCH",
                    "Control does not belong to this engagement");

        Long previousAuditor = control.getAssignedAuditorId();
        control.setAssignedAuditorId(auditorId);
        controlInstanceRepository.save(control);

        if (!Objects.equals(previousAuditor, auditorId)) {
            obligationService.closeOnReassignment(controlInstanceId, false, auditorId, null, tenantId);
        }

        if (auditorId != null) {
            fireControlSectionEvent("CONTROLS_ASSIGNED", controlInstanceId, engagementId, auditorId);
        } else if (previousAuditor != null) {
            // Unassign: WAS fireControlSectionEvent(..., null), which NPE'd inside
            // its own try/catch — a warning in the log and the control's
            // CONTROLS_ASSIGNED item left complete on a control nobody owns.
            uncompleteControlItem("CONTROLS_ASSIGNED", controlInstanceId, engagementId);
        }

        log.info("[AUDIT-ENG-SERVICE] Auditor assigned | controlInstanceId={} | auditorId={}",
                controlInstanceId, auditorId);
    }

    /**
     * Bulk-assigns auditor and/or auditee to multiple controls in a single call.
     *
     * Source: either an explicit list of controlIds, or all controls under a
     * sectionInstanceId (and its descendants, via their sectionInstanceId FK).
     * If both are provided, controlIds takes precedence.
     *
     * Used when a section owner has 50-100 controls and wants to delegate them
     * without N individual PUT calls. All controls are validated to belong to
     * the engagement before assignment. Events are fired per-control as normal.
     *
     * @return count of controls actually updated
     */
    @Transactional
    public int bulkAssignControls(Long engagementId,
                                  BulkControlAssignRequest req,
                                  Long actorId, Long tenantId) {
        // Resolve the target controls
        List<AuditControlInstance> controls;
        if (req.getControlIds() != null && !req.getControlIds().isEmpty()) {
            controls = controlInstanceRepository.findAllById(req.getControlIds());
        } else if (req.getSectionInstanceId() != null) {
            controls = controlInstanceRepository
                    .findBySectionInstanceIdOrderByOrderNoAsc(req.getSectionInstanceId());
        } else {
            throw new BusinessException("MISSING_TARGET",
                    "Either controlIds or sectionInstanceId must be provided");
        }

        // Safety: all must belong to this engagement
        List<AuditControlInstance> owned = controls.stream()
                .filter(c -> c.getEngagementId().equals(engagementId))
                .toList();
        if (owned.size() < controls.size()) {
            log.warn("[AUDIT-ENG-SERVICE] bulkAssign: {} control(s) skipped — wrong engagement",
                    controls.size() - owned.size());
        }

        boolean clearAuditor = Boolean.TRUE.equals(req.getUnassignAuditor());
        boolean clearAuditee = Boolean.TRUE.equals(req.getUnassignAuditee());
        if ((clearAuditor && req.getAuditorUserId() != null)
                || (clearAuditee && req.getAuditeeUserId() != null)) {
            throw new BusinessException("CONFLICTING_ASSIGNMENT",
                    "Choose either a user to assign or unassign for each side, not both.");
        }

        int updated = 0;
        // Resolved ONCE — see resolveActorTaskInstanceId javadoc for why this,
        // not the control.save() calls, was the actual N+1 here. engagementId
        // and actorId are the same for every control in this request, so the
        // lookup result is identical on every iteration.
        Long resolvedTaskInstanceId = req.getAuditorUserId() != null
                ? resolveActorTaskInstanceId(engagementId, actorId, "CONTROLS_ASSIGNED")
                : null;

        for (AuditControlInstance ctrl : owned) {
            boolean changed = false;
            Long previousAuditor = ctrl.getAssignedAuditorId();
            Long previousAuditee = ctrl.getAuditeeAssignedUserId();
            if (req.getAuditorUserId() != null) {
                ctrl.setAssignedAuditorId(req.getAuditorUserId());
                changed = true;
            }
            if (req.getAuditeeUserId() != null) {
                ctrl.setAuditeeAssignedUserId(req.getAuditeeUserId());
                if (req.getEvidenceDueDate() != null)
                    ctrl.setEvidenceDueDate(req.getEvidenceDueDate());
                changed = true;
            }
            if (clearAuditor && previousAuditor != null) {
                ctrl.setAssignedAuditorId(null);
                changed = true;
            }
            if (clearAuditee && previousAuditee != null) {
                ctrl.setAuditeeAssignedUserId(null);
                changed = true;
            }
            if (changed) {
                controlInstanceRepository.save(ctrl);
                // Same cleanup as the single-control paths: delegations on a side
                // whose owner just changed stop granting access.
                if (req.getAuditorUserId() != null
                        && !Objects.equals(previousAuditor, req.getAuditorUserId())) {
                    obligationService.closeOnReassignment(ctrl.getId(), false,
                            req.getAuditorUserId(), actorId, tenantId);
                }
                if (req.getAuditeeUserId() != null
                        && !Objects.equals(previousAuditee, req.getAuditeeUserId())) {
                    obligationService.closeOnReassignment(ctrl.getId(), true,
                            req.getAuditeeUserId(), actorId, tenantId);
                }
                if (req.getAuditorUserId() != null) {
                    fireControlSectionEventWithResolvedTask("CONTROLS_ASSIGNED", ctrl.getId(),
                            resolvedTaskInstanceId, actorId);
                }
                // Unassign: delegations on that side lapse (nobody chose them for
                // the next owner) and the control's assignment item re-opens.
                if (clearAuditor && previousAuditor != null) {
                    obligationService.closeOnReassignment(ctrl.getId(), false, null, actorId, tenantId);
                    uncompleteControlItem("CONTROLS_ASSIGNED", ctrl.getId(), engagementId);
                }
                if (clearAuditee && previousAuditee != null) {
                    obligationService.closeOnReassignment(ctrl.getId(), true, null, actorId, tenantId);
                }
                updated++;
            }
        }

        log.info("[AUDIT-ENG-SERVICE] Bulk assign | engagementId={} | updated={}/{} | " +
                        "auditorId={} | auditeeId={}",
                engagementId, updated, owned.size(),
                req.getAuditorUserId(), req.getAuditeeUserId());
        return updated;
    }

    /**
     * Bulk-assign auditor and/or auditee across multiple SECTIONS in one call.
     * Each section reuses the existing per-section logic (assignSection /
     * assignAuditeeToSection), so cascade-to-children behaves identically to a
     * single-section assignment.
     */
    @Transactional
    public int bulkAssignSections(Long engagementId,
                                  BulkSectionAssignRequest req,
                                  Long actorId, Long tenantId) {
        if (req.getSectionIds() == null || req.getSectionIds().isEmpty()) {
            throw new BusinessException("MISSING_TARGET", "sectionIds must be provided");
        }
        boolean cascade = req.getCascadeToChildren() == null || req.getCascadeToChildren();

        // Resolved ONCE per bulk request — see resolveSectionAssignmentTarget
        // javadoc for why (same engagementId/actorId/completionEvent for
        // every section in this request means the same target every time).
        SectionAssignmentTarget auditorTarget = req.getAuditorUserId() != null
                ? resolveSectionAssignmentTarget("SECTIONS_ASSIGNED_AUDITOR", engagementId, actorId)
                : null;
        SectionAssignmentTarget auditeeTarget = req.getAuditeeUserId() != null
                ? resolveSectionAssignmentTarget("SECTIONS_ASSIGNED_AUDITEE", engagementId, actorId)
                : null;

        int updated = 0;
        for (Long sectionId : req.getSectionIds()) {
            // Safety: section must belong to this engagement (assignSection checks tenant;
            // the per-section methods throw if the section isn't found under the engagement).
            boolean changed = false;
            if (req.getAuditorUserId() != null) {
                // checkOnboardedGate=false — see assignSectionInternal javadoc.
                // The completeness check runs ONCE below, after the whole loop,
                // instead of once per section.
                assignSectionInternal(engagementId, sectionId, req.getAuditorUserId(), cascade,
                        tenantId, actorId, false, auditorTarget);
                changed = true;
            }
            if (req.getAuditeeUserId() != null) {
                assignAuditeeToSectionInternal(engagementId, sectionId, req.getAuditeeUserId(), cascade,
                        tenantId, actorId, false, auditeeTarget);
                changed = true;
            }
            if (changed) updated++;
        }

        // Run each completeness check ONCE for the whole bulk request instead
        // of once per section — same final result (both checks are pure
        // functions of final DB state), fewer engagement-wide re-fetches, and
        // no risk of the gate event firing more than once within one request.
        if (req.getAuditorUserId() != null && actorId != null) {
            checkAndFireEngagementsOnboardedGate(engagementId, actorId);
        }
        if (req.getAuditeeUserId() != null && actorId != null) {
            checkAndFireEvidenceOwnersAssignedGate(engagementId, actorId);
        }

        log.info("[AUDIT-ENG-SERVICE] Bulk section assign | engagementId={} | updated={}/{} | " +
                        "auditorId={} | auditeeId={} | cascade={}",
                engagementId, updated, req.getSectionIds().size(),
                req.getAuditorUserId(), req.getAuditeeUserId(), cascade);
        return updated;
    }

    /**
     * Sends a control back to the auditee for additional evidence.
     * Called by section auditors during Evidence Review (Step 7) when uploaded
     * evidence is insufficient or incorrect.
     *
     * Resets auditeeEvidenceSubmitted=false so the auditee can re-upload
     * and re-submit. Sends a notification to the assigned auditee.
     */
    @Transactional
    public void sendBackControlEvidence(Long engagementId, Long controlInstanceId,
                                        String reason, Long sentBackBy, Long tenantId) {
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", controlInstanceId));

        if (!control.getEngagementId().equals(engagementId))
            throw new BusinessException("CONTROL_MISMATCH", "Control does not belong to this engagement");

        // Reset submission flag so auditee can re-upload
        control.setAuditeeEvidenceSubmitted(false);
        control.setAuditeeEvidenceSubmittedAt(null);
        // A control submitted without evidence can be sent back too — it is open again.
        control.setEvidenceGapReason(null);
        control.setEvidenceGapAt(null);
        control.setEvidenceGapBy(null);
        controlInstanceRepository.save(control);

        // The section (and the clauses above it) are no longer complete, and the
        // control's EVIDENCE_UPLOADED checklist item is open again — otherwise
        // the evidence step could close on a control that is waiting for evidence.
        reopenEvidenceSectionChain(control.getSectionInstanceId(), sentBackBy);
        uncompleteControlItem("EVIDENCE_UPLOADED", controlInstanceId, engagementId);

        // Notify the assigned auditee
        Long auditeeId = control.getAuditeeAssignedUserId();
        if (auditeeId == null) {
            // Fall back to section-level auditee
            AuditSectionInstance section = control.getSectionInstanceId() != null
                    ? sectionInstanceRepository.findById(control.getSectionInstanceId()).orElse(null)
                    : null;
            if (section != null) auditeeId = section.getAuditeeAssignedUserId();
        }
        if (auditeeId != null) {
            String msg = "Evidence for control '" + control.getControlNameSnapshot() + "' was sent back for revision"
                    + (reason != null && !reason.isBlank() ? ": " + reason : ". Please re-upload and resubmit.");
            notificationService.send(auditeeId, "AUDIT_EVIDENCE_SENT_BACK", msg,
                    "AUDIT_CONTROL_INSTANCE", controlInstanceId);
        }

        // A notification is read once and gone. The send-back is work, so it also
        // becomes a CONTROL_REOPEN item in the same person's inbox (mirrors the
        // vendor side's CONTRIBUTOR_REOPEN) and closes when evidence is resubmitted.
        obligationService.raiseControlReopen(control, reason, sentBackBy, tenantId);

        log.info("[AUDIT-ENG-SERVICE] Control sent back for evidence | controlInstanceId={} | by={} | reason={}",
                controlInstanceId, sentBackBy, reason);
    }

    public void submitControlEvidence(Long engagementId, Long controlInstanceId,
                                      Long submittedBy, Long tenantId) {
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance",
                        controlInstanceId));

        if (!control.getEngagementId().equals(engagementId))
            throw new BusinessException("CONTROL_MISMATCH",
                    "Control does not belong to this engagement");

        // Section ownership check — submitter must be the assigned auditee of this control's
        // parent section (or explicitly assigned to this control). Prevents Vikram from
        // submitting evidence for controls in Anita's sections.
        //
        // Was an inline three-tier check of its own, NOT the same as the guard the
        // instance endpoint uses: it let the LEAD AUDITOR submit the auditee's
        // evidence, ignored delegations, and looked at the direct section only.
        // One rule now — ControlAccessGuard — so the two submit paths cannot
        // disagree. NOT_EVIDENCE_OWNER is retired: no client reads it (checked),
        // and the guard's CONTROL_NOT_ASSIGNED is the code every other control
        // endpoint already returns.
        controlAccessGuard.requireCanSubmitEvidence(control, submittedBy);

        // Require at least one uploaded document OR live linked evidence — see hasSubmittableEvidence.
        if (!hasSubmittableEvidence(control)) {
            throw new BusinessException("NO_EVIDENCE",
                    "Please upload at least one evidence file before submitting.");
        }

        control.setAuditeeEvidenceSubmitted(true);
        control.setAuditeeEvidenceSubmittedAt(LocalDateTime.now());
        // Evidence arrived after all — the earlier "submitted without evidence" no longer applies.
        control.setEvidenceGapReason(null);
        control.setEvidenceGapAt(null);
        control.setEvidenceGapBy(null);
        controlInstanceRepository.save(control);

        // Doing the work closes the submitter's own evidence delegation and any
        // send-back reopen on this control — nothing else attached to it.
        obligationService.onEvidenceSubmitted(controlInstanceId, submittedBy, tenantId);

        // Auto-submit parent section when all its controls have evidence submitted.
        // Auditees don't manually submit sections — it happens automatically when
        // they finish uploading evidence for all controls in their section.
        autoSubmitSectionIfComplete(control.getSectionInstanceId(), engagementId, submittedBy);

        // Fire section event for engagement-level workflow (WF14 SOC2 Type II)
        fireControlSectionEvent("EVIDENCE_UPLOADED", controlInstanceId, engagementId, submittedBy);

        notifyEvidenceSubmitted(control, submittedBy, null);

        // Note: Step 5 (Evidence Submission) advances via manual APPROVE by lead auditor,
        // not by auto-gate. This allows partial evidence submission — auditors can
        // proceed to review even if not all 41 controls have evidence yet.

        log.info("[AUDIT-ENG-SERVICE] Evidence submitted | controlInstanceId={} | by={}",
                controlInstanceId, submittedBy);
    }

    /**
     * What submitControlEvidence accepts as evidence: an uploaded document, or a
     * live linked one. Shared with AuditSectionSubmissionService so "ready to
     * submit" in the section dialog means exactly what the submit will accept.
     */
    public boolean hasSubmittableEvidence(AuditControlInstance control) {
        Long controlInstanceId = control.getId();
        boolean hasManualDocs = !documentLinkRepository
                .findAllActiveByEntity("AUDIT_CONTROL_INSTANCE", controlInstanceId).isEmpty();
        // Linked evidence counts too — pulled from integrations (KashiLink),
        // reused from another engagement or auto-tagged — as long as the link is
        // live: awaiting review, accepted or automation-verified. It used to need
        // ACCEPTED, but pulled links arrive PENDING_REVIEW and the auditor reviews
        // AFTER the auditee submits, so a control whose only evidence was pulled
        // could never be submitted (and so its section never auto-submitted).
        // REJECTED and EXPIRED links still do not count.
        boolean hasAutomatedEvidence = evidenceLinkRepository
                .findByTargetEntityTypeAndTargetEntityIdAndTenantId(
                        "AUDIT_CONTROL_INSTANCE", controlInstanceId, control.getTenantId())
                .stream()
                .anyMatch(l -> l.getStatus() == com.kashi.grc.evidence.domain.EvidenceLink.Status.PENDING_REVIEW
                        || l.getStatus() == com.kashi.grc.evidence.domain.EvidenceLink.Status.ACCEPTED
                        || l.getStatus() == com.kashi.grc.evidence.domain.EvidenceLink.Status.AUTOMATION_VERIFIED);
        return hasManualDocs || hasAutomatedEvidence;
    }

    // ── BULK SUBMIT OF LINKED EVIDENCE ────────────────────────────────────────

    /**
     * Permission for submitting, in one go, controls whose evidence is LINKED
     * (pulled from an integration, reused, auto-tagged) but not yet submitted.
     */
    public static final String BULK_SUBMIT_PERMISSION = "audit:evidence:bulk-submit";

    /**
     * Submits every control (of {@code controlIds}, or of the whole engagement
     * when none are given) that has LIVE linked evidence and is not submitted
     * yet — for the case where the auditee side is confident the linked
     * evidence will do, and clicking Submit on each control would be busywork.
     * Freshly uploaded evidence is submitted by its owner one control at a
     * time, as before; this only touches controls that HAVE linked evidence.
     *
     * Who:
     *   • must hold audit:evidence:bulk-submit, and per control be
     *   • the engagement's lead auditee            → any control, or
     *   • the auditee owner of the control's section or a section above it
     *                                              → controls under that section, or
     *   • someone ControlAccessGuard already lets submit that control
     *     (its assignee, a delegate, an override holder).
     * Controls outside that scope are skipped and reported, not refused as a
     * whole — so a section owner can press one button on a mixed list.
     *
     * Each submission has the same effects as the single Submit: the flag and
     * timestamp, the evidence delegation / send-back closed (for the person
     * pressing the button AND the control's evidence owner, since the owner's
     * work is now done), section auto-submit with roll-up, and the
     * EVIDENCE_UPLOADED item ticked on the submitter's task and on the owner's.
     *
     * Linked evidence still has to pass the auditor's review: submitting only
     * hands it over, exactly as a single Submit does.
     *
     * @return { submitted: n, submittedControlIds: [...], skipped: [{controlId, reason}] }
     */
    @Transactional
    public Map<String, Object> bulkSubmitLinkedEvidence(Long engagementId, List<Long> controlIds,
                                                        Long submittedBy, Long tenantId) {
        if (!controlAccessGuard.callerHolds(BULK_SUBMIT_PERMISSION)) {
            throw new BusinessException("BULK_SUBMIT_DENIED",
                    "You do not have permission to submit linked evidence in bulk ("
                            + BULK_SUBMIT_PERMISSION + ")",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        AuditEngagement engagement = engagementRepository.findById(engagementId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", engagementId));

        List<AuditControlInstance> controls = controlInstanceRepository.findByEngagementId(engagementId);
        boolean explicit = controlIds != null && !controlIds.isEmpty();
        if (explicit) {
            Set<Long> wanted = new HashSet<>(controlIds);
            controls = controls.stream().filter(c -> wanted.contains(c.getId())).toList();
        }

        List<Map<String, Object>> skipped = new ArrayList<>();
        List<AuditControlInstance> done = new ArrayList<>();
        if (!controls.isEmpty()) {
            Set<Long> live = evidenceLinkRepository.entityIdsWithLiveLink(
                    "AUDIT_CONTROL_INSTANCE", controls.stream().map(AuditControlInstance::getId).toList());
            Map<Long, AuditSectionInstance> sections = new HashMap<>();
            sectionInstanceRepository.findByEngagementIdOrderByPathAscOrderNoAsc(engagementId)
                    .forEach(s -> sections.put(s.getId(), s));
            boolean leadAuditee = submittedBy.equals(engagement.getLeadAuditeeId());
            var ev = controlAccessGuard.evaluator(engagementId, submittedBy).prefetchControls(controls);
            LocalDateTime now = LocalDateTime.now();

            for (AuditControlInstance c : controls) {
                if (c.isAuditeeEvidenceSubmitted()) {
                    if (explicit) skipped.add(skip(c, "ALREADY_SUBMITTED"));
                    continue;
                }
                if (!live.contains(c.getId())) {
                    if (explicit) skipped.add(skip(c, "NO_LINKED_EVIDENCE"));
                    continue;
                }
                boolean inScope = leadAuditee
                        || ownsSectionChain(c.getSectionInstanceId(), submittedBy, sections)
                        || ev.canAct(c, true);
                if (!inScope) {
                    skipped.add(skip(c, "NOT_YOURS"));
                    continue;
                }
                c.setAuditeeEvidenceSubmitted(true);
                c.setAuditeeEvidenceSubmittedAt(now);
                c.setEvidenceGapReason(null);
                c.setEvidenceGapAt(null);
                c.setEvidenceGapBy(null);
                controlInstanceRepository.save(c);
                done.add(c);
            }

            Map<Long, Long> taskByUser = new HashMap<>();
            Set<Long> sectionIds = new LinkedHashSet<>();
            for (AuditControlInstance c : done) {
                Long owner = effectiveEvidenceOwner(c, sections);
                obligationService.onEvidenceSubmitted(c.getId(), submittedBy, tenantId);
                if (owner != null && !owner.equals(submittedBy)) {
                    obligationService.onEvidenceSubmitted(c.getId(), owner, tenantId);
                }
                for (Long uid : owner != null && !owner.equals(submittedBy)
                        ? List.of(submittedBy, owner) : List.of(submittedBy)) {
                    Long taskId = taskByUser.computeIfAbsent(uid,
                            u -> Optional.ofNullable(
                                    resolveActorTaskInstanceId(engagementId, u, "EVIDENCE_UPLOADED")).orElse(-1L));
                    if (taskId > 0) {
                        fireControlSectionEventWithResolvedTask("EVIDENCE_UPLOADED", c.getId(), taskId, uid);
                    }
                }
                if (c.getSectionInstanceId() != null) sectionIds.add(c.getSectionInstanceId());
            }
            for (Long sid : sectionIds) autoSubmitSectionIfComplete(sid, engagementId, submittedBy);
        }

        log.info("[AUDIT-ENG-SERVICE] Bulk linked-evidence submit | engagementId={} | by={} | submitted={} | skipped={}",
                engagementId, submittedBy, done.size(), skipped.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submitted",           done.size());
        result.put("submittedControlIds", done.stream().map(AuditControlInstance::getId).toList());
        result.put("skipped",             skipped);
        return result;
    }

    private static Map<String, Object> skip(AuditControlInstance c, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("controlId", c.getId());
        m.put("controlCode", c.getControlCodeSnapshot());
        m.put("reason", reason);
        return m;
    }

    /** The caller is the auditee owner of this section or of any section above it. */
    private static boolean ownsSectionChain(Long sectionId, Long userId,
                                            Map<Long, AuditSectionInstance> sections) {
        Long cur = sectionId;
        for (int hops = 0; cur != null && hops < 64; hops++) {
            AuditSectionInstance s = sections.get(cur);
            if (s == null) return false;
            if (userId.equals(s.getAuditeeAssignedUserId())) return true;
            cur = s.getParentInstanceId();
        }
        return false;
    }

    /** The control's evidence owner: its own auditee, else its section's. */
    private static Long effectiveEvidenceOwner(AuditControlInstance c, Map<Long, AuditSectionInstance> sections) {
        if (c.getAuditeeAssignedUserId() != null) return c.getAuditeeAssignedUserId();
        AuditSectionInstance s = c.getSectionInstanceId() != null ? sections.get(c.getSectionInstanceId()) : null;
        return s != null ? s.getAuditeeAssignedUserId() : null;
    }

    /**
     * Fires a project-level section completion event when ALL controls in a
     * project-governed engagement have evidence submitted.
     * Advances the Evidence Submission step (WF16 Step 5) section gate so the
     * step auto-approves once all engagements in the programme are fully evidenced.
     */
    /**
     * Auto-submits a section when ALL controls within it have evidence submitted,
     * then ROLLS UP: each ancestor auto-submits once every child section is
     * submitted and its own controls (if it has any) all have evidence.
     * Called after each control evidence submission — a no-op until the last
     * control in the section is done.
     *
     * WAS: the control's own section only. Controls hang on leaf sections, so a
     * parent clause (A.5, CC6 …) has no controls of its own and returned at
     * "sectionControls.isEmpty()" — parents never auto-submitted, the Sections
     * tab showed every clause open with all of its children done, and
     * submittedSections never reached totalSections.
     */
    private void autoSubmitSectionIfComplete(Long sectionInstanceId, Long engagementId, Long submittedBy) {
        if (sectionInstanceId == null) return;
        try {
            AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId).orElse(null);
            if (section == null) return;

            if (section.getSubmittedAt() == null) {
                List<AuditControlInstance> sectionControls =
                        controlInstanceRepository.findBySectionInstanceIdOrderByOrderNoAsc(sectionInstanceId);
                if (sectionControls.isEmpty()) return;
                if (!sectionControls.stream().allMatch(AuditEngagementService::evidenceDone)) return;
                markAutoSubmitted(section, submittedBy);
            }

            // Roll up through the ancestors.
            Long parentId = section.getParentInstanceId();
            int hops = 0;
            while (parentId != null && hops++ < 64) {
                AuditSectionInstance parent = sectionInstanceRepository.findById(parentId).orElse(null);
                if (parent == null || parent.getSubmittedAt() != null) break;
                List<AuditSectionInstance> children =
                        sectionInstanceRepository.findByParentInstanceIdOrderByOrderNoAsc(parentId);
                boolean childrenDone = children.stream().allMatch(c -> c.getSubmittedAt() != null);
                boolean ownDone = controlInstanceRepository.findBySectionInstanceIdOrderByOrderNoAsc(parentId)
                        .stream().allMatch(AuditEngagementService::evidenceDone);
                if (!childrenDone || !ownDone) break;
                markAutoSubmitted(parent, submittedBy);
                parentId = parent.getParentInstanceId();
            }
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Section auto-submit failed (non-fatal) | sectionInstanceId={} | {}",
                    sectionInstanceId, ex.getMessage());
        }
    }

    /** Evidence submitted, or submitted without evidence for a recorded reason. */
    static boolean evidenceDone(AuditControlInstance c) {
        return c.isAuditeeEvidenceSubmitted() || (c.getEvidenceGapReason() != null && !c.getEvidenceGapReason().isBlank());
    }

    /**
     * Submit one control WITHOUT evidence, for a reason — the evidence owner's
     * answer when they submit a section with this control still empty. Same
     * side effects as submitControlEvidence (delegation closed, section roll-up,
     * checklist item ticked) except that it is not evidence: the submitted flag
     * stays false, so concluding the control effective still needs evidence or
     * the auditor's own override. Callers run the access guard first.
     */
    @Transactional
    public void submitControlWithoutEvidence(AuditControlInstance control, String reason, Long userId, Long tenantId) {
        control.setEvidenceGapReason(reason.trim());
        control.setEvidenceGapAt(LocalDateTime.now());
        control.setEvidenceGapBy(userId);
        controlInstanceRepository.save(control);
        obligationService.onEvidenceSubmitted(control.getId(), userId, tenantId);
        autoSubmitSectionIfComplete(control.getSectionInstanceId(), control.getEngagementId(), userId);
        fireControlSectionEvent("EVIDENCE_UPLOADED", control.getId(), control.getEngagementId(), userId);
        notifyEvidenceSubmitted(control, userId, reason);
        log.info("[AUDIT-ENG-SERVICE] Control submitted without evidence | controlInstanceId={} | by={} | reason={}",
                control.getId(), userId, reason);
    }

    /**
     * Leave one control untested, for a reason — the tester's answer when they
     * submit their testing with it still NOT_TESTED. The result stays
     * NOT_TESTED (not a conclusion; counts and scores are unchanged) and the
     * report shows the reason as a scope limitation. Closes the tester's
     * delegation and ticks the checklist item. Callers run the access guard first.
     */
    @Transactional
    public void markControlNotTested(AuditControlInstance control, String reason, Long userId, Long tenantId) {
        control.setNotTestedReason(reason.trim());
        control.setNotTestedAt(LocalDateTime.now());
        control.setNotTestedBy(userId);
        controlInstanceRepository.save(control);
        obligationService.onControlResultRecorded(control.getId(), userId, tenantId);
        fireControlSectionEvent("TEST_RECORDED", control.getId(), control.getEngagementId(), userId);
        log.info("[AUDIT-ENG-SERVICE] Control left untested | controlInstanceId={} | by={} | reason={}",
                control.getId(), userId, reason);
    }

    /**
     * Auditee-side reopen: the evidence owner's side (section owner, the
     * delegator, lead auditee, engagement owner, override) is not satisfied
     * with what was submitted and wants it redone BEFORE the auditor concludes.
     * The control goes back to open exactly as an auditor send-back does — flag
     * cleared, section un-submitted, checklist item reopened — and the work goes
     * back to whoever did it: their delegation is reopened, or, with no
     * delegation, the control's owner gets a reopen item. Callers check access.
     */
    @Transactional
    public void reopenEvidenceByAuditeeSide(AuditControlInstance control, String reason, Long reopenedBy, Long tenantId) {
        control.setAuditeeEvidenceSubmitted(false);
        control.setAuditeeEvidenceSubmittedAt(null);
        control.setEvidenceGapReason(null);
        control.setEvidenceGapAt(null);
        control.setEvidenceGapBy(null);
        controlInstanceRepository.save(control);
        reopenEvidenceSectionChain(control.getSectionInstanceId(), reopenedBy);
        uncompleteControlItem("EVIDENCE_UPLOADED", control.getId(), control.getEngagementId());
        if (!obligationService.reopenLastEvidenceDelegation(control, reopenedBy, reason, tenantId)) {
            // No delegation to give back — the control's owner gets a reopen item.
            // raiseControlReopen itself only raises the item (the auditor's
            // send-back notifies separately), so say so here.
            Long to = obligationService.raiseControlReopen(control, reason, reopenedBy, tenantId);
            if (to != null) {
                String who = userRepository.findById(reopenedBy)
                        .map(u -> u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName().trim() : u.getEmail())
                        .orElse("Someone");
                String label = (control.getControlCodeSnapshot() != null ? control.getControlCodeSnapshot() + " — " : "")
                        + (control.getControlNameSnapshot() != null ? control.getControlNameSnapshot() : "control #" + control.getId());
                try {
                    notificationService.send(to, "AUDIT_EVIDENCE_REOPENED",
                            who + " asked you to resubmit the evidence for " + label
                                    + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()),
                            "AUDIT_CONTROL_INSTANCE", control.getId());
                } catch (RuntimeException e) {
                    log.warn("[AUDIT-ENG-SERVICE] Reopen notification failed (non-fatal) | {}", e.getMessage());
                }
            }
        }
        log.info("[AUDIT-ENG-SERVICE] Evidence reopened by auditee side | controlInstanceId={} | by={} | reason={}",
                control.getId(), reopenedBy, reason);
    }

    /** A document was reused onto this control: submit its evidence as the person who reused it (no-op if already submitted). */
    @Transactional
    public void submitReusedEvidence(Long controlInstanceId, Long reusedBy, Long tenantId) {
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId).orElse(null);
        if (control == null || control.isAuditeeEvidenceSubmitted() || control.isTestConcluded()) return;
        submitControlEvidence(control.getEngagementId(), controlInstanceId, reusedBy, tenantId);
    }

    /**
     * Linked evidence on this control was ACCEPTED by a reviewer, or arrived
     * AUTOMATION_VERIFIED from an integration. Approved evidence is not pending,
     * so the control counts as submitted everywhere — not only on the card:
     * the submitted flag, the evidence delegations closed, the section roll-up,
     * and the owner's checklist item ticked. No-op when already submitted.
     */
    @Transactional
    public void recordAcceptedEvidence(Long controlInstanceId, Long acceptedBy) {
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId).orElse(null);
        if (control == null || control.isAuditeeEvidenceSubmitted()) return;
        control.setAuditeeEvidenceSubmitted(true);
        control.setAuditeeEvidenceSubmittedAt(LocalDateTime.now());
        control.setEvidenceGapReason(null);
        control.setEvidenceGapAt(null);
        control.setEvidenceGapBy(null);
        controlInstanceRepository.save(control);
        obligationService.onEvidenceSubmitted(controlInstanceId, acceptedBy, control.getTenantId());
        autoSubmitSectionIfComplete(control.getSectionInstanceId(), control.getEngagementId(), acceptedBy);
        fireControlSectionEvent("EVIDENCE_UPLOADED", controlInstanceId, control.getEngagementId(), acceptedBy);
        log.info("[AUDIT-ENG-SERVICE] Accepted linked evidence counts as submitted | controlInstanceId={} | by={}",
                controlInstanceId, acceptedBy);
    }

    /** Ticks an already-done control on the caller's checklist (idempotent) — see AuditSectionSubmissionService. */
    public void tickControl(String completionEvent, Long controlInstanceId, Long engagementId, Long userId) {
        fireControlSectionEvent(completionEvent, controlInstanceId, engagementId, userId);
    }

    private void markAutoSubmitted(AuditSectionInstance section, Long submittedBy) {
        LocalDateTime now = LocalDateTime.now();
        section.setSubmittedAt(now);
        section.setSubmittedBy(submittedBy);
        if (section.getAuditeeSubmittedAt() == null) {
            section.setAuditeeSubmittedAt(now);
            section.setAuditeeSubmittedBy(submittedBy);
        }
        sectionInstanceRepository.save(section);
        log.info("[AUDIT-ENG-SERVICE] Section auto-submitted | sectionInstanceId={} | engagementId={} | by={}",
                section.getId(), section.getEngagementId(), submittedBy);
    }

    /**
     * The inverse of auto-submission: a control in this section needs evidence
     * again (sent back, or its task was reset), so the section — and every
     * ancestor that rolled up on top of it — is no longer complete.
     *
     * WAS: send-back cleared the control's flag and left the section SUBMITTED,
     * so the Sections tab and the stats reported it done while a control in it
     * was waiting for the auditee, and auto-submission could not re-fire
     * later (it skips submitted sections).
     *
     * @return how many sections were reopened
     */
    public int reopenEvidenceSectionChain(Long sectionInstanceId, Long reopenedBy) {
        int reopened = 0;
        Long current = sectionInstanceId;
        int hops = 0;
        LocalDateTime now = LocalDateTime.now();
        while (current != null && hops++ < 64) {
            AuditSectionInstance s = sectionInstanceRepository.findById(current).orElse(null);
            if (s == null) break;
            if (s.getSubmittedAt() != null || s.getAuditeeSubmittedAt() != null) {
                s.setSubmittedAt(null);
                s.setSubmittedBy(null);
                s.setAuditeeSubmittedAt(null);
                s.setAuditeeSubmittedBy(null);
                s.setReopenedAt(now);
                s.setReopenedBy(reopenedBy);
                s.setAuditeeReopenedAt(now);
                s.setAuditeeReopenedBy(reopenedBy);
                sectionInstanceRepository.save(s);
                reopened++;
            }
            current = s.getParentInstanceId();
        }
        return reopened;
    }

    /**
     * Counts controls in an engagement that have evidence PROVIDED — either a direct
     * auditee submission or a reused/linked evidence record. Used for the engagement
     * progress header so it agrees with the control-row "evidence" tags and the
     * evidence-submission auto-complete gate. Adequacy (PASS/FAIL) is judged separately
     * in the auditor review step and is not part of this count.
     */
    private int countControlsWithEvidence(Long engagementId) {
        List<AuditControlInstance> controls =
                controlInstanceRepository.findByEngagementId(engagementId);
        if (controls.isEmpty()) return 0;
        List<Long> ids = controls.stream()
                .map(AuditControlInstance::getId).toList();
        Set<Long> withReused = evidenceLinkRepository
                .entityIdsWithLiveLink("AUDIT_CONTROL_INSTANCE", ids);
        return (int) controls.stream()
                .filter(c -> c.isAuditeeEvidenceSubmitted() || withReused.contains(c.getId()))
                .count();
    }

    private void fireProjectEvidenceCompleteEvent(AuditEngagement engagement,
                                                  Long engagementId,
                                                  Long submittedBy) {
        try {
            List<AuditControlInstance> allControls =
                    controlInstanceRepository.findByEngagementId(engagementId);
            if (allControls.isEmpty()) return;

            // A control counts as "evidence provided" if the auditee directly submitted
            // OR it has any reused/linked evidence. Reused evidence is the auditee
            // providing evidence for this control just like a direct upload — its
            // adequacy is judged later in the auditor's review/testing step (PASS/FAIL
            // + findings), NOT gated here. So presence (direct OR reused) advances the
            // evidence-submission step.
            List<Long> controlIds = allControls.stream()
                    .map(AuditControlInstance::getId).toList();
            Set<Long> controlsWithReusedEvidence = evidenceLinkRepository
                    .entityIdsWithLiveLink("AUDIT_CONTROL_INSTANCE", controlIds);

            long submittedCount = allControls.stream()
                    .filter(c -> c.isAuditeeEvidenceSubmitted()
                            || controlsWithReusedEvidence.contains(c.getId()))
                    .count();
            log.info("[AUDIT-ENG-SERVICE] Evidence progress | engagementId={} | {}/{} controls have evidence (direct or reused)",
                    engagementId, submittedCount, allControls.size());
            if (submittedCount < allControls.size()) return;

            AuditProjectInstance projectInstance = projectInstanceRepository
                    .findById(engagement.getProjectInstanceId()).orElse(null);
            if (projectInstance == null || projectInstance.getWorkflowInstanceId() == null) return;

            var activeSteps = stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(
                            projectInstance.getWorkflowInstanceId(), StepStatus.IN_PROGRESS);
            if (activeSteps.isEmpty()) return;

            var stepInstance = activeSteps.get(0);
            var actorTask = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                    .stream().filter(t -> t.getTaskRole() == TaskRole.ACTOR).findFirst()
                    .or(() -> taskInstanceRepository
                            .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.IN_PROGRESS)
                            .stream().filter(t -> t.getTaskRole() == TaskRole.ACTOR).findFirst());
            if (actorTask.isEmpty()) return;

            eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                    "EVIDENCE_UPLOADED",
                    actorTask.get().getId(),
                    submittedBy,
                    "AUDIT_ENGAGEMENT_INSTANCE",
                    engagementId
            ));
            log.info("[AUDIT-ENG-SERVICE] Project evidence complete | engagementId={} | projectInstanceId={} | taskId={}",
                    engagementId, engagement.getProjectInstanceId(), actorTask.get().getId());
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Project evidence event failed (non-fatal) | engagementId={} | {}",
                    engagementId, ex.getMessage());
        }
    }

    // ── CONTROL TEST RESULT ───────────────────────────────────────────────────

    @Transactional
    public AuditControlInstance recordTestResult(Long engagementId, Long controlInstanceId,
                                                 AuditControlTestRequest req,
                                                 Long testedBy, Long tenantId) {
        AuditControlInstance control = controlInstanceRepository.findById(controlInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", controlInstanceId));

        if (!control.getEngagementId().equals(engagementId))
            throw new BusinessException("CONTROL_MISMATCH", "Control does not belong to this engagement");

        return recordControlResult(control, req, testedBy, tenantId);
    }

    /**
     * THE one way a control-level result is recorded. Both endpoints call it:
     *   PUT /v1/audit/engagements/{id}/controls/{cid}/test-result  (EngagementControlsTab)
     *   PUT /v1/audit/control-instances/{id}/test-result           (ControlFieldworkTab)
     *
     * They used to be two implementations with different side effects. The
     * control-instance one saved the result but never updated engagement
     * counters, never fired TEST_RECORDED (so the section gate could stall with
     * every control tested), and gated evidence differently. Callers must have
     * run ControlAccessGuard.requireCanRecordResult already.
     *
     * EVIDENCE GATE — only a POSITIVE conclusion (EFFECTIVE, PARTIALLY_EFFECTIVE)
     * must rest on evidence. INEFFECTIVE carries its own record (the finding), and
     * "no evidence was provided" is itself a legitimate reason to conclude
     * ineffective — gating it deadlocked the auditor whenever the auditee never
     * uploaded. NOT_APPLICABLE / NOT_TESTED are not conclusions.
     * Evidence counts if ANY of what either endpoint used to accept is present:
     * the submitted flag, an accepted link on the control, or any link on the
     * control or its required tests — so nothing that passed before is refused.
     * Without evidence, an explicit override WITH a reason is accepted and logged.
     */
    @Transactional
    public AuditControlInstance recordControlResult(AuditControlInstance control,
                                                    AuditControlTestRequest req,
                                                    Long testedBy, Long tenantId) {
        if (req == null || req.getTestResult() == null) {
            throw new BusinessException("TEST_RESULT_REQUIRED", "testResult is required");
        }
        Long controlInstanceId = control.getId();
        Long engagementId      = control.getEngagementId();

        boolean positiveConclusion =
                req.getTestResult() == AuditControlInstance.TestResult.EFFECTIVE
                        || req.getTestResult() == AuditControlInstance.TestResult.PARTIALLY_EFFECTIVE;
        boolean override = Boolean.TRUE.equals(req.getEvidenceOverride());
        if (override && (req.getEvidenceOverrideReason() == null || req.getEvidenceOverrideReason().isBlank())) {
            throw new BusinessException("OVERRIDE_REASON_REQUIRED", "An override needs a reason");
        }
        if (positiveConclusion && !override && !hasEvidenceForConclusion(control)) {
            throw new BusinessException("EVIDENCE_REQUIRED",
                    "Attach evidence before concluding this control effective, or record an override reason");
        }
        if (override) {
            log.warn("[AUDIT] Evidence gate overridden | controlInstanceId={} by={} reason={}",
                    controlInstanceId, testedBy, req.getEvidenceOverrideReason());
        }

        control.setTestResult(req.getTestResult());
        // Only overwrite what the caller sent — the fieldwork tab sends notes but
        // no procedure, and must not wipe a procedure recorded from the list.
        if (req.getTestNotes()     != null) control.setTestNotes(req.getTestNotes());
        if (req.getTestProcedure() != null) control.setTestProcedure(req.getTestProcedure());
        control.setTestedAt(LocalDateTime.now());
        control.setTestedBy(testedBy);
        // A conclusion replaces an earlier "left untested" (unless it IS not-tested).
        if (req.getTestResult() != AuditControlInstance.TestResult.NOT_TESTED) {
            control.setNotTestedReason(null);
            control.setNotTestedAt(null);
            control.setNotTestedBy(null);
        }

        if (req.getFindingIssueId() != null) {
            control.setFindingLinked(true);
            control.setFindingIssueId(req.getFindingIssueId());
        }

        controlInstanceRepository.save(control);
        updateEngagementCounts(engagementId);

        // The tester's own control-test delegation is done.
        obligationService.onControlResultRecorded(controlInstanceId, testedBy, tenantId);

        // Fire section completion event so compound section gate advances the workflow
        // step when all of the auditor's controls have been tested.
        fireControlSectionEvent("TEST_RECORDED", controlInstanceId, engagementId, testedBy);

        log.info("[AUDIT] Control tested | controlInstanceId={} | result={}{}",
                controlInstanceId, req.getTestResult(), override ? " | evidence override" : "");
        return control;
    }

    /** See recordControlResult — the union of what both endpoints used to accept. */
    private boolean hasEvidenceForConclusion(AuditControlInstance control) {
        Long id = control.getId();
        if (control.isAuditeeEvidenceSubmitted()) return true;
        if (evidenceLinkRepository.countAcceptedForEntity("AUDIT_CONTROL_INSTANCE", id) > 0) return true;
        if (!evidenceLinkRepository.entityIdsWithAnyLink("AUDIT_CONTROL_INSTANCE", List.of(id)).isEmpty()) return true;
        List<Long> requiredTests = controlTestMappingRepository.findRequiredTestInstanceIdsByControlInstanceId(id);
        return !evidenceLinkRepository.entityIdsWithAnyLink("AUDIT_TEST_INSTANCE", requiredTests).isEmpty();
    }

    // ── SUBMISSION ────────────────────────────────────────────────────────────

    @Transactional
    public void submitSection(Long engagementId, Long sectionInstanceId,
                              boolean cascadeToChildren, Long submittedBy, Long tenantId) {
        AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSectionInstance", sectionInstanceId));

        if (section.getSubmittedAt() != null)
            throw new BusinessException("SECTION_ALREADY_SUBMITTED", "Section already submitted");

        // ── Workflow step guard ───────────────────────────────────────────────
        // Section submission is only valid during Evidence Submission (Step 5).
        // Reject submissions if the project workflow is not at that step.
        // This prevents the test runner or direct API calls from submitting
        // sections out of order (e.g. before sections are assigned in Step 3).
        AuditEngagement engagement = engagementRepository.findById(engagementId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", engagementId));
        if (engagement.getProjectInstanceId() != null) {
            AuditProjectInstance proj = projectInstanceRepository
                    .findById(engagement.getProjectInstanceId()).orElse(null);
            if (proj != null && proj.getWorkflowInstanceId() != null) {
                var activeSteps = stepInstanceRepository.findByWorkflowInstanceIdAndStatus(
                        proj.getWorkflowInstanceId(), StepStatus.IN_PROGRESS);
                boolean atEvidenceStep = activeSteps.stream().anyMatch(si ->
                        si.getSnapName() != null &&
                                si.getSnapName().toLowerCase().contains("evidence submission"));
                if (!atEvidenceStep) {
                    throw new BusinessException("SECTION_SUBMIT_NOT_ALLOWED",
                            "Section submission is only allowed during the Evidence Submission step. " +
                                    "Current workflow step does not permit this action.");
                }
            }
        }
        // ── end workflow step guard ───────────────────────────────────────────

        LocalDateTime now = LocalDateTime.now();
        section.setSubmittedAt(now);
        section.setSubmittedBy(submittedBy);
        // Also set auditeeSubmittedAt — the field MonitorProjectEngagementsAction
        // uses to determine whether this section's evidence is ready for control
        // evaluation. submittedAt and auditeeSubmittedAt represent the same user
        // action (auditee marking their section done) from two perspectives:
        // submittedAt = the section is locked from further auditee edits
        // auditeeSubmittedAt = the monitor readiness check's signal
        if (section.getAuditeeSubmittedAt() == null)
            section.setAuditeeSubmittedAt(now);
        sectionInstanceRepository.save(section);

        if (cascadeToChildren) {
            List<AuditSectionInstance> descendants =
                    sectionInstanceRepository.findAllDescendants(sectionInstanceId, section.getPath());
            for (AuditSectionInstance child : descendants) {
                if (child.getSubmittedAt() == null) {
                    child.setSubmittedAt(now);
                    child.setSubmittedBy(submittedBy);
                    if (child.getAuditeeSubmittedAt() == null)
                        child.setAuditeeSubmittedAt(now);
                    sectionInstanceRepository.save(child);
                }
            }
        }
    }

    @Transactional
    public void reopenSection(Long engagementId, Long sectionInstanceId, Long reopenedBy) {
        AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSectionInstance", sectionInstanceId));
        section.setSubmittedAt(null);
        section.setSubmittedBy(null);
        section.setReopenedAt(LocalDateTime.now());
        section.setReopenedBy(reopenedBy);
        sectionInstanceRepository.save(section);
    }

    // ── STATS ─────────────────────────────────────────────────────────────────

    public Map<String, Object> getEngagementStats(Long engagementId, Long tenantId) {
        AuditEngagement e = engagementRepository.findById(engagementId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", engagementId));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalControls",      e.getTotalControls());
        stats.put("testedControls",     e.getTestedControls());
        stats.put("passedControls",     e.getPassedControls());
        stats.put("failedControls",     e.getFailedControls());
        stats.put("openFindings",       e.getOpenFindingCount());

        long totalSections     = sectionInstanceRepository.countTotalByEngagement(engagementId);
        long submittedSections = sectionInstanceRepository.countSubmittedByEngagement(engagementId);
        stats.put("totalSections",     totalSections);
        stats.put("submittedSections", submittedSections);

        Map<String, Long> resultBreakdown = new LinkedHashMap<>();
        controlInstanceRepository.countByResultForEngagement(engagementId)
                .forEach(r -> resultBreakdown.put(
                        r[0] != null ? r[0].toString() : "NOT_TESTED", (Long) r[1]));
        stats.put("resultBreakdown", resultBreakdown);

        double progress = e.getTotalControls() > 0
                ? (double) e.getTestedControls() / e.getTotalControls() * 100 : 0.0;
        stats.put("progressPct", Math.round(progress));

        return stats;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the active TaskInstance for the acting user on this engagement's
     * current workflow step, then fires a section completion event.
     *
     * Called after domain actions that drive workflow step advancement:
     *   - recordTestResult()      → fires "TEST_RECORDED"
     *   - submitControlEvidence() → fires "EVIDENCE_UPLOADED"
     *   - assignAuditorToControl() → fires "CONTROLS_ASSIGNED"
     *
     * If no active task is found (e.g. no workflow configured, or the step
     * doesn't track this event), the call is a safe no-op — logged at DEBUG.
     *
     * FIX vs uploaded version: fireControlSectionEvent is a class-level private method,
     * NOT nested inside recordTestResult. Java does not allow method declarations
     * inside method bodies. The event is published via ApplicationEventPublisher
     * using TaskSectionEvent.sectionDone() — the correct call signature.
     *
     * @param completionEvent  Must match sectionKey completionEvent in blueprint step section config
     *                         e.g. "TEST_RECORDED", "EVIDENCE_UPLOADED", "CONTROLS_ASSIGNED"
     * @param controlInstanceId The control that was just acted upon
     * @param engagementId      The parent engagement
     * @param userId            The user performing the action (auditor or auditee)
     */

    /**
     * Fires a TaskSectionEvent to advance the compound section gate when a section
     * node is assigned (auditor or auditee). Mirrors fireControlSectionEvent but
     * uses AUDIT_SECTION_INSTANCE as the itemRefType.
     *
     * Called by:
     *   assignSection()          → fires "SECTIONS_ASSIGNED_AUDITOR"  (Step 2 gate)
     *   assignAuditeeToSection() → fires "SECTIONS_ASSIGNED_AUDITEE"  (Step 3 gate)
     *
     * The actorUserId is the ACTOR who performed the assignment (lead auditor),
     * not the assignee — the lead auditor's task is the one that needs to advance.
     */
    /**
     * Fires a simple (non-item-tracking) section completion event on the active
     * step of the project's workflow instance. Used by Steps 11-13 on audit_project_detail.
     *
     * @param projectInstanceId  The running AuditProjectInstance id (e.g. 42)
     * @param completionEvent    Must match wss.completion_event (e.g. "CONSOLIDATION_COMPLETE")
     * @param actorUserId        The user performing the action
     */
    /** Resets the per-engagement item back to PENDING when an assignment is cleared. */
    public void uncompleteEngagementItem(Long projectInstanceId, String completionEvent, Long engagementId) {
        try {
            var projInst = projectInstanceRepository.findById(projectInstanceId).orElse(null);
            if (projInst == null || projInst.getWorkflowInstanceId() == null) return;

            var activeSteps = stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(projInst.getWorkflowInstanceId(), StepStatus.IN_PROGRESS);
            if (activeSteps.isEmpty()) return;

            var stepInstance = activeSteps.get(0);
            // Find ANY actor task at this step — the item belongs to that task
            var anyTask = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.IN_PROGRESS)
                    .stream().findFirst()
                    .or(() -> taskInstanceRepository
                            .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                            .stream().findFirst());
            if (anyTask.isEmpty()) return;

            Long taskId = anyTask.get().getId();
            var matchedSection = taskSectionCompletionRepository
                    .findByTaskInstanceIdAndSnapCompletionEvent(taskId, completionEvent).orElse(null);
            if (matchedSection == null) return;

            taskSectionCompletionService.uncompleteItemByRef(
                    taskId, matchedSection.getSnapSectionKey(), engagementId);
            log.info("[AUDIT-ENG-SERVICE] Engagement item reset | event='{}' | engagementId={} | taskId={}",
                    completionEvent, engagementId, taskId);
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] uncompleteEngagementItem failed (non-fatal) | event={} | eng={} | {}",
                    completionEvent, engagementId, ex.getMessage());
        }
    }

    /** Overload for non-item-tracked sections (Steps 11-13) */
    public void fireProjectSectionEvent(Long projectInstanceId, String completionEvent, Long actorUserId) {
        fireProjectSectionEvent(projectInstanceId, completionEvent, null, actorUserId);
    }

    /**
     * Item-tracked overload — when itemRefId is non-null, calls completeItemByRef()
     * so only this one item is marked done. Used for ENGAGEMENTS_LEAD_ASSIGNED.
     */
    public void fireProjectSectionEvent(Long projectInstanceId, String completionEvent,
                                        Long itemRefId, Long actorUserId) {
        try {
            var projInst = projectInstanceRepository.findById(projectInstanceId).orElse(null);
            if (projInst == null || projInst.getWorkflowInstanceId() == null) return;

            Long workflowInstanceId = projInst.getWorkflowInstanceId();
            var activeSteps = stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(workflowInstanceId, StepStatus.IN_PROGRESS);
            if (activeSteps.isEmpty()) {
                log.warn("[AUDIT-ENG-SERVICE] fireProjectSectionEvent | no IN_PROGRESS step | event='{}' | projectInstanceId={}",
                        completionEvent, projectInstanceId);
                return;
            }

            var stepInstance = activeSteps.get(0);
            log.info("[AUDIT-ENG-SERVICE] fireProjectSectionEvent | event='{}' | stepInstanceId={} | actorUserId={} | itemRefId={}",
                    completionEvent, stepInstance.getId(), actorUserId, itemRefId);

            var actorTask = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.IN_PROGRESS)
                    .stream()
                    .filter(t -> actorUserId.equals(t.getAssignedUserId()) && t.getTaskRole() == TaskRole.ACTOR)
                    .findFirst()
                    .or(() -> taskInstanceRepository
                            .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                            .stream()
                            .filter(t -> actorUserId.equals(t.getAssignedUserId()) && t.getTaskRole() == TaskRole.ACTOR)
                            .findFirst());

            if (actorTask.isEmpty()) {
                log.warn("[AUDIT-ENG-SERVICE] No ACTOR task for userId={} at stepInstanceId={} — " +
                        "skipping '{}' event", actorUserId, stepInstance.getId(), completionEvent);
                return;
            }

            Long taskId = actorTask.get().getId();

            if (itemRefId != null) {
                var matchedSection = taskSectionCompletionRepository
                        .findByTaskInstanceIdAndSnapCompletionEvent(taskId, completionEvent)
                        .orElse(null);
                if (matchedSection == null) {
                    log.debug("[AUDIT-ENG-SERVICE] No section snapshotted with completionEvent=\'{}\' on taskId={} — skipping",
                            completionEvent, taskId);
                    return;
                }
                taskSectionCompletionService.completeItemByRef(
                        taskId, matchedSection.getSnapSectionKey(), itemRefId, actorUserId);
                log.info("[AUDIT-ENG-SERVICE] Project section item completed | event=\'{}\' | itemRefId={} | taskId={} | by={}",
                        completionEvent, itemRefId, taskId, actorUserId);
            } else {
                eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                        completionEvent, taskId, actorUserId, null, null));
                log.info("[AUDIT-ENG-SERVICE] Project section event fired | event=\'{}\' | projectInstanceId={} | taskId={} | by={}",
                        completionEvent, projectInstanceId, taskId, actorUserId);
            }
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Project section event \'{}\' failed (non-fatal) | projectInstanceId={} | {}",
                    completionEvent, projectInstanceId, ex.getMessage());
        }
    }

    /**
     * Fires the DRAFT_REPORTS_REVIEWED section completion event for an engagement.
     * Called by submitReportReview() — each save marks this engagement's item as done.
     * Idempotent: subsequent saves on already-completed items are no-ops.
     * Once all engagements under the project are reviewed, hasSections=false
     * and the Complete Step button becomes available to the auditor.
     */
    public void fireDraftReportReviewedEvent(Long engagementId, Long actorUserId) {
        try {
            AuditEngagement engagement = engagementRepository.findById(engagementId).orElse(null);
            if (engagement == null) return;

            Long workflowInstanceId = engagement.getWorkflowInstanceId();
            if (workflowInstanceId == null && engagement.getProjectInstanceId() != null) {
                var projInst = projectInstanceRepository.findById(engagement.getProjectInstanceId()).orElse(null);
                if (projInst != null) workflowInstanceId = projInst.getWorkflowInstanceId();
            }
            if (workflowInstanceId == null) return;

            var activeSteps = stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(workflowInstanceId, StepStatus.IN_PROGRESS);
            if (activeSteps.isEmpty()) return;

            var stepInstance = activeSteps.get(0);
            var actorTask = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.IN_PROGRESS)
                    .stream()
                    .filter(t -> actorUserId.equals(t.getAssignedUserId()) && t.getTaskRole() == TaskRole.ACTOR)
                    .findFirst()
                    .or(() -> taskInstanceRepository
                            .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                            .stream()
                            .filter(t -> actorUserId.equals(t.getAssignedUserId()) && t.getTaskRole() == TaskRole.ACTOR)
                            .findFirst());

            if (actorTask.isEmpty()) {
                log.debug("[AUDIT-ENG-SERVICE] No ACTOR task for userId={} at stepInstanceId={} — " +
                        "skipping DRAFT_REPORTS_REVIEWED event", actorUserId, stepInstance.getId());
                return;
            }

            Long taskId = actorTask.get().getId();

            // Use item-tracked completion — one item per engagement. This is the
            // same pattern as ENGAGEMENTS_ONBOARDED / EVIDENCE_OWNERS_ASSIGNED.
            // The blanket sectionDone would close the gate on the first engagement's
            // review, skipping remaining engagements.
            var matchedSection = taskSectionCompletionRepository
                    .findByTaskInstanceIdAndSnapCompletionEvent(taskId, "DRAFT_REPORTS_REVIEWED")
                    .orElse(null);
            if (matchedSection == null) {
                log.warn("[AUDIT-ENG-SERVICE] No DRAFT_REPORTS_REVIEWED section snapshotted on taskId={} — skipping",
                        taskId);
                return;
            }
            taskSectionCompletionService.completeItemByRef(
                    taskId, matchedSection.getSnapSectionKey(), engagementId, actorUserId);
            log.info("[AUDIT-ENG-SERVICE] DRAFT_REPORTS_REVIEWED item completed | engagementId={} | taskId={} | by={}",
                    engagementId, taskId, actorUserId);
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] DRAFT_REPORTS_REVIEWED event failed (non-fatal) | engagementId={} | {}",
                    engagementId, ex.getMessage());
        }
    }

    /** Resolved target for a section-assignment completion event — see
     *  resolveSectionAssignmentTarget()/fireSectionAssignmentEvent() below. */
    private record SectionAssignmentTarget(Long taskId, String snapSectionKey) {}

    private void fireSectionAssignmentEvent(String completionEvent,
                                            Long sectionInstanceId,
                                            Long engagementId,
                                            Long actorUserId) {
        if (actorUserId == null || actorUserId == 0L) return;
        SectionAssignmentTarget target = resolveSectionAssignmentTarget(completionEvent, engagementId, actorUserId);
        if (target == null) return; // resolveSectionAssignmentTarget already logged why
        fireSectionAssignmentEventWithTarget(target, sectionInstanceId, actorUserId, completionEvent);
    }

    /**
     * Resolves the (taskId, snapSectionKey) target for a section-assignment
     * completion event — the DB-read part of fireSectionAssignmentEvent
     * (engagement lookup, workflow-instance resolution, active-step lookup,
     * actor-task lookup, matched-section lookup).
     *
     * WHY THIS WAS ANOTHER BULK N+1: for one bulkAssignSections request,
     * completionEvent, engagementId, and actorUserId (the caller doing the
     * bulk assign) are the SAME for every section in the batch — so this
     * entire resolution chain returns the identical target on every
     * iteration, exactly like resolveActorTaskInstanceId for bulk control
     * assignment. Only the final completeItemByRef() call genuinely needs
     * to run per-section (each section is its own tracked compound-task
     * item) — that part stays in fireSectionAssignmentEventWithTarget below.
     *
     * This also reinforces, rather than changes, the existing "resolve
     * taskId NOW, before completing the item" principle already documented
     * on the old single-method version: completing an item can advance the
     * workflow to the next step within the same thread, so re-resolving
     * per section mid-batch could silently start pointing at the NEXT
     * step's task partway through a bulk request. Resolving once up front
     * and reusing it for the whole batch is the correct fix for that, not
     * just a performance one.
     */
    private SectionAssignmentTarget resolveSectionAssignmentTarget(
            String completionEvent, Long engagementId, Long actorUserId) {
        try {
            AuditEngagement engagement = engagementRepository.findById(engagementId).orElse(null);
            if (engagement == null) return null;

            // Resolve the workflow instance to use:
            // 1. Engagement has its own workflow (WF14 individual lifecycle) → use it
            // 2. Engagement is project-governed (projectInstanceId set, no own workflow) →
            //    fall back to the project's workflow instance (WF16)
            Long workflowInstanceId = engagement.getWorkflowInstanceId();
            if (workflowInstanceId == null && engagement.getProjectInstanceId() != null) {
                var projInst = projectInstanceRepository
                        .findById(engagement.getProjectInstanceId()).orElse(null);
                if (projInst != null) {
                    workflowInstanceId = projInst.getWorkflowInstanceId();
                }
            }
            if (workflowInstanceId == null) {
                log.debug("[AUDIT-ENG-SERVICE] No workflow instance for engagementId={} — " +
                        "skipping section assignment event '{}'", engagementId, completionEvent);
                return null;
            }
            var activeSteps = stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(
                            workflowInstanceId, StepStatus.IN_PROGRESS);
            if (activeSteps.isEmpty()) return null;
            var stepInstance = activeSteps.get(0);
            var actorTask = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.IN_PROGRESS)
                    .stream()
                    .filter(t -> actorUserId.equals(t.getAssignedUserId())
                            && t.getTaskRole() == TaskRole.ACTOR)
                    .findFirst()
                    .or(() -> taskInstanceRepository
                            .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                            .stream()
                            .filter(t -> actorUserId.equals(t.getAssignedUserId())
                                    && t.getTaskRole() == TaskRole.ACTOR)
                            .findFirst());
            if (actorTask.isEmpty()) {
                log.debug("[AUDIT-ENG-SERVICE] No ACTOR task for userId={} at stepInstanceId={} — " +
                        "skipping section event '{}'", actorUserId, stepInstance.getId(), completionEvent);
                return null;
            }
            Long taskId = actorTask.get().getId();

            // Find the section snapshot that actually matches the completionEvent
            // the caller asked for — NOT just the first registered section on this
            // task. (Previously this picked registeredSections.get(0)'s event
            // regardless of which gate the caller intended, which could complete
            // the wrong section.)
            var matchedSection = taskSectionCompletionRepository
                    .findByTaskInstanceIdAndSnapCompletionEvent(taskId, completionEvent)
                    .orElse(null);
            if (matchedSection == null) {
                log.debug("[AUDIT-ENG-SERVICE] No section snapshotted with completionEvent='{}' on taskId={} — skipping",
                        completionEvent, taskId);
                return null;
            }
            return new SectionAssignmentTarget(taskId, matchedSection.getSnapSectionKey());
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Section assignment target resolution '{}' failed (non-fatal) | " +
                    "engagementId={} | {}", completionEvent, engagementId, ex.getMessage());
            return null;
        }
    }

    /**
     * Fires the per-section completion using an ALREADY-RESOLVED target (see
     * resolveSectionAssignmentTarget). Same "never break the domain action"
     * exception contract as the original single-method version.
     */
    private void fireSectionAssignmentEventWithTarget(SectionAssignmentTarget target, Long sectionInstanceId,
                                                      Long actorUserId, String completionEvent) {
        if (actorUserId == null || actorUserId == 0L) return;
        try {
            log.info("[AUDIT-ENG-SERVICE] Section item completing | event='{}' | sectionKey='{}' | " +
                            "sectionInstanceId={} (itemRef) | taskId={} | actorUserId={}",
                    completionEvent, target.snapSectionKey(), sectionInstanceId, target.taskId(), actorUserId);

            // tracks_items=1 on this section — sectionInstanceId is ONE of potentially
            // many items (one per AUDIT_SECTION_INSTANCE across ALL engagements in the
            // project). completeItemByRef() marks only this one item done; the section
            // gate itself only flips to complete once every registered item is done.
            // This is the fix for: assigning a section on engagement A was previously
            // closing the whole gate via the blanket TaskSectionEvent, leaving sections
            // on engagement B/C unassigned forever because the gate had already fired.
            taskSectionCompletionService.completeItemByRef(
                    target.taskId(), target.snapSectionKey(), sectionInstanceId, actorUserId);

            log.info("[AUDIT-ENG-SERVICE] Section item completed | event='{}' | sectionInstanceId={} | taskId={} | actorUserId={}",
                    completionEvent, sectionInstanceId, target.taskId(), actorUserId);
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Section assignment event '{}' failed (non-fatal) | " +
                    "sectionInstanceId={} | {}", completionEvent, sectionInstanceId, ex.getMessage());
        }
    }

    /**
     * Companion to fireSectionAssignmentEvent() — resets the section's item back
     * to PENDING when its assignment is cleared, so the step's compound-task gate
     * re-opens instead of staying complete on a now-unassigned section.
     */
    private void uncompleteSectionAssignmentEvent(String completionEvent,
                                                  Long sectionInstanceId,
                                                  Long engagementId) {
        try {
            AuditEngagement engagement = engagementRepository.findById(engagementId).orElse(null);
            if (engagement == null) return;

            Long workflowInstanceId = engagement.getWorkflowInstanceId();
            if (workflowInstanceId == null && engagement.getProjectInstanceId() != null) {
                var projInst = projectInstanceRepository
                        .findById(engagement.getProjectInstanceId()).orElse(null);
                if (projInst != null) {
                    workflowInstanceId = projInst.getWorkflowInstanceId();
                }
            }
            if (workflowInstanceId == null) return;

            var activeSteps = stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(workflowInstanceId, StepStatus.IN_PROGRESS);
            if (activeSteps.isEmpty()) return;
            var stepInstance = activeSteps.get(0);

            var anyTask = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.IN_PROGRESS)
                    .stream().findFirst()
                    .or(() -> taskInstanceRepository
                            .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                            .stream().findFirst());
            if (anyTask.isEmpty()) return;

            Long taskId = anyTask.get().getId();
            var matchedSection = taskSectionCompletionRepository
                    .findByTaskInstanceIdAndSnapCompletionEvent(taskId, completionEvent).orElse(null);
            if (matchedSection == null) return;

            taskSectionCompletionService.uncompleteItemByRef(
                    taskId, matchedSection.getSnapSectionKey(), sectionInstanceId);
            log.info("[AUDIT-ENG-SERVICE] Section assignment item reset | event='{}' | sectionInstanceId={} | taskId={}",
                    completionEvent, sectionInstanceId, taskId);
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] uncompleteSectionAssignmentEvent failed (non-fatal) | event={} | sectionInstanceId={} | {}",
                    completionEvent, sectionInstanceId, ex.getMessage());
        }
    }

    /**
     * Companion to fireControlSectionEvent for an assignment that was CLEARED:
     * resets that control's tracked item to PENDING on every live task of the
     * active step that tracks this completion event, so the gate re-opens.
     * The actor who originally completed it is not recorded, so every task is
     * checked; uncompleteItemByRef is a no-op where the item is absent or
     * already pending. Never breaks the domain action.
     */
    private void uncompleteControlItem(String completionEvent, Long controlInstanceId, Long engagementId) {
        try {
            AuditEngagement engagement = engagementRepository.findById(engagementId).orElse(null);
            if (engagement == null || engagement.getWorkflowInstanceId() == null) return;
            for (var step : stepInstanceRepository.findByWorkflowInstanceIdAndStatus(
                    engagement.getWorkflowInstanceId(), StepStatus.IN_PROGRESS)) {
                List<com.kashi.grc.workflow.domain.TaskInstance> tasks = new ArrayList<>(
                        taskInstanceRepository.findByStepInstanceIdAndStatus(step.getId(), TaskStatus.IN_PROGRESS));
                tasks.addAll(taskInstanceRepository.findByStepInstanceIdAndStatus(step.getId(), TaskStatus.PENDING));
                for (var task : tasks) {
                    taskSectionCompletionRepository
                            .findByTaskInstanceIdAndSnapCompletionEvent(task.getId(), completionEvent)
                            .ifPresent(sec -> taskSectionCompletionService.uncompleteItemByRef(
                                    task.getId(), sec.getSnapSectionKey(), controlInstanceId));
                }
            }
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] uncompleteControlItem failed (non-fatal) | event={} | controlInstanceId={} | {}",
                    completionEvent, controlInstanceId, ex.getMessage());
        }
    }

    private void fireControlSectionEvent(String completionEvent,
                                         Long controlInstanceId,
                                         Long engagementId,
                                         Long userId) {
        try {
            // The control's OWNER on that side too, when someone else did the work —
            // a delegate usually holds no evidence/testing task of their own, so
            // ticking only theirs left the owner's checklist item open and the
            // owner's gate could never close. (The bulk path already did both.)
            tickOwnerIfDelegated(completionEvent, controlInstanceId, engagementId, userId);

            Long taskInstanceId = resolveActorTaskInstanceId(engagementId, userId, completionEvent);
            if (taskInstanceId == null) return; // resolveActorTaskInstanceId already logged why

            completeControlOnTask(completionEvent, taskInstanceId, controlInstanceId, userId);

            log.info("[AUDIT-ENG-SERVICE] Section event fired | event='{}' | " +
                            "controlInstanceId={} | taskInstanceId={} | userId={}",
                    completionEvent, controlInstanceId, taskInstanceId, userId);

        } catch (Exception ex) {
            // Never let section event failure break the domain action
            log.warn("[AUDIT-ENG-SERVICE] Section event '{}' failed (non-fatal) | " +
                    "controlInstanceId={} | {}", completionEvent, controlInstanceId, ex.getMessage());
        }
    }

    /**
     * Ticks ONE control off the task's checklist.
     *
     * WAS: a blanket TaskSectionEvent carrying the control as the artifact. The
     * listener behind it (TaskSectionCompletionService.onSectionEvent) marks the
     * whole SECTION complete and never looks at items — so on an item-tracked
     * gate (EVIDENCE_UPLOADED, CONTROLS_EVALUATED: one item per control) the
     * first control submitted closed the gate and auto-approved the person's
     * task, with every other control still open.
     *
     * Item-tracked sections now complete the item (completeItemByRef); the gate
     * closes when the last item does (WorkflowEventListener.onItemCompleted). A
     * section that tracks no items keeps the old one-shot event, which is right
     * for it.
     */
    /**
     * Evidence handed over: tell the control's auditor it is ready for review,
     * and the control's evidence owner when somebody else (a delegate, or the
     * delegator) did the submitting. Never fails the submission.
     */
    private void notifyEvidenceSubmitted(AuditControlInstance c, Long submittedBy, String gapReason) {
        try {
            Map<Long, AuditSectionInstance> sections = new HashMap<>();
            sectionInstanceRepository.findByEngagementIdOrderByPathAscOrderNoAsc(c.getEngagementId())
                    .forEach(x -> sections.put(x.getId(), x));
            Long auditor = com.kashi.grc.audit.workflow.AuditControlSectionItemRegistrar.effectiveOwner(c, sections, true);
            Long owner   = com.kashi.grc.audit.workflow.AuditControlSectionItemRegistrar.effectiveOwner(c, sections, false);
            String who = userRepository.findById(submittedBy)
                    .map(u -> u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName().trim() : u.getEmail())
                    .orElse("Someone");
            String ctrl = (c.getControlCodeSnapshot() != null ? c.getControlCodeSnapshot() + " — " : "") + c.getControlNameSnapshot();
            String what = gapReason == null
                    ? who + " submitted evidence for " + ctrl
                    : who + " submitted " + ctrl + " without evidence: " + gapReason;
            Set<Long> sent = new HashSet<>();
            if (auditor != null && !auditor.equals(submittedBy) && sent.add(auditor)) {
                notificationService.send(auditor, "AUDIT_EVIDENCE_SUBMITTED", what + " — ready for your review",
                        "AUDIT_CONTROL_INSTANCE", c.getId());
            }
            if (owner != null && !owner.equals(submittedBy) && sent.add(owner)) {
                notificationService.send(owner, "AUDIT_EVIDENCE_SUBMITTED", what,
                        "AUDIT_CONTROL_INSTANCE", c.getId());
            }
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Evidence notification failed (non-fatal) | controlInstanceId={} | {}",
                    c.getId(), ex.getMessage());
        }
    }

    private void tickOwnerIfDelegated(String completionEvent, Long controlInstanceId, Long engagementId, Long userId) {
        boolean evidence = "EVIDENCE_UPLOADED".equals(completionEvent);
        if (!evidence && !"TEST_RECORDED".equals(completionEvent)) return;
        try {
            AuditControlInstance c = controlInstanceRepository.findById(controlInstanceId).orElse(null);
            if (c == null) return;
            Map<Long, AuditSectionInstance> sections = new HashMap<>();
            sectionInstanceRepository.findByEngagementIdOrderByPathAscOrderNoAsc(engagementId)
                    .forEach(x -> sections.put(x.getId(), x));
            Long owner = com.kashi.grc.audit.workflow.AuditControlSectionItemRegistrar
                    .effectiveOwner(c, sections, !evidence);
            if (owner == null || owner.equals(userId)) return;
            Long ownerTask = resolveActorTaskInstanceId(engagementId, owner, completionEvent);
            if (ownerTask != null) completeControlOnTask(completionEvent, ownerTask, controlInstanceId, userId);
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Owner tick failed (non-fatal) | event={} | controlInstanceId={} | {}",
                    completionEvent, controlInstanceId, ex.getMessage());
        }
    }

    void completeControlOnTask(String completionEvent, Long taskInstanceId, Long controlInstanceId, Long userId) {
        var section = taskSectionCompletionRepository
                .findByTaskInstanceIdAndSnapCompletionEvent(taskInstanceId, completionEvent)
                .orElse(null);
        if (section != null && section.isSnapTracksItems()) {
            taskSectionCompletionService.completeItemByRef(
                    taskInstanceId, section.getSnapSectionKey(), controlInstanceId, userId);
            return;
        }
        eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                completionEvent, taskInstanceId, userId, "AUDIT_CONTROL_INSTANCE", controlInstanceId));
    }

    /**
     * Resolves the ACTOR taskInstanceId for (engagementId, userId) — the part of
     * fireControlSectionEvent that involves DB reads (engagement lookup, active
     * step lookup, task lookup). Split out so bulk callers can resolve it ONCE
     * instead of once per row.
     *
     * WHY THIS WAS THE REAL BULK-ASSIGN N+1, NOT THE control.save() CALLS:
     * for a single bulkAssignControls request, engagementId and userId (the
     * actor doing the bulk assign) are the SAME for every control in the
     * batch — so this lookup returns the identical taskInstanceId on every
     * iteration. It isn't "N different rows to fetch" (what batching fixes),
     * it's the SAME row being re-fetched N times (what hoisting fixes). For
     * 100 controls this was up to 400 avoidable queries (engagement + active
     * step + up to 2 task-status lookups per control) for data that never
     * changed between iterations.
     */
    private Long resolveActorTaskInstanceId(Long engagementId, Long userId, String completionEvent) {
        if (userId == null) return null;   // system actions (integration-verified evidence) hold no task
        AuditEngagement engagement = engagementRepository.findById(engagementId).orElse(null);
        if (engagement == null || engagement.getWorkflowInstanceId() == null) {
            log.debug("[AUDIT-ENG-SERVICE] No workflow instance for engagementId={} — " +
                    "skipping section event '{}'", engagementId, completionEvent);
            return null;
        }

        var activeSteps = stepInstanceRepository
                .findByWorkflowInstanceIdAndStatus(
                        engagement.getWorkflowInstanceId(), StepStatus.IN_PROGRESS);
        if (activeSteps.isEmpty()) {
            log.debug("[AUDIT-ENG-SERVICE] No IN_PROGRESS step for workflowInstanceId={} — " +
                            "skipping section event '{}'",
                    engagement.getWorkflowInstanceId(), completionEvent);
            return null;
        }

        var stepInstance = activeSteps.get(0);

        // Try IN_PROGRESS first, fall back to PENDING (task may not have been opened yet)
        var actorTask = taskInstanceRepository
                .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.IN_PROGRESS)
                .stream()
                .filter(t -> userId.equals(t.getAssignedUserId()) && t.getTaskRole() == TaskRole.ACTOR)
                .findFirst()
                .or(() -> taskInstanceRepository
                        .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                        .stream()
                        .filter(t -> userId.equals(t.getAssignedUserId()) && t.getTaskRole() == TaskRole.ACTOR)
                        .findFirst());

        if (actorTask.isEmpty()) {
            log.debug("[AUDIT-ENG-SERVICE] No ACTOR task for userId={} at stepInstanceId={} — " +
                    "skipping section event '{}'", userId, stepInstance.getId(), completionEvent);
            return null;
        }
        return actorTask.get().getId();
    }

    /**
     * Bulk-path counterpart to fireControlSectionEvent — takes an
     * ALREADY-RESOLVED taskInstanceId (see resolveActorTaskInstanceId) instead
     * of re-resolving it per control. Same "never break the domain action"
     * exception contract as the original.
     */
    private void fireControlSectionEventWithResolvedTask(String completionEvent, Long controlInstanceId,
                                                         Long taskInstanceId, Long userId) {
        if (taskInstanceId == null) return;
        try {
            completeControlOnTask(completionEvent, taskInstanceId, controlInstanceId, userId);
            log.info("[AUDIT-ENG-SERVICE] Section event fired | event='{}' | " +
                            "controlInstanceId={} | taskInstanceId={} | userId={}",
                    completionEvent, controlInstanceId, taskInstanceId, userId);
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Section event '{}' failed (non-fatal) | " +
                    "controlInstanceId={} | {}", completionEvent, controlInstanceId, ex.getMessage());
        }
    }

    private void startWorkflowIfConfigured(AuditEngagement engagement,
                                           Long overrideWorkflowId,
                                           Long initiatedBy, Long tenantId) {
        Long workflowId = overrideWorkflowId;
        if (workflowId == null) {
            String expectedName = "AUDIT_ENGAGEMENT_" + engagement.getAuditType().name();
            workflowId = workflowRepository.findAll().stream()
                    .filter(w -> w.isActive() && expectedName.equals(w.getName()))
                    .findFirst().map(w -> w.getId()).orElse(null);
        }
        if (workflowId == null) {
            log.warn("[AUDIT] No workflow configured for auditType={}", engagement.getAuditType());
            return;
        }
        try {
            StartWorkflowRequest req = new StartWorkflowRequest();
            req.setWorkflowId(workflowId);
            req.setEntityType("AUDIT_ENGAGEMENT");
            req.setEntityId(engagement.getId());
            req.setPriority("MEDIUM");
            // FIX: plannedEnd is LocalDateTime — no .atStartOfDay() needed, setDueDate takes LocalDateTime
            if (engagement.getPlannedEnd() != null)
                req.setDueDate(engagement.getPlannedEnd());

            WorkflowInstanceResponse wf = workflowEngineService.startWorkflow(req, tenantId, initiatedBy);
            engagement.setWorkflowInstanceId(wf.getId());
            engagementRepository.save(engagement);
            log.info("[AUDIT] Workflow started | engagementId={} | instanceId={}",
                    engagement.getId(), wf.getId());
        } catch (Exception e) {
            log.error("[AUDIT] Workflow start failed | engagementId={} | {}",
                    engagement.getId(), e.getMessage());
        }
    }

    private void updateEngagementCounts(Long engagementId) {
        long tested = controlInstanceRepository.countTestedByEngagement(engagementId);
        Map<String, Long> breakdown = new LinkedHashMap<>();
        controlInstanceRepository.countByResultForEngagement(engagementId)
                .forEach(r -> breakdown.put(r[0] != null ? r[0].toString() : "NOT_TESTED", (Long) r[1]));

        engagementRepository.findById(engagementId).ifPresent(e -> {
            e.setTestedControls((int) tested);
            e.setPassedControls(breakdown.getOrDefault("EFFECTIVE", 0L).intValue());
            e.setFailedControls(breakdown.getOrDefault("INEFFECTIVE", 0L).intValue());
            engagementRepository.save(e);
        });
    }

    private String buildEngagementRef(Long tenantId) {
        long seq = engagementRepository.nextEngagementRefSequence(tenantId);
        return String.format("ENG-%d-%04d", LocalDateTime.now().getYear(), seq);
    }

    private AuditEngagementResponse toResponse(AuditEngagement e) {
        return AuditEngagementResponse.builder()
                .id(e.getId()).engagementRef(e.getEngagementRef())
                .projectId(e.getProjectId()).name(e.getName()).description(e.getDescription())
                .auditType(e.getAuditType()).status(e.getStatus()).frameworkRef(e.getFrameworkRef())
                .leadAuditorId(e.getLeadAuditorId()).leadAuditeeId(e.getLeadAuditeeId()).ownerId(e.getOwnerId())
                .totalControls(e.getTotalControls()).snapshotStatus(e.getSnapshotStatus())
                .testedControls(e.getTestedControls())
                // Count controls that have evidence PROVIDED — direct submit OR reused
                // link — so the header progress matches the control-row tags and the
                // auto-complete gate (adequacy is judged later in the review step).
                .submittedControls(countControlsWithEvidence(e.getId()))
                .passedControls(e.getPassedControls()).failedControls(e.getFailedControls())
                .openFindingCount(e.getOpenFindingCount()).workflowInstanceId(e.getWorkflowInstanceId())
                .createdAt(e.getCreatedAt()).updatedAt(e.getUpdatedAt())
                // FIX: response expects LocalDate, domain has LocalDateTime — convert with toLocalDate()
                .plannedStart(e.getPlannedStart() != null ? e.getPlannedStart().toLocalDate() : null)
                .plannedEnd(e.getPlannedEnd()     != null ? e.getPlannedEnd().toLocalDate()   : null)
                .listScreenKey(e.getListScreenKey()).detailScreenKey(e.getDetailScreenKey())
                .build();
    }
}