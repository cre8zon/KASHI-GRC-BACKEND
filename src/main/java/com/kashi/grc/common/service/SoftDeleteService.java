package com.kashi.grc.common.service;

import com.kashi.grc.common.domain.SoftDeletable;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.function.LongSupplier;

/**
 * Retire a record instead of destroying it, and bring it back.
 *
 * ── WHAT WAS WRONG ────────────────────────────────────────────────────────────
 *
 * 43 endpoints call repository.delete* and mean it. Among them are the ones
 * behind the generic Delete button on module pages, so a mis-click on an audit
 * finding, control, test or template removed the row and everything that
 * referenced it, with no undo and no record that it had ever existed. In a GRC
 * product that is worse than a data-loss bug: "we deleted the finding" is
 * itself an audit event, and the evidence that it happened went with it.
 *
 * ── MARK, OR REFUSE — NEVER SILENTLY HALF-DELETE ──────────────────────────────
 *
 * Two outcomes only.
 *
 *   mark()    the record is retired. It disappears from every list, picker and
 *             count, and can be restored.
 *   refuse()  the record is in use and the delete does not happen at all.
 *
 * The second matters as much as the first. A template with started project
 * plans, or an approved policy, cannot be retired without leaving live work
 * pointing at something that is no longer offered. Hiding it would make the
 * damage quieter rather than smaller, so the caller is told why and nothing
 * changes.
 *
 * ── WHY A SERVICE AND NOT A BASE-CLASS METHOD ─────────────────────────────────
 *
 * Stamping the four columns is three lines; getting the actor right is not.
 * utilityService.getLoggedInDataContext() throws off a request thread, which
 * is where a scheduled cleanup or a Kafka consumer would call this from. One
 * place to resolve the actor, log consistently and fail the same way is worth
 * more than saving an injection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoftDeleteService {

    private final UtilityService utilityService;

    /**
     * Retire a record.
     *
     * Idempotent: marking an already-deleted record leaves the original
     * deleted_at, deleted_by and reason alone. A second Delete click must not
     * rewrite who did it the first time — that is the field an auditor reads.
     *
     * @param entity the record to retire
     * @param reason free text, stored on the row. Null is allowed; the UI
     *               should ask, and personnel already does.
     * @return true when this call retired it, false when it already was
     */
    public <T extends SoftDeletable> boolean mark(T entity, String reason) {
        if (entity == null) return false;
        if (entity.isDeleted()) {
            log.debug("[SOFT-DELETE] {} id={} already deleted — left as it was",
                    entity.getClass().getSimpleName(), entity.getId());
            return false;
        }

        entity.setDeleted(true);
        entity.setDeletedAt(LocalDateTime.now());
        entity.setDeletedBy(currentUserId());
        entity.setDeletionReason(trim(reason));

        log.info("[SOFT-DELETE] Retired {} id={} | by={} | reason=\"{}\"",
                entity.getClass().getSimpleName(), entity.getId(),
                entity.getDeletedBy(), entity.getDeletionReason());
        return true;
    }

    /**
     * Bring a retired record back.
     *
     * The caller finds it with findById, which still sees deleted rows because
     * @SQLRestriction is not applied to a load by primary key — see
     * SoftDeletable. That quirk is what makes restore possible without a
     * native query.
     *
     * @return true when this call restored it, false when it was already live
     */
    public <T extends SoftDeletable> boolean restore(T entity) {
        if (entity == null) return false;
        if (!entity.isDeleted()) return false;

        Long wasDeletedBy = entity.getDeletedBy();
        LocalDateTime wasDeletedAt = entity.getDeletedAt();

        entity.setDeleted(false);
        entity.setDeletedAt(null);
        entity.setDeletedBy(null);
        entity.setDeletionReason(null);

        log.info("[SOFT-DELETE] Restored {} id={} | by={} | had been deleted by {} at {}",
                entity.getClass().getSimpleName(), entity.getId(),
                currentUserId(), wasDeletedBy, wasDeletedAt);
        return true;
    }

    /**
     * Refuse the delete because the record is in use.
     *
     * Call it with a count and a description of what is holding the record.
     * Nothing happens when the count is zero, so a call site reads as a
     * straight list of conditions:
     *
     *     softDelete.refuseIfInUse(planCount, "project plans have already started");
     *     softDelete.refuseIfInUse(instanceCount, "engagements are using this template");
     *     softDelete.mark(template, reason);
     *
     * The message names the obstacle rather than saying "in use", because the
     * next question is always "by what", and the person reading it is usually
     * about to go and clear it.
     */
    public void refuseIfInUse(long count, String what) {
        if (count <= 0) return;
        throw new BusinessException(
                "RECORD_IN_USE",
                "This cannot be deleted: " + count + " " + what + ".",
                HttpStatus.CONFLICT);
    }

    /** Lazy variant, for a count that costs a query you would rather not run. */
    public void refuseIfInUse(LongSupplier count, String what) {
        refuseIfInUse(count.getAsLong(), what);
    }

    /**
     * Refuse to operate on a record that has been retired.
     *
     * For the paths a load by primary key reaches — a detail page opened from
     * a stale link, an action posted against an id somebody kept. Lists never
     * need this; @SQLRestriction has already removed the row.
     */
    public void requireLive(SoftDeletable entity, String type) {
        if (entity != null && entity.isDeleted()) {
            throw new BusinessException(
                    "RECORD_DELETED",
                    "This " + type + " has been deleted.",
                    HttpStatus.GONE);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Who is doing this, or null off a request thread.
     *
     * getLoggedInDataContext reads the SecurityContext and throws when there
     * is none. A scheduled sweep or a Kafka consumer retiring a record is a
     * legitimate caller with no principal; it should record a null actor, not
     * fail the operation.
     */
    private Long currentUserId() {
        try {
            var ctx = utilityService.getLoggedInDataContext();
            return ctx == null ? null : ctx.getId();
        } catch (Exception e) {
            log.debug("[SOFT-DELETE] No request principal — recording a null actor");
            return null;
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > 500 ? t.substring(0, 500) : t;   // column is VARCHAR(500)
    }
}