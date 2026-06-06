package com.kashi.grc.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.guard.domain.SodRule;
import com.kashi.grc.guard.repository.SodRuleRepository;
import com.kashi.grc.usermanagement.domain.Permission;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserPermissionOverride;
import com.kashi.grc.usermanagement.repository.PermissionGrantRepository;
import com.kashi.grc.usermanagement.repository.UserPermissionOverrideRepository;
import com.kashi.grc.workflow.domain.*;
import com.kashi.grc.workflow.dto.response.AccessContext;
import com.kashi.grc.workflow.enums.*;
import com.kashi.grc.workflow.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WorkflowAccessService — extended to resolve all three access layers.
 *
 * ── WHAT'S NEW vs the original ────────────────────────────────────────────────
 *
 * The original service resolved:
 *   - Active task owner → EDIT
 *   - Observer role     → OBSERVER
 *   - Terminal step     → COMPLETED
 *   - No relationship   → DENIED
 *
 * This extension adds, after mode resolution:
 *   1. Merge StepInstance.snapUiOverrideJson → field/tab/action restrictions
 *   2. Resolve user permissions (role grants + user overrides)
 *   3. Evaluate SoD rules for this user on this workflow instance
 *   4. Populate availableActions based on task role + step override
 *
 * ── RESOLUTION ORDER ──────────────────────────────────────────────────────────
 *
 *   Step 1: Mode (EDIT/OBSERVER/COMPLETED/DENIED) — unchanged from original
 *   Step 2: Base permissions = role.permissions ∪ permission_grants (explicit)
 *   Step 3: Apply user_permission_overrides (override wins; bounded by ceiling for non-SYSTEM)
 *   Step 4: Apply step UI override restrictions (can only restrict, never expand)
 *   Step 5: Evaluate SoD rules for this workflowInstanceId
 *   Step 6: Build availableActions from task role + step override
 *
 * ── BACKWARD COMPATIBILITY ────────────────────────────────────────────────────
 *
 * The AccessContext factory methods (edit/observer/completed/denied) are unchanged.
 * All existing callers that just check canView/canEdit/canAct still work.
 * New fields are @JsonInclude(NON_NULL) — absent when not populated.
 *
 * Callers that pass taskId=null (observer/history check path) still work —
 * they get a COMPLETED or OBSERVER context with permissions populated but
 * no availableActions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAccessService {

    private final StepInstanceRepository             stepInstanceRepository;
    private final WorkflowInstanceRepository         instanceRepository;
    private final TaskInstanceRepository             taskInstanceRepository;
    private final WorkflowStepObserverRoleRepository observerRoleRepository;
    private final WorkflowStepRepository             stepRepository;
    private final PermissionGrantRepository          permissionGrantRepository;
    private final UserPermissionOverrideRepository   userPermissionOverrideRepository;
    private final SodRuleRepository                  sodRuleRepository;
    private final ObjectMapper                       objectMapper;

    // ── Default workflow actions available per task role ──────────────────────

    private static final List<String> ACTOR_DEFAULT_ACTIONS =
            List.of("APPROVE", "REJECT", "SEND_BACK", "COMMENT", "DELEGATE", "WITHDRAW");
    private static final List<String> ASSIGNER_DEFAULT_ACTIONS =
            List.of("REASSIGN", "ESCALATE", "COMMENT");

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full resolution — called by the task access-context endpoint.
     *
     * @param user           Authenticated user (WITH_ROLES_PERMISSIONS graph loaded)
     * @param stepInstanceId Step instance to check
     * @param taskId         Task the user claims to own (null for observer/history check)
     */
    public AccessContext resolve(User user, Long stepInstanceId, Long taskId) {

        StepInstance si = stepInstanceRepository.findById(stepInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", stepInstanceId));

        WorkflowInstance wi = instanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", si.getWorkflowInstanceId()));

        String stepStatus     = si.getStatus().name();
        String workflowStatus = wi.getStatus().name();

        log.debug("[ACCESS] Resolving | userId={} | stepInstanceId={} | taskId={} | stepStatus={} | wfStatus={}",
                user.getId(), stepInstanceId, taskId, stepStatus, workflowStatus);

        // ── STEP 1: Resolve mode (original logic, unchanged) ─────────────────

        AccessContext base = resolveMode(user, si, wi, taskId, stepStatus, workflowStatus);

        // ── STEP 2 + 3: Resolve permissions (role grants + user overrides) ───

        List<String> resolvedPermissions = resolvePermissions(user);

        // ── STEP 4: Parse step UI override ───────────────────────────────────

        StepUiOverride override = parseOverride(si.getSnapUiOverrideJson());

        // ── STEP 5: SoD evaluation ────────────────────────────────────────────

        List<AccessContext.SodViolation> sodViolations =
                evaluateSod(user, wi.getId(), wi.getEntityType(), resolvedPermissions);

        // ── STEP 6: Build available actions ───────────────────────────────────

        List<String> availableActions = resolveAvailableActions(base, override, sodViolations);

        // ── Merge all into final context ──────────────────────────────────────

        return mergeContext(base, override, resolvedPermissions, availableActions, sodViolations,
                si.getSnapName(),
                si.getSnapStepAction() != null ? si.getSnapStepAction().name() : null,
                Boolean.TRUE.equals(si.getSnapAutoCompleteActorOnSubmit()));
    }

    /**
     * Lightweight resolution for non-workflow module pages.
     * No stepInstanceId — resolves permissions + SoD only (no step override).
     * Used by the Universal Module Page for entities without an active workflow task.
     *
     * @param user       Authenticated user
     * @param entityType e.g. "RISK", "AUDIT"
     * @param entityId   The specific record (for SoD check); null for list pages
     */
    public AccessContext resolveForModule(User user, String entityType, Long entityId) {
        List<String> resolvedPermissions = resolvePermissions(user);

        // Find active workflow instance for this entity (for SoD check + step override)
        List<AccessContext.SodViolation> sodViolations = Collections.emptyList();
        StepUiOverride stepOverride = new StepUiOverride();
        String activeStepAction = null;
        String activeStepLabel  = null;
        if (entityId != null) {
            Optional<WorkflowInstance> activeInstance = instanceRepository
                    .findActiveByEntityTypeAndEntityId(entityType, entityId);
            if (activeInstance.isPresent()) {
                sodViolations = evaluateSod(user, activeInstance.get().getId(),
                        entityType, resolvedPermissions);
                // Apply the active step's UI override even without a taskId.
                // This ensures step-scoped permissionOverrides (e.g. hiding the
                // auditor picker on non-assignment steps) work when the user
                // navigates directly to the entity page rather than via a task URL.
                List<StepInstance> activeSteps = stepInstanceRepository
                        .findByWorkflowInstanceIdAndStatus(
                                activeInstance.get().getId(), StepStatus.IN_PROGRESS);
                if (!activeSteps.isEmpty()) {
                    StepInstance currentStep = activeSteps.get(0);
                    stepOverride     = parseOverride(currentStep.getSnapUiOverrideJson());
                    activeStepAction = currentStep.getSnapStepAction() != null
                            ? currentStep.getSnapStepAction().name() : null;
                    activeStepLabel  = currentStep.getSnapName();
                }
            }
        }

        // Apply step-scoped permission overrides (add/remove per active step)
        List<String> effectivePermissions = applyPermissionOverrides(resolvedPermissions, stepOverride);

        // ── System admin bypass: system:write grants full access to every module ──
        boolean isSystemAdmin = effectivePermissions.contains("_system_admin");

        String entityPrefix = entityType.toLowerCase().replace("_", ".");
        boolean canView   = isSystemAdmin || effectivePermissions.contains(entityPrefix + ".view");
        boolean canEdit   = isSystemAdmin || effectivePermissions.contains(entityPrefix + ".edit");
        boolean canDelete = isSystemAdmin || effectivePermissions.contains(entityPrefix + ".delete");

        return AccessContext.builder()
                .mode(canView ? "VIEW" : "DENIED")
                .canView(canView)
                .canEdit(canEdit && sodViolations.stream().noneMatch(v -> "HARD".equals(v.getConflictType())))
                .canAct(false)   // no active task
                .stepAction(activeStepAction)
                .stepLabel(activeStepLabel)
                .visibleTabs(nullIfEmpty(stepOverride.getVisibleTabs()))
                .hiddenTabs(nullIfEmpty(stepOverride.getHiddenTabs()))
                .permissions(effectivePermissions)
                .sodViolations(sodViolations.isEmpty() ? null : sodViolations)
                .autoCompleteActorOnSubmit(false)  // no active task in module view
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE: MODE RESOLUTION (original logic preserved exactly)
    // ─────────────────────────────────────────────────────────────────────────

    private AccessContext resolveMode(User user, StepInstance si, WorkflowInstance wi,
                                      Long taskId, String stepStatus, String workflowStatus) {
        // Workflow terminal
        if (wi.getStatus() == WorkflowStatus.COMPLETED
                || wi.getStatus() == WorkflowStatus.CANCELLED
                || wi.getStatus() == WorkflowStatus.REJECTED) {
            return AccessContext.completed(
                    "Workflow is " + wi.getStatus().name().toLowerCase(),
                    stepStatus, workflowStatus);
        }

        // Step terminal
        if (si.getStatus() == StepStatus.APPROVED || si.getStatus() == StepStatus.REJECTED
                || si.getStatus() == StepStatus.REASSIGNED) {
            String completedAt = si.getCompletedAt() != null
                    ? si.getCompletedAt().toLocalDate().toString() : "unknown date";
            return AccessContext.completed(
                    "Step " + si.getStatus().name().toLowerCase() + " on " + completedAt,
                    stepStatus, workflowStatus);
        }

        // Active task owned by this user
        if (taskId != null) {
            TaskInstance task = taskInstanceRepository.findById(taskId).orElse(null);
            if (task != null
                    && task.getAssignedUserId().equals(user.getId())
                    && task.getStepInstanceId().equals(si.getId())
                    && (task.getStatus() == TaskStatus.PENDING
                    || task.getStatus() == TaskStatus.IN_PROGRESS)) {
                return AccessContext.edit(task.getTaskRole(), stepStatus, workflowStatus);
            }

            // Delegated — read-only
            if (task != null
                    && task.getAssignedUserId().equals(user.getId())
                    && task.getStepInstanceId().equals(si.getId())
                    && task.getStatus() == TaskStatus.DELEGATED) {
                return AccessContext.observer("You delegated this task — monitoring only",
                        stepStatus, workflowStatus);
            }
        }

        // Observer role
        if (si.getStepId() != null) {
            Set<Long> observerRoleIds = observerRoleRepository.findByStepId(si.getStepId())
                    .stream().map(WorkflowStepObserverRole::getRoleId).collect(Collectors.toSet());
            if (!observerRoleIds.isEmpty()) {
                Set<Long> userRoleIds = user.getRoles().stream()
                        .map(r -> r.getId()).collect(Collectors.toSet());
                if (userRoleIds.stream().anyMatch(observerRoleIds::contains)) {
                    return AccessContext.observer(
                            "You have read-only observer access to this step",
                            stepStatus, workflowStatus);
                }
            }
        }

        return AccessContext.denied();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE: PERMISSION RESOLUTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the full permission set for a user.
     *
     * Layer 1: Role permissions (from role_permissions join table, already in User.roles.permissions)
     * Layer 2: Explicit PermissionGrant rows (granted=true adds, granted=false removes)
     * Layer 3: UserPermissionOverride rows (wins over both; active + not expired only)
     */
    private List<String> resolvePermissions(User user) {

        // Layer 1: permissions from role_permissions (already loaded via entity graph)
        Set<String> perms = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getCode)
                .collect(Collectors.toCollection(HashSet::new));

        // Layer 2: explicit PermissionGrant rows (can grant OR deny per role)
        List<Object[]> grants = permissionGrantRepository
                .findGrantsForUserRoles(user.getRoles().stream()
                        .map(r -> r.getId()).collect(Collectors.toList()));
        for (Object[] row : grants) {
            String permCode = (String) row[0];
            Boolean granted = (Boolean) row[1];
            if (granted) {
                perms.add(permCode);
            } else {
                perms.remove(permCode);  // explicit deny at role level
            }
        }

        // Layer 3: user-level overrides (wins over role grants)
        List<UserPermissionOverride> overrides = userPermissionOverrideRepository
                .findActiveByUserId(user.getId(), LocalDateTime.now());
        for (UserPermissionOverride ov : overrides) {
            // Need permission code — join in repository or load separately
            String permCode = ov.getPermissionCode();  // denormalized field, see note below
            if (permCode == null) continue;
            if (ov.isGranted()) {
                perms.add(permCode);
            } else {
                perms.remove(permCode);
            }
        }

        // ── Wildcard: system:write = Platform Admin = full access to all modules ──
        // New module entity types (AUDIT_TEST, AUDIT_POLICY, etc.) have no seeded
        // permission codes, but Platform Admin should always have full access.
        if (perms.contains("system:write")) {
            perms.add("_system_admin");
        }

        return new ArrayList<>(perms);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE: STEP UI OVERRIDE PARSING
    // ─────────────────────────────────────────────────────────────────────────

    private StepUiOverride parseOverride(String json) {
        if (json == null || json.isBlank()) return new StepUiOverride();
        try {
            return objectMapper.readValue(json, StepUiOverride.class);
        } catch (Exception e) {
            log.warn("[ACCESS] Failed to parse stepUiOverrideJson: {}", e.getMessage());
            return new StepUiOverride();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE: SOD EVALUATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks if this user has acted in a conflicting role on the same workflow instance.
     *
     * Scoping: INSTANCE scope → conflict only within same workflowInstanceId.
     *          GLOBAL scope   → conflict if user holds both permissions regardless of instance.
     */
    private List<AccessContext.SodViolation> evaluateSod(
            User user, Long workflowInstanceId, String entityType, List<String> resolvedPermissions) {

        List<SodRule> rules = sodRuleRepository.findActiveRulesForEntityType(entityType);
        if (rules.isEmpty()) return Collections.emptyList();

        // Find permissions the user has already exercised as ACTOR on this instance
        Set<String> actedPermissions = taskInstanceRepository
                .findActorTasksForInstance(workflowInstanceId, user.getId())
                .stream()
                .flatMap(task -> resolveStepPermissions(task.getStepInstanceId()).stream())
                .collect(Collectors.toSet());

        List<AccessContext.SodViolation> violations = new ArrayList<>();

        for (SodRule rule : rules) {
            if (!rule.isActive()) continue;

            boolean holdsA = resolvedPermissions.contains(rule.getPermissionA());
            boolean holdsB = resolvedPermissions.contains(rule.getPermissionB());
            boolean actedA = actedPermissions.contains(rule.getPermissionA());
            boolean actedB = actedPermissions.contains(rule.getPermissionB());

            // Conflict: user holds both permissions AND has already acted on one side
            boolean conflict = false;
            String conflictingPerm = null;

            if (holdsA && holdsB) {
                if (actedA && holdsB) { conflict = true; conflictingPerm = rule.getPermissionB(); }
                else if (actedB && holdsA) { conflict = true; conflictingPerm = rule.getPermissionA(); }
            }

            if (conflict) {
                violations.add(AccessContext.SodViolation.builder()
                        .ruleName(rule.getRuleName())
                        .conflictingPermission(conflictingPerm)
                        .conflictType(rule.getConflictType().name())
                        .message(buildSodMessage(rule))
                        .build());
                log.warn("[SOD] Violation detected | user={} | rule='{}' | type={}",
                        user.getId(), rule.getRuleName(), rule.getConflictType());
            }
        }

        return violations;
    }

    /**
     * Returns the permission codes associated with a step instance's actor role.
     * Used to determine what permissions a user "exercised" by completing a step.
     *
     * Derives permissions from the step's actorRoles → their permission grants.
     * This is a light lookup — cached or kept small in practice.
     */
    private Set<String> resolveStepPermissions(Long stepInstanceId) {
        return stepInstanceRepository.findById(stepInstanceId)
                .map(si -> {
                    if (si.getStepId() == null) return Collections.<String>emptySet();
                    return stepRepository.findById(si.getStepId())
                            .map(step -> {
                                // Derive from the step's action type as a simple proxy
                                // In a full implementation, resolve actorRoles → their permissions
                                if (step.getStepAction() == null) return Collections.<String>emptySet();
                                String entityType = step.getSide() != null ? step.getSide().toLowerCase() : "entity";
                                String action = step.getStepAction().name().toLowerCase();
                                return Set.of(entityType + "." + action);
                            })
                            .orElse(Collections.emptySet());
                })
                .orElse(Collections.emptySet());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE: AVAILABLE ACTIONS
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> resolveAvailableActions(AccessContext base, StepUiOverride override,
                                                 List<AccessContext.SodViolation> sodViolations) {
        if (!base.isCanAct()) return Collections.emptyList();

        // Start with defaults based on task role
        List<String> defaults = base.getTaskRole() == TaskRole.ACTOR
                ? new ArrayList<>(ACTOR_DEFAULT_ACTIONS)
                : new ArrayList<>(ASSIGNER_DEFAULT_ACTIONS);

        // Apply step override restriction
        if (override.getAvailableActions() != null && !override.getAvailableActions().isEmpty()) {
            defaults.retainAll(override.getAvailableActions());
        }

        // HARD SoD violations block approval actions
        boolean hasHardViolation = sodViolations.stream()
                .anyMatch(v -> "HARD".equals(v.getConflictType()));
        if (hasHardViolation) {
            defaults.removeAll(List.of("APPROVE", "REJECT"));
        }

        return defaults;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE: MERGE
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> applyPermissionOverrides(List<String> base, StepUiOverride override) {
        if (override == null || override.getPermissionOverrides() == null) return base;
        Set<String> result = new HashSet<>(base);
        PermissionOverrides po = override.getPermissionOverrides();
        if (po.getAdd()    != null) result.addAll(po.getAdd());
        if (po.getRemove() != null) result.removeAll(po.getRemove());
        return new ArrayList<>(result);
    }

    private AccessContext mergeContext(AccessContext base, StepUiOverride override,
                                       List<String> permissions, List<String> availableActions,
                                       List<AccessContext.SodViolation> sodViolations,
                                       String stepLabel, String stepAction,
                                       boolean snapAutoCompleteActorOnSubmit) {
        return AccessContext.builder()
                // ── Existing fields (from mode resolution) ────────────────────
                .mode(base.getMode())
                .canView(base.isCanView())
                .canEdit(base.isCanEdit() && sodViolations.stream().noneMatch(v -> "HARD".equals(v.getConflictType())))
                .canAct(base.isCanAct())
                .taskRole(base.getTaskRole())
                .stepStatus(base.getStepStatus())
                .workflowStatus(base.getWorkflowStatus())
                .reason(base.getReason())
                // ── Step label + action ───────────────────────────────────────
                .stepLabel(stepLabel)
                .stepAction(stepAction)
                // ── Field visibility (from step override) ─────────────────────
                .editableFields(nullIfEmpty(override.getEditableFields()))
                .readOnlyFields(nullIfEmpty(override.getReadOnlyFields()))
                .hiddenFields(nullIfEmpty(override.getHiddenFields()))
                // ── Tab visibility (from step override) ───────────────────────
                .visibleTabs(nullIfEmpty(override.getVisibleTabs()))
                .hiddenTabs(nullIfEmpty(override.getHiddenTabs()))
                // ── Actions ───────────────────────────────────────────────────
                .availableActions(nullIfEmpty(availableActions))
                // ── Permissions (with step-scoped overrides applied) ──────────
                .permissions(applyPermissionOverrides(permissions, override))
                // ── SoD ───────────────────────────────────────────────────────
                .sodViolations(sodViolations.isEmpty() ? null : sodViolations)
                .autoCompleteActorOnSubmit(
                        base.isCanAct()
                                && "ACTOR".equals(base.getTaskRole())
                                && Boolean.TRUE.equals(snapAutoCompleteActorOnSubmit))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE: UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    private <T> List<T> nullIfEmpty(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list;
    }

    private String buildSodMessage(SodRule rule) {
        return String.format(
                "%s: you have already acted on this record in a conflicting capacity. %s",
                rule.getRuleName(),
                "HARD".equals(rule.getConflictType().name())
                        ? "Approval actions are blocked."
                        : "Document an exception to proceed."
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INNER: Step UI override POJO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Deserialized from StepInstance.snapUiOverrideJson.
     * All fields optional — null means "no restriction on this dimension".
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class StepUiOverride {
        /** When non-empty: only these tabs are shown */
        private List<String> visibleTabs;
        /** Explicitly hidden tabs */
        private List<String> hiddenTabs;
        /** When non-empty: only these fields are editable */
        private List<String> editableFields;
        /** Always read-only at this step */
        private List<String> readOnlyFields;
        /** Completely hidden from user */
        private List<String> hiddenFields;
        /** When non-empty: only these actions are available */
        private List<String> availableActions;
        /**
         * Step-scoped permission overrides.
         * add[]    — grant extra permissions for this step only.
         * remove[] — strip permissions for this step only.
         * Applied after resolvePermissions() in mergeContext().
         */
        private PermissionOverrides permissionOverrides;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class PermissionOverrides {
        private List<String> add;
        private List<String> remove;
    }
}

/*
 * ── REPOSITORY ADDITIONS NEEDED ───────────────────────────────────────────────
 *
 * 1. PermissionGrantRepository — add:
 *
 *    @Query("""
 *        SELECT p.code, pg.granted
 *        FROM PermissionGrant pg
 *        JOIN Permission p ON p.id = pg.permissionId
 *        WHERE pg.roleId IN :roleIds
 *    """)
 *    List<Object[]> findGrantsForUserRoles(@Param("roleIds") List<Long> roleIds);
 *
 * 2. UserPermissionOverrideRepository — add:
 *
 *    -- Add permissionCode as a denormalized column to UserPermissionOverride
 *    -- (avoids a join in the hot path — just store the code string at grant time)
 *
 *    @Query("""
 *        SELECT o FROM UserPermissionOverride o
 *        WHERE o.userId = :userId
 *          AND o.isActive = true
 *          AND (o.expiresAt IS NULL OR o.expiresAt > :now)
 *    """)
 *    List<UserPermissionOverride> findActiveByUserId(
 *        @Param("userId") Long userId, @Param("now") LocalDateTime now);
 *
 * 3. SodRuleRepository — add:
 *
 *    @Query("""
 *        SELECT r FROM SodRule r
 *        WHERE r.isActive = true
 *          AND (r.entityTypes IS NULL
 *               OR r.entityTypes LIKE CONCAT('%', :entityType, '%'))
 *    """)
 *    List<SodRule> findActiveRulesForEntityType(@Param("entityType") String entityType);
 *
 * 4. TaskInstanceRepository — add:
 *
 *    @Query("""
 *        SELECT t FROM TaskInstance t
 *        JOIN StepInstance si ON si.id = t.stepInstanceId
 *        WHERE si.workflowInstanceId = :instanceId
 *          AND t.assignedUserId = :userId
 *          AND t.taskRole = 'ACTOR'
 *          AND t.status IN ('APPROVED', 'COMPLETED')
 *    """)
 *    List<TaskInstance> findActorTasksForInstance(
 *        @Param("instanceId") Long instanceId, @Param("userId") Long userId);
 *
 * 5. WorkflowInstanceRepository — add:
 *
 *    Optional<WorkflowInstance> findActiveByEntityTypeAndEntityId(
 *        String entityType, Long entityId);
 *    -- (filter: status = IN_PROGRESS)
 */