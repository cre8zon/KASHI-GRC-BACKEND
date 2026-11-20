package com.kashi.grc.uiconfig.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One user's layout for one table: column widths, hidden columns and column
 * order, saved from the table itself (drag a header edge, the Columns menu,
 * drag a header to reorder).
 *
 * MODEL:
 *   One row per (userId, tenantId, tableKey). tenantId is part of the key
 *   because a user who belongs to several organisations may see different
 *   columns for the same list in each. Users with no tenant are stored under
 *   tenantId 0 rather than NULL — MySQL unique indexes allow repeated NULLs.
 *
 *   tableKey names the table, e.g. "list:module_audit_policy" — the list's
 *   screen key, chosen by the frontend.
 *
 *   prefsJson is the frontend's own small document:
 *     { "widths": { "title": 420 }, "hidden": ["source"], "order": ["ref", "title", ...] }
 *   The server checks it is a JSON object of bounded size and otherwise does
 *   not interpret it; a column that no longer exists is ignored on read.
 *
 * No row = the table's configured default. Reset deletes the row.
 * Table is created automatically by ddl-auto=update.
 */
@Entity
@Table(name = "user_table_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_table_pref", columnNames = {"user_id", "tenant_id", "table_key"}),
        indexes = @Index(name = "idx_user_table_pref_user", columnList = "user_id"))
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class UserTablePreference {

    /** tenantId stored for users who belong to no organisation. */
    public static final long NO_TENANT = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "table_key", nullable = false, length = 120)
    private String tableKey;

    @Lob
    @Column(name = "prefs_json", nullable = false, columnDefinition = "TEXT")
    private String prefsJson;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
