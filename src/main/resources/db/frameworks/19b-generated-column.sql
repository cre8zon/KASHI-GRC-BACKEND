-- ═══════════════════════════════════════════════════════════════════════════
-- 19b — The generated column                          [fixes my 1054 error]
--
-- ── MY BUG ─────────────────────────────────────────────────────────────────
--   Error 1054. Unknown column 'IS_GENERATED' in field list
--
-- There is no IS_GENERATED column in MySQL's information_schema.COLUMNS. I
-- wrote it from memory. The generated-column indicator in MySQL 8 lives in
-- EXTRA, which holds 'VIRTUAL GENERATED' or 'STORED GENERATED', alongside
-- GENERATION_EXPRESSION. Corrected below.
--
-- ── WHAT YOUR RUN ALREADY TOLD US ──────────────────────────────────────────
-- Statement 418 — indexes over generated columns — returned 1 row.
--
-- So the generated column IS indexed. That is the harder of the three cases:
-- CONVERT TO will not rebuild it in place, and the index has to come off before
-- the column can. Plan for drop → convert → re-add rather than hoping.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1. What it is ──────────────────────────────────────────────────────────
SELECT TABLE_NAME,
       COLUMN_NAME,
       COLUMN_TYPE,
       EXTRA,                       -- 'VIRTUAL GENERATED' or 'STORED GENERATED'
       GENERATION_EXPRESSION,
       COLLATION_NAME,
       IS_NULLABLE
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND GENERATION_EXPRESSION <> '';

-- ── 2. Which index, and is it unique ───────────────────────────────────────
SELECT s.TABLE_NAME, s.INDEX_NAME, s.COLUMN_NAME, s.SEQ_IN_INDEX,
       s.NON_UNIQUE, s.INDEX_TYPE
  FROM information_schema.STATISTICS s
 WHERE s.TABLE_SCHEMA = DATABASE()
   AND (s.TABLE_NAME, s.COLUMN_NAME) IN (
       SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> '')
 ORDER BY s.TABLE_NAME, s.INDEX_NAME, s.SEQ_IN_INDEX;

-- If SEQ_IN_INDEX shows other columns in the same INDEX_NAME, it is a composite
-- index and the whole index must be dropped and recreated with every column in
-- its original order. Get the full picture:
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols_,
       MAX(NON_UNIQUE) AS non_unique_
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = (SELECT TABLE_NAME FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> '' LIMIT 1)
 GROUP BY INDEX_NAME;

-- ── 3. Does it even need converting? ───────────────────────────────────────
-- If the table is already on utf8mb4_0900_ai_ci it is not among the 123 and
-- there is nothing to do. Check before planning any surgery.
SELECT TABLE_NAME, TABLE_COLLATION,
       IF(TABLE_COLLATION = 'utf8mb4_0900_ai_ci',
          'ALREADY CORRECT — no action needed',
          'IN SCOPE — needs the drop/convert/re-add dance') AS verdict_
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = (SELECT TABLE_NAME FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> '' LIMIT 1);

-- ── 4. Capture the authoritative definition ────────────────────────────────
-- Run this and KEEP THE OUTPUT. It is the only complete record of the column
-- and index definitions, and you need it to put them back exactly.
--
--   SHOW CREATE TABLE `<table from query 1>`;
--
-- Paste that output to me and I will write the exact drop/convert/re-add
-- statements. Do not reconstruct it by hand — a generated column recreated with
-- a subtly different expression is a bug that surfaces weeks later in whatever
-- feature depends on it.

-- ── 5. The shape of the fix, for reference ─────────────────────────────────
-- Do not run this until you have the real names from steps 1, 2 and 4.
--
--   ALTER TABLE `t` DROP INDEX `idx_name`;
--   ALTER TABLE `t` DROP COLUMN `gen_col`;
--   ALTER TABLE `t` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--   ALTER TABLE `t` ADD COLUMN `gen_col` <type> AS (<expression>) VIRTUAL;
--   ALTER TABLE `t` ADD INDEX `idx_name` (`gen_col`);
--
-- A VIRTUAL generated column stores nothing, so dropping and re-adding it costs
-- only the index rebuild. If EXTRA says STORED, the values are recomputed on
-- re-add, which is slower but equally safe.
--
-- ── OR: SKIP IT ────────────────────────────────────────────────────────────
-- 122 of 123 tables convert with no drama. If this one table is not on a join
-- path the AI module or the UCF uses, leaving it on utf8mb4_unicode_ci for now
-- is a reasonable trade — you get an unblocked module tonight and one tidy-up
-- item on the backlog rather than a 2am schema operation.
--
-- Check whether it matters:
SELECT TABLE_NAME, COLUMN_NAME, COLLATION_NAME
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = (SELECT TABLE_NAME FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND GENERATION_EXPRESSION <> '' LIMIT 1)
   AND COLUMN_NAME IN ('code','common_control_code','control_tag','control_tag_snapshot',
                       'parent_code','domain_code','framework_ref','citation','section_code');
-- Zero rows means this table shares no join column with the UCF. Defer it.
