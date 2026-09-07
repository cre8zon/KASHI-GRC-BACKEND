-- ═══════════════════════════════════════════════════════════════════════════
-- 19c — Padding check, three ways
--
-- The problem with 19a section A: it returns ONE cell containing a 480-branch
-- SQL string. Getting that out of the Workbench grid and back into an editor is
-- fiddly and easy to truncate. Skip the copy-paste entirely.
--
-- Run METHOD 1. It takes ten seconds and covers essentially all of the real
-- risk. Run METHOD 2 if you want the exhaustive sweep. METHOD 3 is the manual
-- fallback if your Aiven user cannot use prepared statements.
-- ═══════════════════════════════════════════════════════════════════════════


-- ══ METHOD 1 — the columns that actually matter ═══════════════════════════
-- Padding only changes behaviour in COMPARISONS. A trailing space in a
-- `description` or `content_body` is invisible: nothing joins on it, nothing
-- looks it up. What matters is the columns used for joins, lookups and unique
-- keys — and in practice those are the indexed ones.
--
-- This is hand-written, no generation, no prepared statement. Every column here
-- is one the UCF or the audit module actually compares on.
-- Copy, run, read. Expect every count to be 0.

SELECT 'common_controls.code'                 col_, COUNT(*) padded_ FROM common_controls          WHERE code                 <> TRIM(code)
UNION ALL SELECT 'common_controls.parent_code',      COUNT(*) FROM common_controls          WHERE parent_code          <> TRIM(parent_code)
UNION ALL SELECT 'common_controls.domain_code',      COUNT(*) FROM common_controls          WHERE domain_code          <> TRIM(domain_code)
UNION ALL SELECT 'common_controls.node_level',       COUNT(*) FROM common_controls          WHERE node_level           <> TRIM(node_level)
UNION ALL SELECT 'ccm.common_control_code',          COUNT(*) FROM common_control_mappings  WHERE common_control_code  <> TRIM(common_control_code)
UNION ALL SELECT 'ccm.framework_ref',                COUNT(*) FROM common_control_mappings  WHERE framework_ref        <> TRIM(framework_ref)
UNION ALL SELECT 'ccm.citation',                     COUNT(*) FROM common_control_mappings  WHERE citation             <> TRIM(citation)
UNION ALL SELECT 'audit_controls.control_code',      COUNT(*) FROM audit_controls           WHERE control_code         <> TRIM(control_code)
UNION ALL SELECT 'audit_controls.control_tag',       COUNT(*) FROM audit_controls           WHERE control_tag          <> TRIM(control_tag)
UNION ALL SELECT 'audit_controls.common_control_code', COUNT(*) FROM audit_controls         WHERE common_control_code  <> TRIM(common_control_code)
UNION ALL SELECT 'audit_controls.framework_ref',     COUNT(*) FROM audit_controls           WHERE framework_ref        <> TRIM(framework_ref)
UNION ALL SELECT 'audit_tests.control_tag',          COUNT(*) FROM audit_tests              WHERE control_tag          <> TRIM(control_tag)
UNION ALL SELECT 'audit_tests.common_control_code',  COUNT(*) FROM audit_tests              WHERE common_control_code  <> TRIM(common_control_code)
UNION ALL SELECT 'audit_tests.framework_ref',        COUNT(*) FROM audit_tests              WHERE framework_ref        <> TRIM(framework_ref)
UNION ALL SELECT 'audit_tests.test_ref',             COUNT(*) FROM audit_tests              WHERE test_ref             <> TRIM(test_ref)
UNION ALL SELECT 'audit_tests.automation_key',       COUNT(*) FROM audit_tests              WHERE automation_key       <> TRIM(automation_key)
UNION ALL SELECT 'audit_sections.section_code',      COUNT(*) FROM audit_sections           WHERE section_code         <> TRIM(section_code)
UNION ALL SELECT 'audit_sections.framework_ref',     COUNT(*) FROM audit_sections           WHERE framework_ref        <> TRIM(framework_ref)
UNION ALL SELECT 'audit_sections.path',              COUNT(*) FROM audit_sections           WHERE path                 <> TRIM(path)
UNION ALL SELECT 'audit_templates.framework_ref',    COUNT(*) FROM audit_templates          WHERE framework_ref        <> TRIM(framework_ref)
UNION ALL SELECT 'audit_templates.template_name',    COUNT(*) FROM audit_templates          WHERE template_name        <> TRIM(template_name)
UNION ALL SELECT 'aci.control_tag_snapshot',         COUNT(*) FROM audit_control_instances  WHERE control_tag_snapshot <> TRIM(control_tag_snapshot)
UNION ALL SELECT 'aci.control_code_snapshot',        COUNT(*) FROM audit_control_instances  WHERE control_code_snapshot<> TRIM(control_code_snapshot)
ORDER BY padded_ DESC;

-- All zeros -> proceed to the alters.
-- Anything above zero -> fix it FIRST, see the TRIM block at the bottom.
--
-- If a column name here does not exist in your schema you will get error 1054.
-- Delete that one line and re-run; it means I guessed a name, not that anything
-- is wrong with your data.


-- ══ METHOD 2 — exhaustive sweep, no copy-paste ════════════════════════════
-- Same generated SQL as 19a, but executed via PREPARE so it never leaves the
-- server. Nothing to copy out of a grid cell, nothing to truncate.
--
-- Restricted to INDEXED string columns, which is the meaningful set and keeps
-- this to roughly 60-80 branches instead of 480. Indexed columns are the ones
-- used for joins, lookups and uniqueness — exactly where padding changes an
-- answer. Run METHOD 2b below if you want all 480 regardless.

SET SESSION group_concat_max_len = 4000000;

SET @sql := (
  SELECT CONCAT(
    'SELECT * FROM (',
    GROUP_CONCAT(
      CONCAT('SELECT ''', c.TABLE_NAME, '.', c.COLUMN_NAME, ''' AS col_, COUNT(*) AS padded_ FROM `',
             c.TABLE_NAME, '` WHERE `', c.COLUMN_NAME, '` <> TRIM(`', c.COLUMN_NAME, '`)')
      SEPARATOR ' UNION ALL '),
    ') z WHERE padded_ > 0 ORDER BY padded_ DESC')
    FROM information_schema.COLUMNS c
   WHERE c.TABLE_SCHEMA = DATABASE()
     AND c.DATA_TYPE IN ('varchar','char')
     AND c.TABLE_NAME NOT LIKE 'ai_%'
     AND EXISTS (SELECT 1 FROM information_schema.STATISTICS s
                  WHERE s.TABLE_SCHEMA = c.TABLE_SCHEMA
                    AND s.TABLE_NAME   = c.TABLE_NAME
                    AND s.COLUMN_NAME  = c.COLUMN_NAME)
);

-- Sanity: should be a long string, not NULL. NULL means group_concat_max_len
-- was not raised or the filter matched nothing.
SELECT CHAR_LENGTH(@sql) AS sql_length, LEFT(@sql, 200) AS starts_with;

PREPARE padcheck FROM @sql;
EXECUTE padcheck;
DEALLOCATE PREPARE padcheck;

-- ZERO ROWS RETURNED = clean. The WHERE padded_ > 0 wrapper means only
-- offenders appear, so an empty result set is the pass condition.


-- ══ METHOD 2b — all 480 columns ═══════════════════════════════════════════
-- Same thing without the indexed-column filter. Slower: it full-scans every
-- table once per string column. Fine overnight, unnecessary before the alters.

-- SET SESSION group_concat_max_len = 8000000;
-- SET @sql := (
--   SELECT CONCAT('SELECT * FROM (',
--     GROUP_CONCAT(CONCAT('SELECT ''', TABLE_NAME, '.', COLUMN_NAME,
--       ''' AS col_, COUNT(*) AS padded_ FROM `', TABLE_NAME,
--       '` WHERE `', COLUMN_NAME, '` <> TRIM(`', COLUMN_NAME, '`)')
--       SEPARATOR ' UNION ALL '),
--     ') z WHERE padded_ > 0 ORDER BY padded_ DESC')
--     FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND DATA_TYPE IN ('varchar','char')
--      AND TABLE_NAME NOT LIKE 'ai_%');
-- PREPARE padcheck FROM @sql; EXECUTE padcheck; DEALLOCATE PREPARE padcheck;


-- ══ METHOD 3 — manual, if PREPARE is unavailable ══════════════════════════
-- Some managed users have prepared statements restricted. If PREPARE errors:
--
--   1. Run the 19a section A generator. It returns one row, one cell.
--   2. Right-click that cell -> "Open Value in Viewer"  (or Ctrl+Alt+V).
--   3. In the viewer, Text tab -> select all -> copy.
--   4. New query tab, paste, run.
--
-- Watch for truncation: the viewer shows the full value but Workbench's grid
-- preview does not. If the pasted SQL ends mid-word, group_concat_max_len was
-- too low — raise it and regenerate.


-- ══ IF ANYTHING COMES BACK NON-ZERO ═══════════════════════════════════════
-- Fix it BEFORE converting, while PAD SPACE still treats 'ABC ' and 'ABC' as
-- equal. After conversion those rows have already stopped matching and you are
-- repairing damage rather than preventing it.
--
--   UPDATE `table_name` SET `col` = TRIM(`col`) WHERE `col` <> TRIM(`col`);
--
-- Then re-run the check and confirm zero before proceeding.
--
-- One caution: if the padded column is part of a UNIQUE index, TRIM can create
-- a duplicate-key collision — 'ABC ' and 'ABC' are distinct stored values but
-- collide once trimmed. Check first:
--
--   SELECT TRIM(col), COUNT(*) FROM `table_name`
--    GROUP BY TRIM(col) HAVING COUNT(*) > 1;
--
-- If that returns rows, decide which of the near-duplicates is correct before
-- trimming. Do not bulk-trim into a unique constraint.
