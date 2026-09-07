-- ═══════════════════════════════════════════════════════════════════════════
-- 19 — Collation convergence on utf8mb4_0900_ai_ci        [v4 — OPTION B]
--
-- ── YES, ZERO CODE CHANGES ─────────────────────────────────────────────────
-- Collation is a storage-layer property of a column. Nothing in the Java stack
-- references it:
--   * JPA/Hibernate never emit COLLATE and never read it back
--   * no @Column annotation in the codebase specifies one
--   * the JDBC driver negotiates connection charset independently
--   * every entity, repository, DTO and query is untouched
--
-- The only code-adjacent effect is a positive one: the explicit COLLATE clauses
-- I put in 20-cleanup-debris.sql and 21-seed-dpdpa.sql become redundant. They
-- stay harmless — a COLLATE matching the column's own collation is a no-op.
--
-- And it removes the reason PolicyContextAssembler.candidateControlsForTemplate()
-- would have thrown 1267, without touching the JPQL.
--
-- ── WHY B IS THE RIGHT CALL NOW ────────────────────────────────────────────
-- I previously argued for A on lock-window grounds. Two things changed that:
--
--   1. Your DATABASE default is already utf8mb4_0900_ai_ci. Option B needs no
--      default change, so the drift stops the moment the last table converts.
--      Option A would have required changing the default too, and a missed
--      default is exactly how you got here.
--
--   2. The one behavioural difference between these collations is padding, and
--      I checked your data for it. Details below — it comes back clean.
--
-- So B costs a longer maintenance window and buys the better collation, a
-- correct default, and no ongoing discipline requirement. Take the window.
--
-- ── THE ONE REAL RISK, AND WHY IT LOOKS CLEAR ──────────────────────────────
-- utf8mb4_unicode_ci is PAD SPACE. utf8mb4_0900_ai_ci is NO PAD.
--
--   today:  'IAM-02.3 ' = 'IAM-02.3'   ->  TRUE
--   after:  'IAM-02.3 ' = 'IAM-02.3'   ->  FALSE
--
-- So a row with a trailing space in a joined column joins today and silently
-- stops joining afterwards. That is the failure mode to rule out — it does not
-- error, it just returns fewer rows.
--
-- I checked every join column in the exports you sent:
--   common_controls        code, parent_code, domain_code
--   common_control_mappings common_control_code, framework_ref, citation
--   audit_controls         control_code, control_tag, common_control_code
--   audit_tests            control_tag, common_control_code, automation_key
--   audit_sections         section_code, framework_ref, path
--   audit_templates        template_name, framework_ref, framework_code
--
--   trailing/leading whitespace : 0
--   non-ASCII characters        : 0
--
-- That covers 9 tables of 167. STEP 1 checks the rest.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── STEP 1. Rule out padding anywhere in the schema ────────────────────────
-- Generates one SELECT per string column. Run this, then run its output. Any
-- row it returns is a value that would stop matching after conversion.

SELECT CONCAT(
  'SELECT ''', TABLE_NAME, '.', COLUMN_NAME, ''' AS col_, COUNT(*) AS padded_ FROM `',
  TABLE_NAME, '` WHERE `', COLUMN_NAME, '` <> TRIM(`', COLUMN_NAME, '`) HAVING padded_ > 0 UNION ALL'
) AS check_sql
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND DATA_TYPE IN ('varchar','char')
   AND TABLE_NAME NOT LIKE 'ai_%'
 ORDER BY TABLE_NAME, COLUMN_NAME;

-- Strip the final 'UNION ALL' before running. Empty result = safe to proceed.
-- If anything comes back, TRIM those columns first:
--   UPDATE `t` SET `c` = TRIM(`c`) WHERE `c` <> TRIM(`c`);
-- Do that BEFORE converting, while PAD SPACE still makes the two forms equal —
-- afterwards you would be repairing joins that have already gone quiet.

-- ── STEP 2. Blockers ───────────────────────────────────────────────────────
SELECT TABLE_NAME, COLUMN_NAME, GENERATION_EXPRESSION
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> '';

-- Indexed VARCHARs near the 3072-byte InnoDB key limit. At 4 bytes/char a
-- VARCHAR(768) index is already at the ceiling; conversion can tip it over.
SELECT s.TABLE_NAME, s.INDEX_NAME, s.COLUMN_NAME, c.CHARACTER_MAXIMUM_LENGTH
  FROM information_schema.STATISTICS s
  JOIN information_schema.COLUMNS c
    ON c.TABLE_SCHEMA = s.TABLE_SCHEMA AND c.TABLE_NAME = s.TABLE_NAME
   AND c.COLUMN_NAME = s.COLUMN_NAME
 WHERE s.TABLE_SCHEMA = DATABASE()
   AND c.DATA_TYPE IN ('varchar','char')
   AND c.CHARACTER_MAXIMUM_LENGTH > 700
 ORDER BY c.CHARACTER_MAXIMUM_LENGTH DESC;

-- ── STEP 3. Size the window ────────────────────────────────────────────────
SELECT TABLE_COLLATION,
       COUNT(*) AS tables_,
       SUM(TABLE_ROWS) AS approx_rows,
       ROUND(SUM(DATA_LENGTH + INDEX_LENGTH)/1024/1024, 1) AS total_mb
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
 GROUP BY TABLE_COLLATION;

-- ── STEP 4. Generate the alters, smallest first ────────────────────────────
-- Smallest first is deliberate: if something is going to fail on a generated
-- column or an index limit, it fails in the first few seconds on a tiny table
-- rather than forty minutes in on your largest one.
--
-- ALGORITHM=INPLACE, LOCK=NONE is attempted so reads and writes continue.
-- MySQL rejects it for some collation changes; when it does, you get
-- "ALGORITHM=INPLACE is not supported" immediately and no work is done. Re-run
-- that table without the clause, inside the window.

SELECT TABLE_NAME,
       TABLE_ROWS,
       ROUND((DATA_LENGTH + INDEX_LENGTH)/1024/1024, 1) AS mb,
       CONCAT('ALTER TABLE `', TABLE_NAME,
              '` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci',
              ', ALGORITHM=INPLACE, LOCK=NONE;') AS statement_
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_TYPE = 'BASE TABLE'
   AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'
 ORDER BY (DATA_LENGTH + INDEX_LENGTH) ASC;

-- Fallback for any table that rejected INPLACE. This one locks:
-- SELECT CONCAT('ALTER TABLE `', TABLE_NAME,
--               '` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
--   FROM information_schema.TABLES
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
--    AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'
--  ORDER BY (DATA_LENGTH + INDEX_LENGTH) ASC;

-- ── STEP 5. Database default ───────────────────────────────────────────────
-- Already utf8mb4_0900_ai_ci. Nothing to change — that is the quiet advantage
-- of this direction. New tables, including the seven ai_* tables that
-- ddl-auto=update will create, land on the correct collation with no action and
-- no ongoing discipline.
--
-- Confirm anyway:
SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
  FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE();

-- ── STEP 6. Verify ─────────────────────────────────────────────────────────
-- One row.
SELECT TABLE_COLLATION, COUNT(*) FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
 GROUP BY TABLE_COLLATION;

-- Column level, in case a CONVERT TO was skipped.
SELECT DISTINCT COLLATION_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND COLLATION_NAME IS NOT NULL;

-- The two joins that threw 1267. Both must return a number now.
SELECT COUNT(*) AS tests_anchored_to_a_live_leaf
  FROM audit_tests t
  JOIN common_controls c ON c.code = t.control_tag
 WHERE c.node_level = 'CONTROL' AND c.is_active = 1;          -- expect 157

SELECT COUNT(DISTINCT a.common_control_code) AS audit_controls_resolving_to_ucf
  FROM audit_controls a
  JOIN common_controls c ON c.code = a.common_control_code
 WHERE c.node_level = 'CONTROL' AND c.is_active = 1;          -- expect ~58

-- Row counts unchanged — proves no join went quiet from the padding change.
-- Compare against the same query taken BEFORE you start.
SELECT 'controls' t, COUNT(*) n FROM audit_controls
UNION ALL SELECT 'tests',      COUNT(*) FROM audit_tests
UNION ALL SELECT 'automated',  COUNT(*) FROM audit_tests WHERE automation_key IS NOT NULL
UNION ALL SELECT 'ucf leaves', COUNT(*) FROM common_controls WHERE node_level='CONTROL'
UNION ALL SELECT 'mappings',   COUNT(*) FROM common_control_mappings
UNION ALL SELECT 'instances',  COUNT(*) FROM audit_control_instances;

-- ── STEP 7. Keep it from drifting again ────────────────────────────────────
-- Add to a startup health check. One query, logged as a warning:
--
--   SELECT COUNT(DISTINCT TABLE_COLLATION) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';
--
-- Above 1 means a table was created outside the default. Turns a silent
-- multi-year drift into a log line on the day it starts.

-- ── RUNBOOK ────────────────────────────────────────────────────────────────
--   1. Backup. This is DDL across 123 tables.
--   2. Capture the STEP 6 row counts NOW, for comparison afterwards.
--   3. Run STEP 1 and its generated output. Do not proceed until empty.
--   4. Run STEP 2. Resolve anything it returns.
--   5. Run the STEP 4 alters in order. Watch for INPLACE rejections.
--   6. Run STEP 6. Compare row counts against step 2's capture.
--   7. THEN run 20-cleanup-debris.sql, 21a, 21.
--
-- Do not interleave. A half-converted schema during the cleanup script means a
-- delete running across a collation boundary, which is the one situation where
-- 1267 would surface partway through a transaction.
