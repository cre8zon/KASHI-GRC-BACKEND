package com.kashi.grc.common.jdbc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch-inserts rows via one JDBC batch (Statement.RETURN_GENERATED_KEYS) and
 * returns the generated ids in insertion order.
 *
 * WHY THIS EXISTS — RAW JDBC INSTEAD OF A REPOSITORY: every entity in this
 * app uses GenerationType.IDENTITY (see BaseEntity). Hibernate cannot batch
 * INSERTs for IDENTITY-strategy entities — it must flush after each row to
 * read back the DB-assigned auto-increment id before it can set the FK on
 * the next row. So calling saveAll() on a list of new entities still issues
 * one INSERT round trip per row; hibernate.jdbc.batch_size (already set in
 * application.properties) is silently a no-op for every INSERT in this
 * codebase, even though it DOES work for UPDATEs (no identity-retrieval
 * problem there). This is the one way to get genuine batch-insert behavior
 * for a hot bulk-provisioning path — first used in ExecuteAssessmentAction's
 * assessment-instantiation rewrite, extracted here once a second call site
 * (AuditSectionService's engagement template snapshot) needed the exact
 * same thing.
 *
 * This is a deliberate, localized exception to the "always use JPA
 * repositories" convention — reach for it only for genuinely hot,
 * high-row-count INSERT paths, not as a general replacement for saveAll().
 *
 * Participates in the caller's @Transactional automatically: JdbcTemplate
 * shares the same DataSource-bound connection as JPA repositories when
 * Spring's transaction synchronization is active, so this insert commits/
 * rolls back with everything else in the enclosing transaction.
 *
 * Ordering guarantee this relies on: MySQL Connector/J returns generated
 * keys for a batch insert in the same order the rows were added to the
 * batch. This is standard, documented MySQL driver behavior (unlike some
 * other databases, which do NOT guarantee generated-key order for batches
 * — this helper is MySQL-specific for that reason).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcBatchInsertHelper {

    private final JdbcTemplate jdbcTemplate;

    /** Batch-insert; returns generated ids in the same order as {@code rows}. */
    public List<Long> batchInsertAndGetIds(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) return List.of();
        return jdbcTemplate.execute((java.sql.Connection con) -> {
            List<Long> ids = new ArrayList<>(rows.size());
            try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (Object[] row : rows) {
                    for (int i = 0; i < row.length; i++) {
                        ps.setObject(i + 1, row[i]);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            return ids;
        });
    }

    /**
     * Batch-update rows sharing one SQL template, e.g. for a follow-up path
     * fix-up after an insert whose value depends on the row's own generated
     * id. Each element of {@code rows} is the parameter list for one
     * execution of {@code sql} (in JDBC parameter order).
     */
    public void batchUpdate(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) return;
        jdbcTemplate.batchUpdate(sql, rows);
    }
}