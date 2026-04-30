package com.kashi.grc.common.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * DatabaseConfig — single place for all DB performance tuning.
 *
 * Covers:
 *   1. HikariCP pool sized and keepalive-tuned for Aiven (remote SSL, ~15ms RTT).
 *   2. JDBC URL parameters — prepared statement cache, batch rewrites, socket timeouts.
 *   3. Missing indexes — created on startup via JdbcTemplate (avoids EntityManager cycle).
 *
 * Index strategy: MySQL does not reliably support "CREATE INDEX IF NOT EXISTS" across
 * all connector/server combinations. Instead we use SHOW INDEX to check first, then
 * CREATE only if missing. Safe and idempotent on every restart.
 */
@Slf4j
@Configuration
public class DatabaseConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    // ── 1. HikariCP DataSource ────────────────────────────────────────────────

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();

        String url = jdbcUrl.contains("cachePrepStmts")
                ? jdbcUrl
                : jdbcUrl + buildJdbcParams();

        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        config.setPoolName("KashiGRC-Pool");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);

        config.setConnectionTimeout(8_000);
        config.setIdleTimeout(300_000);
        config.setMaxLifetime(1_800_000);

        // CRITICAL for Aiven: ping idle connections every 60s.
        // Aiven drops idle MySQL connections at ~5 min. Without keepalive,
        // Hikari hands out a dead connection → 30s hang on the next request.
        config.setKeepaliveTime(30_000);

        config.setValidationTimeout(3_000);
        config.setConnectionTestQuery("SELECT 1");

        log.info("[DB-CONFIG] HikariCP pool ready | maxPool=20 | keepalive=60s");
        return new HikariDataSource(config);
    }

    private String buildJdbcParams() {
        return "&connectTimeout=5000"
                + "&socketTimeout=120000"
                + "&autoReconnect=true"
                + "&cachePrepStmts=true"
                + "&prepStmtCacheSize=250"
                + "&prepStmtCacheSqlLimit=2048"
                + "&useServerPrepStmts=true"
                + "&rewriteBatchedStatements=true"
                + "&serverTimezone=UTC";
    }

    // ── 2. Performance indexes ────────────────────────────────────────────────

    /**
     * Each entry: { "table_name", "index_name", "CREATE INDEX ... SQL" }
     *
     * We SHOW INDEX first to check existence, then CREATE only if missing.
     * This avoids MySQL's inconsistent "IF NOT EXISTS" support across connector
     * versions — which caused "bad SQL grammar" on Aiven 8.0.45.
     *
     * Adding a new slow query = add one row here. No entity file changes needed.
     */
    private static final List<String[]> INDEXES = List.of(

            // task_instances — inbox fires findByAssignedUserIdAndStatus 3× per load
            new String[]{ "task_instances", "idx_task_assigned_status",
                    "CREATE INDEX idx_task_assigned_status ON task_instances (assigned_user_id, status)" },
            new String[]{ "task_instances", "idx_task_step_instance",
                    "CREATE INDEX idx_task_step_instance ON task_instances (step_instance_id)" },
            new String[]{ "task_instances", "idx_task_step_status",
                    "CREATE INDEX idx_task_step_status ON task_instances (step_instance_id, status)" },

            // step_instances — workflow timeline load
            new String[]{ "step_instances", "idx_step_workflow_instance",
                    "CREATE INDEX idx_step_workflow_instance ON step_instances (workflow_instance_id, created_at)" },
            new String[]{ "step_instances", "idx_step_status_sla",
                    "CREATE INDEX idx_step_status_sla ON step_instances (status, sla_due_at, completed_at)" },

            // workflow_instances — vendor detail, entity resolution
            new String[]{ "workflow_instances", "idx_wi_tenant_entity",
                    "CREATE INDEX idx_wi_tenant_entity ON workflow_instances (tenant_id, entity_type, entity_id, status)" },

            // vendor_assessment_cycles — called on every vendor detail load
            new String[]{ "vendor_assessment_cycles", "idx_cycle_vendor",
                    "CREATE INDEX idx_cycle_vendor ON vendor_assessment_cycles (vendor_id, status)" },

            // Prevents duplicate cycles per workflow instance (fixes EntityResolver NonUniqueResultException)
            new String[]{ "vendor_assessment_cycles", "uk_cycle_workflow_instance",
                    "CREATE UNIQUE INDEX uk_cycle_workflow_instance ON vendor_assessment_cycles (workflow_instance_id)" },

            // Prevents duplicate response rows for the same (assessment, question) pair.
            // Root cause: concurrent multi-choice clicks fire two POST /respond requests
            // within the same DB transaction window: both SELECT empty, both INSERT.
            // The constraint makes the second INSERT fail with DataIntegrityViolationException
            // which AssessmentController.respondToQuestion catches and retries as UPDATE.
            // Cleanup first: DELETE FROM assessment_responses WHERE id = 72;
            new String[]{ "assessment_responses", "uk_response_assessment_question",
                    "CREATE UNIQUE INDEX uk_response_assessment_question ON assessment_responses (assessment_id, question_instance_id)" },

            // vendor_assessments — assessment tab, EntityResolver
            new String[]{ "vendor_assessments", "idx_assessment_cycle",
                    "CREATE INDEX idx_assessment_cycle ON vendor_assessments (cycle_id)" },

            // users — VRM lookup by vendor, role resolution
            new String[]{ "users", "idx_user_vendor",
                    "CREATE INDEX idx_user_vendor ON users (vendor_id, is_deleted)" },
            new String[]{ "users", "idx_user_tenant_vendor",
                    "CREATE INDEX idx_user_tenant_vendor ON users (tenant_id, vendor_id, is_deleted)" },

            // action_items — vendor detail action items tab
            new String[]{ "action_items", "idx_ai_entity",
                    "CREATE INDEX idx_ai_entity ON action_items (entity_type, entity_id, tenant_id)" },
            new String[]{ "action_items", "idx_ai_assigned_status",
                    "CREATE INDEX idx_ai_assigned_status ON action_items (assigned_to, status, tenant_id)" }
    );

    @Bean
    public ApplicationRunner ensureIndexes(DataSource dataSource) {
        return args -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            log.info("[DB-CONFIG] Checking {} performance indexes...", INDEXES.size());
            int created = 0, skipped = 0;

            for (String[] entry : INDEXES) {
                String table     = entry[0];
                String indexName = entry[1];
                String ddl       = entry[2];

                // Check existence via SHOW INDEX — works on all MySQL versions.
                // queryForList avoids the ambiguous lambda overload between
                // ResultSetExtractor and RowCallbackHandler in JdbcTemplate.query().
                boolean exists = !jdbc.queryForList(
                        "SHOW INDEX FROM `" + table + "` WHERE Key_name = ?",
                        indexName
                ).isEmpty();

                if (exists) {
                    skipped++;
                } else {
                    try {
                        jdbc.execute(ddl);
                        created++;
                        log.info("[DB-CONFIG] Created index: {}.{}", table, indexName);
                    } catch (Exception e) {
                        log.warn("[DB-CONFIG] Failed to create index {}.{}: {}",
                                table, indexName, e.getMessage());
                    }
                }
            }

            log.info("[DB-CONFIG] Indexes done | created={} | already-existed={}", created, skipped);
        };
    }
}