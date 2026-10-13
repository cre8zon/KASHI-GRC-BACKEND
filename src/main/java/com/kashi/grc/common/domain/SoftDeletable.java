package com.kashi.grc.common.domain;

import java.time.LocalDateTime;

/**
 * An entity that is retired rather than removed.
 *
 * ── WHY THIS IS AN INTERFACE AND NOT A SUPERCLASS ─────────────────────────────
 *
 * The obvious move is a @MappedSuperclass carrying the four columns, and it
 * does not work here. The entities that need soft delete already extend two
 * different bases — AuditFinding extends TenantAwareEntity, the audit library
 * extends GlobalOrTenantEntity (tenant_id nullable, because a platform row is
 * shared by every org) — and Java has one superclass to give. Adding the
 * columns to GlobalOrTenantEntity would impose them on every entity that
 * extends it, including ones that should never be deletable at all.
 *
 * So each entity declares the four fields itself, exactly as Personnel already
 * does, and implements this interface so SoftDeleteService can treat them
 * uniformly. Lombok's @Getter/@Setter satisfy the whole contract with no
 * hand-written methods: a `private boolean isDeleted` field produces
 * isDeleted() and setDeleted(boolean), which is the shape AuditableEntity and
 * Vendor already use.
 *
 * ── THE COLUMNS, AND WHY THESE FOUR ───────────────────────────────────────────
 *
 *   is_deleted       the flag every query filters on
 *   deleted_at       when
 *   deleted_by       who — the one question an auditor always asks next
 *   deletion_reason  why
 *
 * Personnel arrived at the same four independently. Copying its names means a
 * restore screen, an export or a report can treat every soft-deletable table
 * the same way, rather than learning a second vocabulary.
 *
 * ── WHAT ACTUALLY HIDES THE ROW ───────────────────────────────────────────────
 *
 * Not this interface. Each implementing ENTITY carries
 *
 *     @SQLRestriction("is_deleted = false")
 *
 * which Hibernate applies to HQL, criteria queries and collection loads. That
 * is what makes the change tractable: every module list runs through
 * DbRepository, which builds criteria queries, so all of them filter with no
 * call-site edits at all. The annotation has to sit on the entity — Hibernate
 * ignores it on a mapped superclass — which is the second reason this is an
 * interface rather than a base class.
 *
 * ── THE ONE EXCEPTION, STATED PLAINLY ─────────────────────────────────────────
 *
 * @SQLRestriction is NOT applied to a load by primary key. findById() and
 * EntityManager.find() still return a deleted row. That is deliberate on
 * Hibernate's part and useful here: it is how SoftDeleteService.restore finds
 * the row it has to bring back.
 *
 * It does mean a direct link to a deleted record still resolves. Anywhere that
 * matters, call SoftDeleteService.requireLive after loading. Lists, pickers,
 * counts and associations — everywhere a deleted row would be offered to
 * someone who was not looking for it — are covered automatically.
 */
public interface SoftDeletable {

    Long getId();

    boolean isDeleted();
    void setDeleted(boolean deleted);

    LocalDateTime getDeletedAt();
    void setDeletedAt(LocalDateTime deletedAt);

    Long getDeletedBy();
    void setDeletedBy(Long deletedBy);

    String getDeletionReason();
    void setDeletionReason(String deletionReason);
}