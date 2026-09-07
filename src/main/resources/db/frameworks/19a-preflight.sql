-- ═══════════════════════════════════════════════════════════════════════════
-- 19a — Pre-flight, from your actual run of 19
--
-- Your output flagged two things and revealed a third.
--
--   statement 404   480 rows   -> 480 string columns to padding-check.
--                                 One SELECT each is unworkable by hand.
--                                 Section A builds it as a single query.
--   statement 405     1 row    -> ONE GENERATED COLUMN. This is a genuine
--                                 CONVERT TO blocker. Section B.
--   statement 406     0 rows   -> no index-length risk. Clear.
--   statement 414     baseline -> captured, and it disagrees with my exports.
--                                 Section C.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── A. Padding check, as ONE query ─────────────────────────────────────────
-- GROUP_CONCAT defaults to 1024 bytes and would silently truncate a 480-column
-- statement, so raise it first. Silent truncation here means a half-built query
-- that runs fine and checks half your columns.

SET SESSION group_concat_max_len = 4000000;

SELECT CONCAT(
  GROUP_CONCAT(
    CONCAT('SELECT ''', TABLE_NAME, '.', COLUMN_NAME, ''' AS col_, COUNT(*) AS padded_ FROM `',
           TABLE_NAME, '` WHERE `', COLUMN_NAME, '` <> TRIM(`', COLUMN_NAME, '`)')
    SEPARATOR ' UNION ALL '),
  ' ORDER BY padded_ DESC'
) AS run_this
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND DATA_TYPE IN ('varchar','char')
   AND TABLE_NAME NOT LIKE 'ai_%';

-- Copy the single cell, run it, and look only at rows where padded_ > 0.
-- Expect zero. If any come back, fix them BEFORE converting, while PAD SPACE
-- still treats the padded and unpadded forms as equal:
--
--   UPDATE `table` SET `col` = TRIM(`col`) WHERE `col` <> TRIM(`col`);
--
-- After conversion those rows have already stopped joining, silently, and you
-- would be repairing the damage rather than preventing it.

-- ── B. The generated column ────────────────────────────────────────────────
-- CONVERT TO CHARACTER SET fails on a table with a generated column whose
-- expression depends on a character column — MySQL will not rebuild it in
-- place. Find out what it is before you plan the window.

SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_GENERATED,
       EXTRA, GENERATION_EXPRESSION, COLLATION_NAME
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> '';

-- Is it indexed? A functional index over a generated column is the harder case.
SELECT s.TABLE_NAME, s.INDEX_NAME, s.COLUMN_NAME, s.SEQ_IN_INDEX
  FROM information_schema.STATISTICS s
 WHERE s.TABLE_SCHEMA = DATABASE()
   AND (s.TABLE_NAME, s.COLUMN_NAME) IN (
       SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> '');

-- Three outcomes, depending on what the two queries above show:
--
--   1. The column is on a table ALREADY on utf8mb4_0900_ai_ci
--      -> nothing to do. It is not in the 123.
--
--   2. VIRTUAL, not indexed
--      -> usually converts without complaint. Try it; if it errors, use (3).
--
--   3. STORED, or indexed, or it errors
--      -> drop, convert, re-add. Capture the definition first:
--
--         SHOW CREATE TABLE `that_table`;
--
--         ALTER TABLE `t` DROP INDEX `idx_on_generated`;      -- if indexed
--         ALTER TABLE `t` DROP COLUMN `generated_col`;
--         ALTER TABLE `t` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--         ALTER TABLE `t` ADD COLUMN `generated_col` ... AS (...) VIRTUAL;
--         ALTER TABLE `t` ADD INDEX `idx_on_generated` (`generated_col`);
--
-- Paste me the output of the first query and I will write the exact statements
-- rather than leaving you to reconstruct the definition at 2am.

-- ── C. Your baseline disagrees with my exports ─────────────────────────────
-- Statement 414 gave:
--
--     controls    166        my export: 220
--     tests       213        my export: 159
--     mappings    279        my export: 222
--     ucf leaves  134        my export: 134   (matches)
--     automated    19        my export:  19   (matches)
--     instances  4514        not in my exports
--
-- Tests +54 and mappings +57 are the failed v1 DPDPA run: its audit_tests and
-- common_control_mappings INSERTs committed even though the audit_controls
-- INSERTs were rejected on the enum. 21a-undo-failed-v1.sql clears both.
--
-- Controls at 166 rather than 220 is NOT explained by anything I sent — none of
-- my scripts had deleted a control at that point. Either the other chat has been
-- working on this table, or part of 20-cleanup-debris.sql was run. Worth knowing
-- which before you go further:

SELECT framework_ref, COUNT(*) FROM audit_controls GROUP BY framework_ref ORDER BY 2 DESC;
SELECT MAX(updated_at) AS last_touched, COUNT(*) AS changed_today
  FROM audit_controls WHERE updated_at >= CURDATE();

-- ── CONSEQUENCE: my hardcoded verification numbers are stale ───────────────
-- "expect 157", "expect ~58", "expect 116" throughout scripts 19-21 came from
-- my exports. Derive them from the live database instead and use those:

SELECT 'tests anchored to a live leaf' metric_, COUNT(*) value_
  FROM audit_tests t JOIN common_controls c
    ON c.code COLLATE utf8mb4_0900_ai_ci = t.control_tag COLLATE utf8mb4_0900_ai_ci
 WHERE c.node_level='CONTROL' AND c.is_active=1
UNION ALL
SELECT 'audit_controls resolving to ucf', COUNT(DISTINCT a.common_control_code)
  FROM audit_controls a JOIN common_controls c
    ON c.code COLLATE utf8mb4_0900_ai_ci = a.common_control_code COLLATE utf8mb4_0900_ai_ci
 WHERE c.node_level='CONTROL' AND c.is_active=1
UNION ALL
SELECT 'controls reachable from template 28', COUNT(DISTINCT scm.control_id)
  FROM audit_template_section_mappings tsm
  JOIN audit_sections root ON root.id = tsm.section_id
  JOIN audit_sections s ON s.id = root.id OR s.path LIKE CONCAT(root.path,'%')
  JOIN audit_section_control_mappings scm ON scm.section_id = s.id
 WHERE tsm.template_id = 28
UNION ALL
SELECT 'controls reachable from template 12', COUNT(DISTINCT scm.control_id)
  FROM audit_template_section_mappings tsm
  JOIN audit_sections root ON root.id = tsm.section_id
  JOIN audit_sections s ON s.id = root.id OR s.path LIKE CONCAT(root.path,'%')
  JOIN audit_section_control_mappings scm ON scm.section_id = s.id
 WHERE tsm.template_id = 12;

-- These four numbers are the real baseline. Record them, and every verification
-- step afterwards compares against these rather than against my figures.
-- COLLATE is explicit so this runs before the conversion.

-- ── ORDER, RESTATED ────────────────────────────────────────────────────────
--   1. Section A — padding check. Must be empty.
--   2. Section B — resolve the generated column.
--   3. Section C — record the live baseline.
--   4. 19 step 4 — the 123 alters, smallest first.
--   5. 19 step 6 — verify against the section C numbers, not mine.
--   6. 21a  -> 20  -> 21
