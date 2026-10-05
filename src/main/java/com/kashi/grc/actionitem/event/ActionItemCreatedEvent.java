package com.kashi.grc.actionitem.event;

import com.kashi.grc.actionitem.domain.ActionItem;

/**
 * Published after an action item is persisted, so a module can react to an item
 * raised on ITS entity without the raiser knowing that module exists.
 *
 * ── WHY AN EVENT AND NOT A CALL ───────────────────────────────────────────
 * KashiGuard raises findings for every module — assessments, audits, anything
 * that publishes a ModuleSubmitEvent. Vendor assessments want a guard finding to
 * become a tracked Issue automatically; audit does not, and has its own
 * escalation with its own rules.
 *
 * Putting that decision inside GuardEvaluator would mean the guard module
 * importing the assessment module and branching on entity type — the thing that
 * makes a shared component stop being shared. Publishing here lets the
 * assessment module subscribe to the items that concern it and lets every other
 * module carry on exactly as before, with no listener and no change.
 *
 * Carries the entity rather than an id because every listener needs the type,
 * the entity id and the source to decide whether the item is theirs, and
 * re-reading the row for that would be three queries per raised item.
 *
 * Listeners MUST treat this as a notification, not a transaction hook: it is
 * published after save, and a listener that throws must not take the raise down
 * with it. Subscribe with AFTER_COMMIT and catch your own failures.
 */
public record ActionItemCreatedEvent(ActionItem item, Long tenantId) { }