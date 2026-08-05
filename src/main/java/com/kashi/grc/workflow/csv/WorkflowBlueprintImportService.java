package com.kashi.grc.workflow.csv;

import com.kashi.grc.common.dto.CsvImportResult;
import com.kashi.grc.common.service.CsvImportService;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.workflow.domain.*;
import com.kashi.grc.workflow.enums.*;
import com.kashi.grc.workflow.repository.*;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WorkflowBlueprintImportService — extended step importer that fills the gaps
 * left by CsvImportService.importWorkflowSteps().
 *
 * ── WHAT THE BASE METHOD ALREADY HANDLES ─────────────────────────────────────
 * CsvImportService.importWorkflowSteps() handles:
 *   order, name, side, stepAction, approvalType, slaHours, automatedAction,
 *   assignerResolution, allowOverride, navKey, assignerNavKey,
 *   actorRoles, assignerRoles, observerRoles,
 *   sections (pipe-delimited: sectionKey|label|completionEvent|required|requiresAssignment|tracksItems)
 *
 * ── WHAT THIS CLASS ADDS ─────────────────────────────────────────────────────
 *   description            — step description (stored on WorkflowStep)
 *   isOptional             — step can be skipped if no items qualify
 *   isParallel             — step runs in parallel with adjacent steps
 *   autoApproveAssignerOnFill — NEW field needed on WorkflowStep domain (see migration below)
 *   stepUiOverrideJson     — inline JSON restricting tabs/fields/actions for actors on this step
 *   minApprovalsRequired   — already in base but not all paths set it correctly
 *
 * For sections (compound task gates), the pipe-delimited format is extended to 9 parts:
 *   sectionKey|label|completionEvent|required|requiresAssignment|tracksItems|sectionScreenKey|itemScreenKey|itemRefType
 * The § separator between multiple sections is preserved from the base implementation.
 *
 * Additionally, sectionUiJson and itemUiJson can be supplied as separate columns
 * (section_ui_json, item_ui_json) when they are too long for the pipe-delimited cell.
 * These are applied to the LAST section parsed in the sections column for that row.
 * If a step has multiple sections, supply them in separate rows using
 * type=SECTION_OVERRIDE (see below).
 *
 * ── CSV FORMAT ───────────────────────────────────────────────────────────────
 * Two row types share the same header:
 *
 *   type=STEP         → creates/updates a workflow step (all columns apply)
 *   type=SECTION_OVERRIDE → updates section-level UI JSON for a previously imported
 *                           step's section. Requires: order (to identify step),
 *                           section_key, section_ui_json, item_ui_json.
 *                           Useful when the JSON is large and won't fit a cell.
 *
 * FULL HEADER (add to the existing TEMPLATE format columns):
 *   order, name, description, side, stepAction, approvalType, minApprovalsRequired,
 *   slaHours, isOptional, isParallel, autoApproveAssignerOnFill,
 *   automatedAction, assignerResolution, allowOverride, navKey, assignerNavKey,
 *   stepUiOverrideJson, actorRoles, assignerRoles, observerRoles,
 *   sections, section_ui_json, item_ui_json
 *
 * The sections cell uses § to separate multiple section definitions, each pipe-delimited:
 *   sectionKey|label|completionEvent|required|requiresAssignment|tracksItems|sectionScreenKey|itemScreenKey|itemRefType
 *
 * ── SOC 2 EXAMPLE ROWS ───────────────────────────────────────────────────────
 * (See WorkflowBlueprintImportService.SOC2_EXAMPLE_CSV constant for a full example.)
 *
 * ── MIGRATION SQL ─────────────────────────────────────────────────────────────
 * Before using this service, apply:
 *
 *   ALTER TABLE workflow_steps
 *     ADD COLUMN auto_approve_assigner_on_fill TINYINT(1) NOT NULL DEFAULT 0
 *       COMMENT 'When true and step is FILL, assigner task is auto-approved so inbox stays clean';
 *
 * Note: description, is_optional, is_parallel, step_ui_override_json are already
 * present on workflow_steps per WorkflowStep.java. Only auto_approve_assigner_on_fill is new.
 *
 * ── RELATIONSHIP TO CsvImportService ─────────────────────────────────────────
 * This service is NOT a subclass — it cannot extend CsvImportService because that
 * class holds many unrelated repositories (assessment, questions, options). Instead,
 * this service re-implements importWorkflowSteps with the extended column set and
 * delegates role resolution to the same RoleRepository.
 *
 * WorkflowController.importSteps() should be updated to call this service
 * instead of CsvImportService.importWorkflowSteps():
 *
 *   Before: CsvImportResult result = csvImportService.importWorkflowSteps(file, id, resolvedTenantId);
 *   After:  CsvImportResult result = blueprintImportService.importSteps(file, id, resolvedTenantId);
 *
 * The endpoint signature is unchanged — only the delegate changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowBlueprintImportService {

    private final WorkflowRepository                 workflowRepository;
    private final WorkflowStepRepository             workflowStepRepository;
    private final WorkflowStepRoleRepository         workflowStepRoleRepository;
    private final WorkflowStepAssignerRoleRepository workflowStepAssignerRoleRepository;
    private final WorkflowStepObserverRoleRepository workflowStepObserverRoleRepository;
    private final WorkflowStepSectionRepository      workflowStepSectionRepository;
    private final RoleRepository                     roleRepository;
    private final EntityManager                      entityManager;

    // ── Public example CSV constant — referenced by WorkflowBlueprintDesigner ──

    /**
     * Minimal SOC 2 Engagement Lifecycle blueprint CSV.
     * Covers all 9 steps with extended fields.
     * Download via GET /v1/workflows/import-template.
     */
    public static final String SOC2_EXAMPLE_CSV =
            "type,order,name,description,side,stepAction,approvalType,minApprovalsRequired," +
                    "slaHours,isOptional,isParallel,autoApproveAssignerOnFill," +
                    "automatedAction,assignerResolution,allowOverride,navKey,assignerNavKey," +
                    "stepUiOverrideJson,actorRoles,assignerRoles,observerRoles,sections\n" +

                    "STEP,1,Engagement Setup,GRC Manager configures scope and assigns lead auditor," +
                    "ORGANIZATION,FILL,ANY_ONE,1,48,false,false,false,,INITIATOR,true," +
                    "soc2_engagements,soc2_engagements," +
                    "\"{\"\"visibleTabs\"\":[\"\"overview\"\",\"\"sections\"\",\"\"workflow\"\"]," +
                    "\"\"editableFields\"\":[\"\"name\"\",\"\"description\"\",\"\"leadAuditorId\"\",\"\"plannedStart\"\",\"\"plannedEnd\"\"]," +
                    "\"\"availableActions\"\":[\"\"APPROVE\"\",\"\"SEND_BACK\"\"]}\",GRC_MANAGER,,\n" +

                    "STEP,2,Assign Sections to Auditors,Lead auditor assigns each section to a specific auditor," +
                    "AUDITOR,ASSIGN,ANY_ONE,1,72,false,false,false,,PREVIOUS_ACTOR,true," +
                    "soc2_engagements,soc2_engagements," +
                    "\"{\"\"visibleTabs\"\":[\"\"sections\"\",\"\"workflow\"\"],\"\"availableActions\"\":[\"\"APPROVE\"\"]}\"," +
                    "LEAD_AUDITOR,GRC_MANAGER,," +
                    "SECTIONS_ASSIGNED_AUDITOR|Assign sections to auditors|SECTIONS_ASSIGNED_AUDITOR|true|true|true|audit_section_assignment_card|audit_section_assignment_item|AUDIT_SECTION_INSTANCE\n" +

                    "STEP,3,Assign Evidence Owners,Lead auditor assigns each section to the auditee responsible for evidence," +
                    "AUDITOR,ASSIGN,ANY_ONE,1,48,false,false,false,,PREVIOUS_ACTOR,true," +
                    "soc2_engagements,,," +
                    "AUDITOR_ROLE,GRC_MANAGER,," +
                    "SECTIONS_ASSIGNED_AUDITEE|Assign evidence owners|SECTIONS_ASSIGNED_AUDITEE|true|true|true|audit_section_auditee_card|audit_section_auditee_item|AUDIT_SECTION_INSTANCE\n" +

                    "STEP,4,Evidence Collection,Auditees upload evidence for assigned controls," +
                    "AUDITEE,FILL,ALL,1,720,false,false,true,,POOL,true," +
                    "task_inbox,,," +
                    "AUDITEE_CONTRIBUTOR,LEAD_AUDITOR,," +
                    "EVIDENCE_UPLOADED|Upload evidence|EVIDENCE_UPLOADED|true|false|true|control_evidence_card|control_evidence_item|AUDIT_CONTROL_INSTANCE\n" +

                    "STEP,5,Control Evaluation,Auditors review evidence and record test results," +
                    "AUDITOR,EVALUATE,ALL,1,336,false,false,false,,POOL,true," +
                    "soc2_engagements,,LEAD_AUDITOR," +
                    "AUDITOR_ROLE,LEAD_AUDITOR,," +
                    "CONTROLS_EVALUATED|Evaluate controls|TEST_RECORDED|true|false|true|test_execution_card|test_execution_item|AUDIT_CONTROL_INSTANCE\n" +

                    "STEP,6,Findings Remediation,Issue owners remediate open findings. Auditors validate.," +
                    "ORGANIZATION,FILL,ALL,1,336,true,false,false,,POOL,true," +
                    "soc2_findings,,," +
                    "GRC_MANAGER,LEAD_AUDITOR,," +
                    "FINDINGS_REMEDIATED|Remediate findings|FINDING_REMEDIATED|false|false|true|finding_remediation_card|finding_remediation_item|AUDIT_FINDING\n" +

                    "STEP,7,Draft Report Review,Lead auditor reviews overall results and writes opinion," +
                    "AUDITOR,REVIEW,ANY_ONE,1,168,false,false,false,,PREVIOUS_ACTOR,true," +
                    "soc2_engagements,,," +
                    "LEAD_AUDITOR,GRC_MANAGER,,\n" +

                    "STEP,8,Management Response,Management provides formal written response to each finding," +
                    "ORGANIZATION,FILL,ANY_ONE,1,168,false,false,false,,INITIATOR,true," +
                    "soc2_findings,,,GRC_MANAGER,,\n" +

                    "STEP,9,Final Approval,CISO reviews complete engagement and closes it," +
                    "ORGANIZATION,APPROVE,ANY_ONE,1,72,false,false,false,,POOL,true," +
                    "soc2_engagements,,,GRC_MANAGER,,\n";

    // ── Column indices — resolved from header at runtime ─────────────────────

    // All column names are normalised (lowercase, spaces→underscores) before lookup.
    // Supports both camelCase (stepAction) and snake_case (step_action) headers.

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Import workflow steps from CSV into an existing blueprint.
     *
     * Extends CsvImportService.importWorkflowSteps() with:
     *   description, isOptional, isParallel, autoApproveAssignerOnFill,
     *   stepUiOverrideJson, and extended section columns
     *   (sectionScreenKey, itemScreenKey, itemRefType, sectionUiJson, itemUiJson).
     *
     * @param file       Uploaded .csv file
     * @param workflowId Target workflow blueprint ID (must exist and be in DRAFT status)
     * @param tenantId   Tenant used for role name resolution (pass org tenantId, not Platform Admin's)
     */
    @Transactional
    public CsvImportResult importSteps(MultipartFile file, Long workflowId, Long tenantId) {
        log.info("[WF-BP-IMPORT] Starting | workflowId={} | file={} | tenantId={}",
                workflowId, file != null ? file.getOriginalFilename() : "null", tenantId);

        var result = CsvImportResult.builder().totalRows(0).successCount(0).failureCount(0);

        // ── File validation ───────────────────────────────────────────────────
        if (file == null || file.isEmpty())
            return result.fatalError(true).summary("No file uploaded").build();
        if (!Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase().endsWith(".csv"))
            return result.fatalError(true).summary("Only .csv files are accepted").build();

        workflowRepository.findById(workflowId)
                .orElse(null);
        // Note: not aborting if workflow is ACTIVE — Platform Admin may intentionally
        // re-import to bulk-update steps. The engine prevents import if instances are running.

        // ── Parse CSV ────────────────────────────────────────────────────────
        List<String[]> allRows;
        try { allRows = readAllRows(file); }
        catch (Exception e) {
            return result.fatalError(true)
                    .summary("Failed to parse CSV: " + e.getMessage()).build();
        }
        if (allRows.size() < 2)
            return result.fatalError(true)
                    .summary("CSV must have a header row and at least one data row").build();

        // ── Build column index map ────────────────────────────────────────────
        String[] header = allRows.get(0);
        Map<String, Integer> ci = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++)
            ci.put(norm(header[i]), i);

        // ── Resolve all column indices ─────────────────────────────────────
        int ciType    = ci.getOrDefault("type",     -1);
        int ciOrder   = firstOf(ci, "order", "step_order");
        int ciName    = ci.getOrDefault("name",     -1);
        int ciDesc    = firstOf(ci, "description",  "desc");
        int ciSide    = ci.getOrDefault("side",     -1);
        int ciAct     = firstOf(ci, "stepaction",   "step_action", "action");
        int ciAppr    = firstOf(ci, "approvaltype", "approval_type");
        int ciMinA    = firstOf(ci, "minapprovalrequired", "minapprovals", "min_approvals_required");
        int ciSla     = firstOf(ci, "slahours",     "sla_hours");
        int ciOpt     = firstOf(ci, "isoptional",   "is_optional", "optional");
        int ciPar     = firstOf(ci, "isparallel",   "is_parallel", "parallel");
        int ciAutoApp = firstOf(ci, "autoapproveassigneronfill", "auto_approve_assigner_on_fill");
        int ciAutoAct = firstOf(ci, "automatedaction", "automated_action");
        int ciRes     = firstOf(ci, "assignerresolution", "assigner_resolution");
        int ciAllow   = firstOf(ci, "allowoverride", "allow_override");
        int ciNavK    = firstOf(ci, "navkey",        "nav_key");
        int ciAssNavK = firstOf(ci, "assignernavkey","assigner_nav_key");
        int ciUiOvr   = firstOf(ci, "stepuioverridejson", "step_ui_override_json", "uioverride");
        int ciARol    = firstOf(ci, "actorroles",   "actor_roles", "roles");
        int ciAssR    = firstOf(ci, "assignerroles","assigner_roles");
        int ciObsR    = firstOf(ci, "observerroles","observer_roles");
        int ciSecs    = firstOf(ci, "sections",     "compound_sections");
        int ciSecUi   = firstOf(ci, "sectionuijson","section_ui_json");
        int ciItemUi  = firstOf(ci, "itemuijson",   "item_ui_json");
        int ciSecKey  = firstOf(ci, "section_key",  "sectionkey");   // for SECTION_OVERRIDE rows

        log.info("[WF-BP-IMPORT] Columns resolved | cols={}", ci.keySet());

        // ── Load roles for name → ID resolution ──────────────────────────────
        // Same logic as CsvImportService: global roles (tenantId=null) first,
        // org-specific last so org IDs win on name clash.
        Map<String, Long> roleMap = new HashMap<>();
        roleRepository.findAllForTenant(tenantId).stream()
                .sorted(Comparator.comparing(r -> r.getTenantId() == null ? 0L : r.getTenantId()))
                .forEach(r -> roleMap.put(r.getName().toLowerCase().replace(" ", "_"), r.getId()));
        log.info("[WF-BP-IMPORT] Loaded {} roles | tenantId={}", roleMap.size(), tenantId);

        List<CsvImportResult.ImportLogEntry> importLog = new ArrayList<>();
        List<String[]> dataRows = allRows.subList(1, allRows.size());
        int ok = 0, fail = 0;

        // Pre-fetch every existing step for this workflow ONCE — was previously
        // re-fetched (findByWorkflowIdOrderByStepOrderAsc, then filtered in
        // Java for the matching stepOrder) on EVERY row, in both the STEP and
        // SECTION_OVERRIDE cases. For a 20-step blueprint that was up to 20
        // queries fetching all 20 rows each. Kept as a mutable map — updated
        // below whenever a step is created/updated — so later rows in the
        // same import see steps created earlier in the same pass, exactly as
        // the original per-row re-query would have.
        Map<Integer, WorkflowStep> stepsByOrder = workflowStepRepository
                .findByWorkflowIdOrderByStepOrderAsc(workflowId).stream()
                .collect(Collectors.toMap(WorkflowStep::getStepOrder, s -> s, (a, b) -> a));

        // Track stepOrders seen so we can delete orphans at the end
        Set<Integer> csvOrders = new LinkedHashSet<>();

        for (int i = 0; i < dataRows.size(); i++) {
            String[] row    = dataRows.get(i);
            int      lineNo = i + 2;

            // ── Row type ─────────────────────────────────────────────────────
            String rowType = get(row, ciType).toUpperCase();
            if (rowType.isEmpty()) rowType = "STEP"; // default when type column absent

            switch (rowType) {

                // ── STEP ─────────────────────────────────────────────────────
                case "STEP" -> {
                    String name = get(row, ciName);
                    if (name.isEmpty()) {
                        importLog.add(entry("Row " + lineNo + ": name required — skipped", "ERROR"));
                        fail++; continue;
                    }

                    int     stepOrder = toInt(get(row, ciOrder), i + 1);
                    String  sideRaw   = normSide(get(row, ciSide));
                    csvOrders.add(stepOrder);

                    StepAction stepAction = parseEnum(StepAction.class,
                            get(row, ciAct), null, importLog, lineNo, "stepAction");
                    ApprovalType approvalType = parseEnum(ApprovalType.class,
                            get(row, ciAppr), ApprovalType.ANY_ONE, importLog, lineNo, "approvalType");
                    AssignerResolution resolution = parseEnum(AssignerResolution.class,
                            get(row, ciRes), AssignerResolution.POOL, importLog, lineNo, "assignerResolution");

                    Integer slaHours      = toInteger(get(row, ciSla));
                    int     minAppr       = toInt(get(row, ciMinA), 1);
                    boolean isOptional    = toBool(get(row, ciOpt), false);
                    boolean isParallel    = toBool(get(row, ciPar), false);
                    boolean autoApprove   = toBool(get(row, ciAutoApp), false);
                    boolean allowOverride = !isFalse(get(row, ciAllow));

                    String description  = get(row, ciDesc);
                    String autoAct      = nullIfBlank(get(row, ciAutoAct));
                    String navKey       = nullIfBlank(get(row, ciNavK));
                    String assignerNavK = nullIfBlank(get(row, ciAssNavK));
                    String uiOverride   = nullIfBlank(get(row, ciUiOvr));

                    // ── Upsert step ───────────────────────────────────────────
                    final int fOrder = stepOrder;
                    WorkflowStep step = stepsByOrder.getOrDefault(fOrder,
                            WorkflowStep.builder().workflowId(workflowId).build());

                    boolean created = step.getId() == null;

                    step.setName(name);
                    step.setDescription(description.isEmpty() ? null : description);
                    step.setStepOrder(stepOrder);
                    step.setSide(sideRaw);
                    step.setStepAction(stepAction);
                    step.setApprovalType(approvalType);
                    step.setAssignerResolution(resolution);
                    step.setMinApprovalsRequired(minAppr);
                    step.setSlaHours(slaHours);
                    step.setOptional(isOptional);
                    step.setParallel(isParallel);
                    step.setAutomatedAction(autoAct);
                    step.setNavKey(navKey);
                    step.setAssignerNavKey(assignerNavK);
                    step.setAllowOverride(allowOverride);
                    step.setStepUiOverrideJson(uiOverride);
                    // autoApproveAssignerOnFill — set only if field exists on domain
                    // Requires: ALTER TABLE workflow_steps ADD COLUMN auto_approve_assigner_on_fill TINYINT(1) NOT NULL DEFAULT 0
                    setAutoApprove(step, autoApprove);

                    step = workflowStepRepository.save(step);
                    stepsByOrder.put(fOrder, step);
                    final Long stepId = step.getId();

                    // ── Roles ────────────────────────────────────────────────
                    if (ciARol >= 0) {
                        workflowStepRoleRepository.deleteByStepId(stepId);
                        entityManager.flush(); entityManager.clear();
                        resolveRoles(get(row, ciARol), roleMap, importLog, lineNo, "actorRole")
                                .forEach(rid -> workflowStepRoleRepository.save(
                                        WorkflowStepRole.builder().stepId(stepId).roleId(rid).build()));
                    }
                    if (ciAssR >= 0) {
                        workflowStepAssignerRoleRepository.deleteByStepId(stepId);
                        entityManager.flush(); entityManager.clear();
                        resolveRoles(get(row, ciAssR), roleMap, importLog, lineNo, "assignerRole")
                                .forEach(rid -> workflowStepAssignerRoleRepository.save(
                                        WorkflowStepAssignerRole.builder().stepId(stepId).roleId(rid).build()));
                    }
                    if (ciObsR >= 0) {
                        workflowStepObserverRoleRepository.deleteByStepId(stepId);
                        entityManager.flush(); entityManager.clear();
                        resolveRoles(get(row, ciObsR), roleMap, importLog, lineNo, "observerRole")
                                .forEach(rid -> workflowStepObserverRoleRepository.save(
                                        WorkflowStepObserverRole.builder().stepId(stepId).roleId(rid).build()));
                    }

                    // ── Sections (extended 9-part pipe format) ────────────────
                    if (ciSecs >= 0) {
                        String sectionsRaw  = get(row, ciSecs);
                        String lastSecUiJson  = nullIfBlank(get(row, ciSecUi));
                        String lastItemUiJson = nullIfBlank(get(row, ciItemUi));

                        workflowStepSectionRepository.deleteByStepId(stepId);
                        entityManager.flush(); entityManager.clear();
                        if (!sectionsRaw.isBlank() && !sectionsRaw.equalsIgnoreCase("NULL")) {
                            String[] defs = sectionsRaw.split("§");
                            int secOrder  = 0;
                            for (int di = 0; di < defs.length; di++) {
                                String[] p = defs[di].trim().split("\\|", -1);
                                if (p.length < 3) continue;

                                // Parts: 0=sectionKey 1=label 2=completionEvent 3=required
                                //        4=requiresAssignment 5=tracksItems
                                //        6=sectionScreenKey 7=itemScreenKey 8=itemRefType
                                String sKey    = p[0].trim();
                                String sLabel  = p.length > 1 ? p[1].trim() : sKey;
                                String sEvent  = p.length > 2 ? p[2].trim() : sKey + "_DONE";
                                boolean sReq   = p.length <= 3 || !"false".equalsIgnoreCase(p[3].trim());
                                boolean sAssign= p.length > 4 && "true".equalsIgnoreCase(p[4].trim());
                                boolean sItems = p.length > 5 && "true".equalsIgnoreCase(p[5].trim());
                                String sScreenKey  = p.length > 6 ? nullIfBlank(p[6].trim()) : null;
                                String sItemKey    = p.length > 7 ? nullIfBlank(p[7].trim()) : null;
                                String sItemRefType= p.length > 8 ? nullIfBlank(p[8].trim()) : null;

                                // sectionUiJson / itemUiJson: only applied to last section in cell
                                // (multi-section rows with per-section JSON should use SECTION_OVERRIDE rows)
                                String sSecUiJson  = (di == defs.length - 1) ? lastSecUiJson  : null;
                                String sItemUiJson = (di == defs.length - 1) ? lastItemUiJson : null;

                                if (sKey.isEmpty() || sEvent.isEmpty()) continue;
                                secOrder++;

                                workflowStepSectionRepository.save(
                                        WorkflowStepSection.builder()
                                                .stepId(stepId)
                                                .sectionKey(sKey)
                                                .sectionOrder(secOrder)
                                                .label(sLabel)
                                                .completionEvent(sEvent)
                                                .required(sReq)
                                                .requiresAssignment(sAssign)
                                                .tracksItems(sItems)
                                                .sectionScreenKey(sScreenKey)
                                                .itemScreenKey(sItemKey)
                                                .itemRefType(sItemRefType)
                                                .sectionUiJson(sSecUiJson)
                                                .itemUiJson(sItemUiJson)
                                                .build());
                            }
                            log.info("[WF-BP-IMPORT] Step {}: {} section(s) persisted | stepId={}",
                                    stepOrder, secOrder, stepId);
                        }
                    }

                    importLog.add(entry(String.format(
                            "Step %d: \"%s\" [%s/%s/%s] optional=%b autoApprove=%b id=%d [%s]",
                            stepOrder, name, sideRaw,
                            stepAction != null ? stepAction.name() : "—",
                            resolution.name(), isOptional, autoApprove,
                            stepId, created ? "created" : "updated"), "SUCCESS"));
                    ok++;
                }

                // ── SECTION_OVERRIDE — patch sectionUiJson/itemUiJson on an existing section ──
                case "SECTION_OVERRIDE" -> {
                    int    stepOrder  = toInt(get(row, ciOrder), -1);
                    String sectionKey = get(row, ciSecKey);
                    if (stepOrder < 0 || sectionKey.isEmpty()) {
                        importLog.add(entry("Row " + lineNo +
                                ": SECTION_OVERRIDE needs order + section_key — skipped", "ERROR"));
                        fail++; continue;
                    }

                    final int fOrder = stepOrder;
                    WorkflowStep step = stepsByOrder.get(fOrder);
                    if (step == null) {
                        importLog.add(entry("Row " + lineNo +
                                ": SECTION_OVERRIDE — no step at order=" + stepOrder + " — skipped", "WARNING"));
                        continue;
                    }

                    workflowStepSectionRepository.findByStepIdOrderBySectionOrderAsc(step.getId())
                            .stream().filter(s -> s.getSectionKey().equalsIgnoreCase(sectionKey))
                            .findFirst().ifPresentOrElse(sec -> {
                                String secUi  = nullIfBlank(get(row, ciSecUi));
                                String itemUi = nullIfBlank(get(row, ciItemUi));
                                if (secUi  != null) sec.setSectionUiJson(secUi);
                                if (itemUi != null) sec.setItemUiJson(itemUi);
                                workflowStepSectionRepository.save(sec);
                                importLog.add(entry(String.format(
                                        "SECTION_OVERRIDE: step=%d sectionKey=%s updated",
                                        stepOrder, sectionKey), "SUCCESS"));
                            }, () -> importLog.add(entry(
                                    "Row " + lineNo + ": SECTION_OVERRIDE — sectionKey=" +
                                            sectionKey + " not found on step " + stepOrder, "WARNING")));
                    ok++;
                }

                default -> {
                    if (!rowType.isEmpty())
                        importLog.add(entry("Row " + lineNo + ": unknown type \"" + rowType + "\" — skipped",
                                "WARNING"));
                }
            }
        }

        // ── Remove orphan steps (step orders in DB but not in CSV) ────────────
        // Same behaviour as CsvImportService.importWorkflowSteps(). Reuses
        // stepsByOrder instead of a third full re-fetch — it already reflects
        // the complete current state (pre-existing steps untouched by this
        // CSV stayed in the map from the initial load; touched ones were
        // updated in place as each row was processed).
        int deleted = 0;
        if (!csvOrders.isEmpty()) {
            for (WorkflowStep s : stepsByOrder.values()) {
                if (!csvOrders.contains(s.getStepOrder())) {
                    workflowStepRoleRepository.deleteByStepId(s.getId());
                    workflowStepAssignerRoleRepository.deleteByStepId(s.getId());
                    workflowStepObserverRoleRepository.deleteByStepId(s.getId());
                    workflowStepSectionRepository.deleteByStepId(s.getId());
                    entityManager.flush(); entityManager.clear();
                    workflowStepRepository.delete(s);
                    importLog.add(entry("Removed orphan step (order=" + s.getStepOrder() +
                            " name=\"" + s.getName() + "\") — not in CSV", "INFO"));
                    deleted++;
                }
            }
        }

        String summary = String.format(
                "Blueprint step import: %d imported | %d failed | %d removed | %d total rows",
                ok, fail, deleted, dataRows.size());
        log.info("[WF-BP-IMPORT] {}", summary);

        return result.fatalError(false).summary(summary)
                .totalRows(dataRows.size()).successCount(ok).failureCount(fail)
                .log(importLog).createdEntityId(workflowId).createdEntityType("workflowId")
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<Long> resolveRoles(String namesStr, Map<String, Long> roleMap,
                                    List<CsvImportResult.ImportLogEntry> log2,
                                    int lineNo, String label) {
        List<Long> ids = new ArrayList<>();
        if (namesStr == null || namesStr.isBlank()) return ids;
        for (String n : namesStr.split(";")) {
            String key = n.trim().toLowerCase().replace(" ", "_");
            if (key.isEmpty()) continue;
            Long id = roleMap.get(key);
            if (id != null) ids.add(id);
            else log2.add(entry("Row " + lineNo + ": unknown " + label + " \"" + n.trim() + "\" — skipped",
                    "WARNING"));
        }
        return ids;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E defaultVal,
                                            List<CsvImportResult.ImportLogEntry> log2,
                                            int lineNo, String fieldName) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("NULL")) return defaultVal;
        try { return Enum.valueOf(type, raw.toUpperCase()); }
        catch (IllegalArgumentException e) {
            log2.add(entry("Row " + lineNo + ": unknown " + fieldName + " \"" + raw +
                    "\" — using default " + (defaultVal != null ? defaultVal.name() : "null"), "WARNING"));
            return defaultVal;
        }
    }

    private String normSide(String raw) {
        String s = raw.toUpperCase();
        return Set.of("ORGANIZATION","VENDOR","AUDITOR","AUDITEE","SYSTEM").contains(s)
                ? s : "ORGANIZATION";
    }

    private String get(String[] row, int idx) {
        return (idx >= 0 && idx < row.length && row[idx] != null) ? row[idx].strip() : "";
    }

    private int toInt(String s, int def) {
        try { return s.isEmpty() ? def : Integer.parseInt(s); }
        catch (NumberFormatException e) { return def; }
    }

    private Integer toInteger(String s) {
        try { return (s == null || s.isBlank() || s.equalsIgnoreCase("NULL")) ? null : Integer.parseInt(s); }
        catch (NumberFormatException e) { return null; }
    }

    private boolean toBool(String s, boolean def) {
        if (s == null || s.isBlank()) return def;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private boolean isFalse(String s) {
        return "false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s);
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank() || s.equalsIgnoreCase("NULL")) ? null : s;
    }

    private String norm(String s) {
        return s == null ? "" : s.strip().toLowerCase().replace(" ", "_");
    }

    private int firstOf(Map<String, Integer> ci, String... keys) {
        for (String k : keys) { Integer v = ci.get(k); if (v != null) return v; }
        return -1;
    }

    /**
     * Sets autoApproveAssignerOnFill via reflection so this service compiles even
     * before the migration SQL has been run and the domain field added.
     * Once the domain field is confirmed present, replace with a direct setter:
     *   step.setAutoApproveAssignerOnFill(autoApprove);
     */
    private void setAutoApprove(WorkflowStep step, boolean value) {
        try {
            var m = step.getClass().getMethod("setAutoApproveAssignerOnFill", boolean.class);
            m.invoke(step, value);
        } catch (NoSuchMethodException ignored) {
            // Migration not yet run — field absent. No-op: the value defaults to false in DB.
            log.debug("[WF-BP-IMPORT] autoApproveAssignerOnFill setter not found — migration pending");
        } catch (Exception e) {
            log.warn("[WF-BP-IMPORT] Could not set autoApproveAssignerOnFill: {}", e.getMessage());
        }
    }

    private CsvImportResult.ImportLogEntry entry(String message, String status) {
        return CsvImportResult.ImportLogEntry.builder().message(message).status(status).build();
    }

    private List<String[]> readAllRows(MultipartFile file) throws IOException, CsvValidationException {
        List<String[]> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String[] row;
            while ((row = reader.readNext()) != null) {
                boolean blank = Arrays.stream(row).allMatch(s -> s == null || s.isBlank());
                if (!blank) rows.add(row);
            }
        }
        return rows;
    }
}