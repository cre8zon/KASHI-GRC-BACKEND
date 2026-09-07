-- ═══════════════════════════════════════════════════════════════════════════
-- 19d — Run the conversion
--
-- ── GATES PASSED ───────────────────────────────────────────────────────────
--   padding check      23/23 join columns at 0        CLEAR
--   index length       statement 406, 0 rows          CLEAR
--   generated column   statement 426, 0 rows          NOT on a UCF join path
--                                                     -> excluded, deferred
--
-- ── BASELINE YOU RECORDED ──────────────────────────────────────────────────
--   tests anchored to a live leaf         213
--   audit_controls resolving to ucf        61
--   controls reachable from template 28   116
--   controls reachable from template 12    41
--   automated tests                        19
--
-- These are the numbers to compare against afterwards. Not mine.
--
-- ── BEFORE YOU START ───────────────────────────────────────────────────────
-- Take a backup. This is DDL across ~122 tables. Everything else in this
-- conversation has been reversible; this is the point where that stops being
-- true without one.
-- ═══════════════════════════════════════════════════════════════════════════


-- ══ STEP 1 — see exactly what will run ════════════════════════════════════
-- The generated-column table is excluded automatically by the NOT IN clause,
-- so you do not have to remember its name.

SELECT t.TABLE_NAME, t.TABLE_ROWS,
       ROUND((t.DATA_LENGTH + t.INDEX_LENGTH)/1024/1024, 1) AS mb,
       CONCAT('ALTER TABLE `', t.TABLE_NAME,
              '` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;') AS statement_
  FROM information_schema.TABLES t
 WHERE t.TABLE_SCHEMA = DATABASE()
   AND t.TABLE_TYPE   = 'BASE TABLE'
   AND t.TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'
   AND t.TABLE_NAME NOT IN (
       SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> '')
 ORDER BY (t.DATA_LENGTH + t.INDEX_LENGTH) ASC;

-- Expect ~122 rows. Check the mb column: the total is your window.


-- ══ STEP 2 — run them ═════════════════════════════════════════════════════
-- 122 statements is too many to paste by hand. This procedure walks the same
-- list, smallest table first, and reports as it goes.
--
-- IT IS RESUMABLE. Converted tables stop matching the cursor's WHERE clause, so
-- if it stops on table 70 you fix that one table and re-run the procedure —
-- it picks up at 71 with no bookkeeping.
--
-- There is deliberately NO error handler. If a table fails you want the
-- procedure to stop there and tell you, not to plough on and leave you
-- reconciling which of 122 succeeded.

DROP PROCEDURE IF EXISTS kashi_convert_collation;

DELIMITER $$
CREATE PROCEDURE kashi_convert_collation()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE tname VARCHAR(255);
  DECLARE n INT DEFAULT 0;

  DECLARE cur CURSOR FOR
    SELECT t.TABLE_NAME
      FROM information_schema.TABLES t
     WHERE t.TABLE_SCHEMA = DATABASE()
       AND t.TABLE_TYPE   = 'BASE TABLE'
       AND t.TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'
       AND t.TABLE_NAME NOT IN (
           SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> '')
     ORDER BY (t.DATA_LENGTH + t.INDEX_LENGTH) ASC;

  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO tname;
    IF done = 1 THEN LEAVE read_loop; END IF;

    SET @ddl := CONCAT('ALTER TABLE `', tname,
                       '` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci');
    PREPARE s FROM @ddl;
    EXECUTE s;
    DEALLOCATE PREPARE s;

    SET n = n + 1;
  END LOOP;
  CLOSE cur;

  SELECT n AS tables_converted;
END$$
DELIMITER ;

CALL kashi_convert_collation();

DROP PROCEDURE IF EXISTS kashi_convert_collation;

-- If it stops partway, the error names the table. Handle that one by hand —
-- usually an index-length or foreign-key edge case — then CALL again.


-- ══ STEP 3 — verify ═══════════════════════════════════════════════════════

-- Collations. Expect two rows at most: utf8mb4_0900_ai_ci for ~166 tables, and
-- utf8mb4_unicode_ci for the single deferred generated-column table.
SELECT TABLE_COLLATION, COUNT(*) AS tables_
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
 GROUP BY TABLE_COLLATION;

-- The two joins that threw 1267 all evening. Both must now return a number,
-- and both must match your baseline. No COLLATE clause — that is the point.
SELECT 'tests anchored to a live leaf' metric_, COUNT(*) actual_, 213 AS baseline_,
       IF(COUNT(*)=213,'OK','INVESTIGATE') verdict_
  FROM audit_tests t JOIN common_controls c ON c.code = t.control_tag
 WHERE c.node_level='CONTROL' AND c.is_active=1
UNION ALL
SELECT 'audit_controls resolving to ucf', COUNT(DISTINCT a.common_control_code), 61,
       IF(COUNT(DISTINCT a.common_control_code)=61,'OK','INVESTIGATE')
  FROM audit_controls a JOIN common_controls c ON c.code = a.common_control_code
 WHERE c.node_level='CONTROL' AND c.is_active=1;

-- Templates 28 and 12. Must read 116 and 41.
SELECT tsm.template_id, COUNT(DISTINCT scm.control_id) AS controls_
  FROM audit_template_section_mappings tsm
  JOIN audit_sections root ON root.id = tsm.section_id
  JOIN audit_sections s ON s.id = root.id OR s.path LIKE CONCAT(root.path,'%')
  JOIN audit_section_control_mappings scm ON scm.section_id = s.id
 WHERE tsm.template_id IN (28,12) GROUP BY tsm.template_id;

-- Integration surface. MUST be 19.
SELECT COUNT(*) AS automated_tests,
       IF(COUNT(*)=19,'OK','STOP — INTEGRATION AT RISK') verdict_
  FROM audit_tests WHERE automation_key IS NOT NULL;

-- Every automated test still anchored to a live leaf. Expect ZERO rows.
SELECT t.id, t.automation_key, t.control_tag
  FROM audit_tests t
  LEFT JOIN common_controls c
    ON c.code = t.control_tag AND c.node_level='CONTROL' AND c.is_active=1
 WHERE t.automation_key IS NOT NULL AND c.code IS NULL;


-- ══ STEP 4 — run an integration check ═════════════════════════════════════
-- Before moving on, trigger one real integration run and confirm a result
-- lands on its test instance. The queries above prove the schema is consistent;
-- only an actual run proves the checkKey routing still works end to end.
--
-- This is the assurance you actually wanted from all of this. Do not skip it.


-- ══ THEN ══════════════════════════════════════════════════════════════════
--   21a-undo-failed-v1.sql    clears the failed v1 rows (tests +54, mappings +57)
--   20-cleanup-debris.sql     removes testing debris
--   21-seed-dpdpa.sql   v3    seeds DPDPA
--
-- After 20, re-check templates 28 and 12 at 116 and 41 before running 21.


-- ══ AFTERWARDS — stop it drifting again ═══════════════════════════════════
-- Your database default is already utf8mb4_0900_ai_ci, so new tables land
-- correctly on their own, including the seven ai_* tables ddl-auto will create.
--
-- Add this to a startup health check so a stray CREATE TABLE with an explicit
-- collation shows up as a log line rather than as a 1267 two years from now:
--
--   SELECT COUNT(DISTINCT TABLE_COLLATION) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';
--
-- It will read 2 until the deferred generated-column table is converted. Either
-- exclude that table by name in the check, or clear the backlog item and let it
-- read 1.
