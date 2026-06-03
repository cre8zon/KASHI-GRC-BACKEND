package com.kashi.grc.workflow.event;

/**
 * Spring ApplicationEvent fired by TaskSectionCompletionService after
 * snapshotSectionsForTask() creates a TaskSectionCompletion row for a section
 * that has tracksItems=true and a non-null itemRefType.
 *
 * This event tells domain-specific item registrars that a section is ready to
 * receive TaskSectionItem rows. Each registrar listens for the itemRefType(s)
 * it handles and calls TaskSectionCompletionService.registerItems() to populate
 * the section with its items.
 *
 * ── BACKWARD COMPATIBILITY ────────────────────────────────────────────────────
 * This event is ONLY fired when a blueprint section has:
 *   1. tracksItems = true
 *   2. itemRefType != null
 *
 * Existing TPRM blueprints have NO sections defined at all.
 * snapshotSectionsForTask() returns immediately (blueprint.isEmpty()).
 * Therefore: zero events fired, zero change in behavior for existing workflows.
 *
 * ── ADDING A NEW ITEM TYPE ────────────────────────────────────────────────────
 * 1. Create a @Component class that implements ApplicationListener<SectionItemsNeededEvent>
 *    (or use @EventListener)
 * 2. Filter on event.itemRefType() matching your entity type
 * 3. Load your entities for this workflowInstanceId
 * 4. Call sectionService.registerItems(taskInstanceId, sectionKey, registrations)
 *
 * Example registrars:
 *   AssessmentSectionItemRegistrar  → itemRefType = QUESTION_RESPONSE
 *   RiskControlItemRegistrar        → itemRefType = CONTROL
 *   AuditFindingItemRegistrar       → itemRefType = FINDING
 *
 * @param taskInstanceId      The TaskInstance for the step that just activated
 * @param stepInstanceId      The StepInstance
 * @param workflowInstanceId  The WorkflowInstance — used to look up domain entities
 * @param tenantId            Tenant scope
 * @param sectionKey          Blueprint section key (e.g. "QUESTIONS", "CONTROLS")
 * @param itemRefType         Entity type string (e.g. "QUESTION_RESPONSE", "CONTROL")
 * @param sectionScreenKey    Snapshotted section screen key — passed for context
 * @param itemScreenKey       Snapshotted item screen key — passed for context
 * @param sectionUiJson       Snapshotted section UI JSON override — passed for context
 * @param assignedUserId      userId the task belongs to — registrars use this to scope
 *                            items to only this user's assigned work
 */
public record SectionItemsNeededEvent(
        Long   taskInstanceId,
        Long   stepInstanceId,
        Long   workflowInstanceId,
        Long   tenantId,
        String sectionKey,
        String itemRefType,
        String sectionScreenKey,
        String itemScreenKey,
        String sectionUiJson,
        Long   assignedUserId
) {}