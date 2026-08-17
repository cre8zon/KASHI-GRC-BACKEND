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
                        java.util.Map.of(
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
                .filter(java.util.Objects::nonNull)
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
            }
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
            }
            // NOTE: control cascade deliberately removed — see assignSection() for
            // the matching auditor-side rationale. Controls inherit the section's
            // auditee implicitly via AuditWorkflowActorResolver /
            // AuditProjectWorkflowActorResolver until explicitly overridden by
            // assignAuditeeToControl().
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

        control.setAuditeeAssignedUserId(auditeeUserId);
        if (evidenceDueDate != null) control.setEvidenceDueDate(evidenceDueDate);
        controlInstanceRepository.save(control);

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

        control.setAssignedAuditorId(auditorId);
        controlInstanceRepository.save(control);

        fireControlSectionEvent("CONTROLS_ASSIGNED", controlInstanceId, engagementId, auditorId);

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
                                  com.kashi.grc.audit.dto.request.BulkControlAssignRequest req,
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
            if (changed) {
                controlInstanceRepository.save(ctrl);
                if (req.getAuditorUserId() != null) {
                    fireControlSectionEventWithResolvedTask("CONTROLS_ASSIGNED", ctrl.getId(),
                            resolvedTaskInstanceId, actorId);
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
                                  com.kashi.grc.audit.dto.request.BulkSectionAssignRequest req,
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
        controlInstanceRepository.save(control);

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
        if (submittedBy != null) {
            AuditSectionInstance parentSection = control.getSectionInstanceId() != null
                    ? sectionInstanceRepository.findById(control.getSectionInstanceId()).orElse(null)
                    : null;
            boolean isControlOwner   = submittedBy.equals(control.getAuditeeAssignedUserId());
            boolean isSectionOwner   = parentSection != null
                    && submittedBy.equals(parentSection.getAuditeeAssignedUserId());
            boolean isEngagementOwner = engagementRepository.findById(engagementId)
                    .map(e -> submittedBy.equals(e.getLeadAuditorId())
                            || submittedBy.equals(e.getOwnerId()))
                    .orElse(false);
            if (!isControlOwner && !isSectionOwner && !isEngagementOwner) {
                throw new BusinessException("NOT_EVIDENCE_OWNER",
                        "You are not assigned as evidence owner for this control or its section");
            }
        }

        // Require at least one uploaded document OR automated evidence before allowing submit
        boolean hasManualDocs = !documentLinkRepository
                .findAllActiveByEntity("AUDIT_CONTROL_INSTANCE", controlInstanceId).isEmpty();
        boolean hasAutomatedEvidence = evidenceLinkRepository
                .countAcceptedForEntity("AUDIT_CONTROL_INSTANCE", controlInstanceId) > 0;
        if (!hasManualDocs && !hasAutomatedEvidence) {
            throw new BusinessException("NO_EVIDENCE",
                    "Please upload at least one evidence file before submitting.");
        }

        control.setAuditeeEvidenceSubmitted(true);
        control.setAuditeeEvidenceSubmittedAt(java.time.LocalDateTime.now());
        controlInstanceRepository.save(control);

        // Auto-submit parent section when all its controls have evidence submitted.
        // Auditees don't manually submit sections — it happens automatically when
        // they finish uploading evidence for all controls in their section.
        autoSubmitSectionIfComplete(control.getSectionInstanceId(), engagementId, submittedBy);

        // Fire section event for engagement-level workflow (WF14 SOC2 Type II)
        fireControlSectionEvent("EVIDENCE_UPLOADED", controlInstanceId, engagementId, submittedBy);

        // Note: Step 5 (Evidence Submission) advances via manual APPROVE by lead auditor,
        // not by auto-gate. This allows partial evidence submission — auditors can
        // proceed to review even if not all 41 controls have evidence yet.

        log.info("[AUDIT-ENG-SERVICE] Evidence submitted | controlInstanceId={} | by={}",
                controlInstanceId, submittedBy);
    }

    /**
     * Fires a project-level section completion event when ALL controls in a
     * project-governed engagement have evidence submitted.
     * Advances the Evidence Submission step (WF16 Step 5) section gate so the
     * step auto-approves once all engagements in the programme are fully evidenced.
     */
    /**
     * Auto-submits a section when ALL controls within it have evidence submitted.
     * Called after each control evidence submission — no-op until the last control is done.
     * This removes the need for auditees to manually click "Submit section".
     */
    private void autoSubmitSectionIfComplete(Long sectionInstanceId, Long engagementId, Long submittedBy) {
        if (sectionInstanceId == null) return;
        try {
            AuditSectionInstance section = sectionInstanceRepository.findById(sectionInstanceId).orElse(null);
            if (section == null || section.getSubmittedAt() != null) return; // already submitted

            // Check all controls in this section have evidence
            List<AuditControlInstance> sectionControls =
                    controlInstanceRepository.findBySectionInstanceIdOrderByOrderNoAsc(sectionInstanceId);
            if (sectionControls.isEmpty()) return;

            boolean allDone = sectionControls.stream()
                    .allMatch(AuditControlInstance::isAuditeeEvidenceSubmitted);
            if (!allDone) return;

            // All controls done — auto-submit the section
            LocalDateTime now = LocalDateTime.now();
            section.setSubmittedAt(now);
            section.setSubmittedBy(submittedBy);
            if (section.getAuditeeSubmittedAt() == null) section.setAuditeeSubmittedAt(now);
            sectionInstanceRepository.save(section);

            log.info("[AUDIT-ENG-SERVICE] Section auto-submitted | sectionInstanceId={} | engagementId={} | by={}",
                    sectionInstanceId, engagementId, submittedBy);
        } catch (Exception ex) {
            log.warn("[AUDIT-ENG-SERVICE] Section auto-submit failed (non-fatal) | sectionInstanceId={} | {}",
                    sectionInstanceId, ex.getMessage());
        }
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
        java.util.List<Long> ids = controls.stream()
                .map(AuditControlInstance::getId).toList();
        java.util.Set<Long> withReused = evidenceLinkRepository
                .entityIdsWithAnyLink("AUDIT_CONTROL_INSTANCE", ids);
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
            java.util.List<Long> controlIds = allControls.stream()
                    .map(AuditControlInstance::getId).toList();
            java.util.Set<Long> controlsWithReusedEvidence = evidenceLinkRepository
                    .entityIdsWithAnyLink("AUDIT_CONTROL_INSTANCE", controlIds);

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

        // Evidence must exist before recording a test result.
        // Checks both the legacy boolean flag AND the evidence_links table
        // (which covers both MANUAL uploaded files and AUTOMATED integration evidence).
        boolean hasEvidence = control.isAuditeeEvidenceSubmitted()
                || evidenceLinkRepository.countAcceptedForEntity(
                "AUDIT_CONTROL_INSTANCE", controlInstanceId) > 0;
        if (!hasEvidence) {
            throw new BusinessException("EVIDENCE_NOT_SUBMITTED",
                    "Evidence has not been submitted for this control. " +
                            "The auditee must upload and submit evidence before the auditor can record a test result.");
        }

        control.setTestResult(req.getTestResult());
        control.setTestNotes(req.getTestNotes());
        control.setTestProcedure(req.getTestProcedure());
        control.setTestedAt(LocalDateTime.now());
        control.setTestedBy(testedBy);

        if (req.getFindingIssueId() != null) {
            control.setFindingLinked(true);
            control.setFindingIssueId(req.getFindingIssueId());
        }

        controlInstanceRepository.save(control);
        updateEngagementCounts(engagementId);

        // Fire section completion event so compound section gate advances the workflow
        // step when all of the auditor's controls have been tested.
        fireControlSectionEvent("TEST_RECORDED", controlInstanceId, engagementId, testedBy);

        log.info("[AUDIT] Control tested | controlInstanceId={} | result={}",
                controlInstanceId, req.getTestResult());
        return control;
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

    private void fireControlSectionEvent(String completionEvent,
                                         Long controlInstanceId,
                                         Long engagementId,
                                         Long userId) {
        try {
            Long taskInstanceId = resolveActorTaskInstanceId(engagementId, userId, completionEvent);
            if (taskInstanceId == null) return; // resolveActorTaskInstanceId already logged why

            eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                    completionEvent, taskInstanceId, userId, "AUDIT_CONTROL_INSTANCE", controlInstanceId));

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
            eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                    completionEvent, taskInstanceId, userId, "AUDIT_CONTROL_INSTANCE", controlInstanceId));
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
                .leadAuditorId(e.getLeadAuditorId()).ownerId(e.getOwnerId())
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