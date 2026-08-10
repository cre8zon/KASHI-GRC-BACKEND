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

        // CRITICAL for Aiven: ping idle connections every 30s.
        // Aiven drops idle MySQL connections at ~5 min. Without keepalive,
        // Hikari hands out a dead connection → 30s hang on the next request.
        config.setKeepaliveTime(30_000);

        config.setValidationTimeout(3_000);

        // NO setConnectionTestQuery.
        //
        // Setting it makes Hikari run that query on EVERY connection checkout —
        // one extra network round trip per request. Against Aiven at ~150ms RTT
        // measured from here (the 15ms in the class javadoc is optimistic) that is
        // ~150ms added to every single endpoint, and because it is raw JDBC rather
        // than Hibernate it never appears in query-count profiling.
        //
        // MySQL Connector/J supports JDBC4, so Hikari uses Connection.isValid()
        // instead: validated locally, no round trip. HikariCP's own guidance is to
        // leave this unset on any JDBC4 driver.
        //
        // Dead-connection safety is unaffected — that is what keepaliveTime below
        // handles, by pinging IDLE connections in the background rather than
        // taxing every checkout.

        log.info("[DB-CONFIG] HikariCP pool ready | maxPool=20 | keepalive=30s | JDBC4 isValid()");
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
                    "CREATE INDEX idx_ai_assigned_status ON action_items (assigned_to, status, tenant_id)" },

            // ── Assessment template structure — /full endpoint ────────────────────────
            // template_section_mappings — scanned on every getFull() and publishTemplate().
            // Without these, MySQL does a filesort on template_id even with the unique
            // constraint, because composite (template_id, order_no) is needed for ORDER BY.
            new String[]{ "template_section_mappings", "idx_tsm_template_order",
                    "CREATE INDEX idx_tsm_template_order ON template_section_mappings (template_id, order_no)" },
            new String[]{ "template_section_mappings", "idx_tsm_section",
                    "CREATE INDEX idx_tsm_section ON template_section_mappings (section_id)" },

            // section_question_mappings — hot path: IN query over many section IDs in getFull()
            new String[]{ "section_question_mappings", "idx_sqm_section_order",
                    "CREATE INDEX idx_sqm_section_order ON section_question_mappings (section_id, order_no)" },
            new String[]{ "section_question_mappings", "idx_sqm_question",
                    "CREATE INDEX idx_sqm_question ON section_question_mappings (question_id)" },

            // question_option_mappings — hot path: IN query over many question IDs in getFull()
            new String[]{ "question_option_mappings", "idx_qom_question_order",
                    "CREATE INDEX idx_qom_question_order ON question_option_mappings (question_id, order_no)" },

            // assessment_questions — library list queries filtered by tenant
            new String[]{ "assessment_questions", "idx_aq_tenant",
                    "CREATE INDEX idx_aq_tenant ON assessment_questions (tenant_id)" },

            // assessment_sections — library list queries filtered by tenant
            new String[]{ "assessment_sections", "idx_as_tenant",
                    "CREATE INDEX idx_as_tenant ON assessment_sections (tenant_id)" },

            // notifications — unread count query fires on every page load via TopNav badge
            new String[]{ "notifications", "idx_notif_user_read",
                    "CREATE INDEX idx_notif_user_read ON notifications (user_id, read_at)" },

            // ── Added for the N+1 batch-query fixes done in this pass — these tables/
            // columns weren't hit by any indexed lookup until the corresponding fix
            // (findAll()+filter → indexed query, or full-fetch → COUNT query) existed.
            // Without these, the batched/counted queries are fewer round trips but each
            // one is still a full table scan — the fix's benefit is capped by whichever
            // of the two is missing.

            // audit_sections — CSV library import section resolution (was findAll() per row)
            new String[]{ "audit_sections", "idx_asec_code_tenant",
                    "CREATE INDEX idx_asec_code_tenant ON audit_sections (section_code, tenant_id)" },
            new String[]{ "audit_sections", "idx_asec_parent_order",
                    "CREATE INDEX idx_asec_parent_order ON audit_sections (parent_id, order_no)" },
            new String[]{ "audit_sections", "idx_asec_tenant_parent_name",
                    "CREATE INDEX idx_asec_tenant_parent_name ON audit_sections (tenant_id, parent_id, name(100))" },

            // audit_controls — CSV import + control-test/policy mapping resolution
            new String[]{ "audit_controls", "idx_actl_code_tenant",
                    "CREATE INDEX idx_actl_code_tenant ON audit_controls (control_code, tenant_id)" },
            new String[]{ "audit_controls", "idx_actl_name_tenant",
                    "CREATE INDEX idx_actl_name_tenant ON audit_controls (name(150), tenant_id)" },

            // audit_templates — CSV import template upsert
            new String[]{ "audit_templates", "idx_atpl_name_tenant",
                    "CREATE INDEX idx_atpl_name_tenant ON audit_templates (name(150), tenant_id)" },

            // audit_tests — CSV import + control-test mapping resolution
            new String[]{ "audit_tests", "idx_atest_ref_tenant",
                    "CREATE INDEX idx_atest_ref_tenant ON audit_tests (test_ref, tenant_id)" },

            // audit_section_instances — completeness-gate COUNT queries fire on EVERY
            // single section assignment (not just bulk) — see
            // checkAndFireEngagementsOnboardedGate/checkAndFireEvidenceOwnersAssignedGate.
            // Without this, replacing "fetch every section" with a COUNT query still
            // scans the whole table per call.
            new String[]{ "audit_section_instances", "idx_asi_engagement_auditor",
                    "CREATE INDEX idx_asi_engagement_auditor ON audit_section_instances (engagement_id, assigned_auditor_id)" },
            new String[]{ "audit_section_instances", "idx_asi_engagement_auditee",
                    "CREATE INDEX idx_asi_engagement_auditee ON audit_section_instances (engagement_id, auditee_assigned_user_id)" },

            // audit_control_instances / audit_findings — project-instance report rollup
            // (AuditEngagementController.getProjectInstanceReportData batch fix)
            new String[]{ "audit_control_instances", "idx_aci_engagement",
                    "CREATE INDEX idx_aci_engagement ON audit_control_instances (engagement_id)" },
            new String[]{ "audit_findings", "idx_af_engagement_tenant",
                    "CREATE INDEX idx_af_engagement_tenant ON audit_findings (engagement_id, tenant_id)" },

            // assessment_question_instances — countByAssessmentIdIn/countBySectionInstanceIdIn
            // (getVendorAssessments + getSectionsStatus batch fixes)
            new String[]{ "assessment_question_instances", "idx_aqi_assessment",
                    "CREATE INDEX idx_aqi_assessment ON assessment_question_instances (assessment_id)" },
            new String[]{ "assessment_question_instances", "idx_aqi_section_instance",
                    "CREATE INDEX idx_aqi_section_instance ON assessment_question_instances (section_instance_id)" },

            // assessment_option_instances — findByQuestionInstanceIdInOrderByOrderNo
            // (getMySections/getMyQuestions/buildSectionInstances/getMyReviewerSections —
            // this was the single most repeated batch fix in the whole pass)
            new String[]{ "assessment_option_instances", "idx_aoi_question_order",
                    "CREATE INDEX idx_aoi_question_order ON assessment_option_instances (question_instance_id, order_no)" },

            // workflow_instance_history — getFullHistoryForInstances (multi-instance
            // history batch fix; also the underlying table for every single-instance
            // history query, which benefits from this too)
            new String[]{ "workflow_instance_history", "idx_wih_instance_performed",
                    "CREATE INDEX idx_wih_instance_performed ON workflow_instance_history (workflow_instance_id, performed_at)" }

            // NOTE: assessment_template_instances.assessment_id already has @Column(unique
            // = true) on the entity, which Hibernate/ddl-auto=update creates as a unique
            // constraint (and therefore an index) automatically — findByAssessmentIdIn on
            // that table doesn't need a manual entry here.
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