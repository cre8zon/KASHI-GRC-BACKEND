package com.kashi.grc.evidence.event;

/**
 * KashiLink — published after an EvidenceRecord is persisted.
 *
 * WHY AN EVENT AND NOT A DIRECT @Async CALL
 * -----------------------------------------
 * EvidenceService.create() is @Transactional. The previous code called
 * EvidenceReuseEngine.propagate() directly, and that method was @Async — so the
 * pool thread opened a NEW transaction and ran findById() before the caller had
 * committed. The record was usually not visible yet, propagation silently
 * returned, and the log line read "No tag on record N — skipping" for a record
 * that definitely had a tag.
 *
 * Publishing an event and consuming it with
 * @TransactionalEventListener(AFTER_COMMIT) guarantees the record is committed
 * and visible before propagation starts. This mirrors GuardEvaluationListener,
 * which already uses exactly this pattern for KashiGuard.
 *
 * @param evidenceRecordId the persisted record
 * @param tenantId         owning tenant
 * @param controlTag       the tag that will drive propagation (already uppercased)
 * @param automated        true if collected by an integration check
 * @param automationPass   only meaningful when automated=true; drives
 *                         AUTOMATION_VERIFIED vs PENDING_REVIEW
 */
public record EvidenceRecordCreatedEvent(
        Long    evidenceRecordId,
        Long    tenantId,
        String  controlTag,
        boolean automated,
        boolean automationPass
) {
    public static EvidenceRecordCreatedEvent manual(Long recordId, Long tenantId, String tag) {
        return new EvidenceRecordCreatedEvent(recordId, tenantId, tag, false, false);
    }

    public static EvidenceRecordCreatedEvent automated(Long recordId, Long tenantId,
                                                       String tag, boolean pass) {
        return new EvidenceRecordCreatedEvent(recordId, tenantId, tag, true, pass);
    }
}