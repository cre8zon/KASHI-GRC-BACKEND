package com.kashi.grc.common.kafka;

/**
 * Central registry of Kafka topic names.
 *
 * Naming convention: kashigrc.<domain>.<purpose>
 * Every topic used anywhere in the codebase MUST be declared here —
 * never inline topic strings in producers or listeners.
 *
 * Dead-letter topics are derived automatically as "<topic>.DLT"
 * by the DeadLetterPublishingRecoverer in KafkaConfig.
 */
public final class KafkaTopics {

    private KafkaTopics() {}

    /** Round-trip verification topic. Remove once real topics are live. */
    public static final String TEST = "kashigrc.test.echo";

    /**
     * Email requests — produced by MailService, consumed by EmailEventConsumer.
     * Event types: EMAIL_TEMPLATE_REQUESTED, EMAIL_RAW_REQUESTED.
     * Key: recipient email address (per-recipient ordering).
     * Consumer group: kashigrc-email.
     */
    public static final String EMAIL_REQUESTED = "kashigrc.email.requested";

    /**
     * Notification email fanout — produced by NotificationService (single
     * choke point for all 36 in-app notification call sites), consumed by
     * NotificationEmailConsumer which resolves recipients + templates and
     * chains per-recipient emails onto EMAIL_REQUESTED.
     * Event types: NOTIFICATION_EMAIL_REQUESTED.
     * Key: entityType:entityId (per-entity ordering), eventKey when absent.
     * Consumer group: kashigrc-notification.
     */
    public static final String NOTIFICATION_EMAIL = "kashigrc.notification.email";

    /**
     * Assessment instantiation — produced by WorkflowEngineService when a
     * SYSTEM step with automatedAction=EXECUTE_ASSESSMENT starts (instead of
     * dispatching the handler synchronously and blocking whatever request
     * thread caused the step to start), consumed by
     * ExecuteAssessmentConsumer, which dispatches the same handler off the
     * request thread and advances the workflow on completion.
     * Event types: EXECUTE_ASSESSMENT_REQUESTED.
     * Key: workflowInstanceId (per-instance ordering — a given workflow
     * instance's automated actions must not race each other).
     * Consumer group: kashigrc-assessment.
     */
    public static final String ASSESSMENT_EXECUTE_REQUESTED = "kashigrc.assessment.execute-requested";

    /**
     * Audit engagement template snapshot — produced by AuditEngagementService.create()
     * when a templateId is supplied (instead of running snapshotTemplate() + the
     * optional workflow start inline, blocking whatever request thread is
     * creating the engagement — a single POST /engagements call, or, worse,
     * the project-instance cascade which creates one engagement per planned
     * template in a loop). Consumed by AuditEngagementSnapshotConsumer.
     * Event types: AUDIT_ENGAGEMENT_SNAPSHOT_REQUESTED.
     * Key: engagementId (each engagement's own snapshot is independent —
     * round-robin across engagements is fine, no cross-engagement ordering
     * requirement).
     * Consumer group: kashigrc-audit-engagement.
     */
    public static final String AUDIT_ENGAGEMENT_SNAPSHOT_REQUESTED = "kashigrc.audit.engagement-snapshot-requested";

    // ── Future topics (declared when implemented) ─────────────────────
    // public static final String WORKFLOW_EVENTS    = "kashigrc.workflow.events";
    // public static final String AUDIT_TRAIL        = "kashigrc.audit.trail";
}