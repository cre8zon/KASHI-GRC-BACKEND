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

    // ── Future topics (declared when implemented) ─────────────────────
    // public static final String NOTIFICATION_FANOUT = "kashigrc.notification.fanout";
    // public static final String WORKFLOW_EVENTS    = "kashigrc.workflow.events";
    // public static final String AUDIT_TRAIL        = "kashigrc.audit.trail";
}