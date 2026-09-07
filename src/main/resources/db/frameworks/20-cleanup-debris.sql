-- ═══════════════════════════════════════════════════════════════════════════
-- 20 — Remove testing debris, keep ISO 27001:2022 (tpl 28) and SOC 2 (tpl 12)
--
-- ── READ THIS BEFORE RUNNING ───────────────────────────────────────────────
-- Every protection below is computed LIVE by the script, not hardcoded from my
-- analysis. That is deliberate: your audit_control_instance_test_mappings
-- export came back at exactly 1000 rows, which is an export limit, not a row
-- count. I do not know what the other rows reference, so nothing here trusts a
-- list I derived offline.
--
-- Three things are protected unconditionally, in this order:
--   1. anything reachable from template 28 or 12
--   2. anything referenced by ANY engagement instance (running or historical)
--   3. any audit_test carrying an automation_key
--
-- Rule 3 is absolute. Your integrations work and an automated check whose test
-- row disappears is the one failure mode you told me to avoid.
--
-- PREREQUISITE: run 19-collation-fix.sql first. Your audit_* tables are
-- utf8mb4_unicode_ci and common_controls is utf8mb4_0900_ai_ci, so any join
-- between them throws error 1267. Every cross-table comparison below carries an
-- explicit COLLATE so this script survives either way — but the AI module does
-- not, so fix the schema regardless.
--
-- RUN IN A TRANSACTION. Inspect every SELECT before the DELETE beneath it.
-- Take a backup first — these are hard deletes, unlike everything else I have
-- sent you.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── STEP 0. Build the keep-sets as real tables ─────────────────────────────
-- Materialised rather than inlined so you can inspect them, and so every
-- DELETE below reads the same definition of "protected".

DROP TEMPORARY TABLE IF EXISTS keep_sections;
DROP TEMPORARY TABLE IF EXISTS keep_controls;
DROP TEMPORARY TABLE IF EXISTS keep_tests;

-- Sections reachable from templates 28 and 12, at any depth.
-- Uses the materialised path, so run 09-section-path-integrity.sql FIRST or the
-- bare-path rows ("651/652") will not match the LIKE and their sections will be
-- wrongly treated as orphans.
CREATE TEMPORARY TABLE keep_sections (id BIGINT PRIMARY KEY);
INSERT INTO keep_sections (id)
SELECT DISTINCT s.id
  FROM audit_sections s
  JOIN audit_sections root
    ON s.id = root.id
    OR s.path LIKE CONCAT(root.path, '%')
    OR s.path LIKE CONCAT('/', TRIM(BOTH '/' FROM root.path), '/%')
 WHERE root.id IN (SELECT section_id FROM audit_template_section_mappings
                    WHERE template_id IN (28, 12));

SELECT COUNT(*) AS sections_kept FROM keep_sections;   -- expect ~85

-- Controls: on a kept section, OR referenced by any engagement instance.
CREATE TEMPORARY TABLE keep_controls (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO keep_controls (id)
SELECT DISTINCT scm.control_id
  FROM audit_section_control_mappings scm
  JOIN keep_sections ks ON ks.id = scm.section_id;
INSERT IGNORE INTO keep_controls (id)
SELECT DISTINCT original_control_id
  FROM audit_control_instance_test_mappings
 WHERE original_control_id IS NOT NULL;

SELECT COUNT(*) AS controls_kept FROM keep_controls;   -- expect ~157+

-- Tests: framework matches a kept framework, OR instance-referenced, OR
-- automated. The automation clause is the one that must never be relaxed.
CREATE TEMPORARY TABLE keep_tests (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO keep_tests (id)
SELECT id FROM audit_tests WHERE automation_key IS NOT NULL;
INSERT IGNORE INTO keep_tests (id)
SELECT DISTINCT original_test_id FROM audit_control_instance_test_mappings
 WHERE original_test_id IS NOT NULL;
INSERT IGNORE INTO keep_tests (id)
SELECT id FROM audit_tests
 WHERE control_tag COLLATE utf8mb4_0900_ai_ci IN (
           SELECT code COLLATE utf8mb4_0900_ai_ci FROM common_controls
            WHERE node_level = 'CONTROL' AND is_active = 1);

SELECT COUNT(*) AS tests_kept FROM keep_tests;         -- expect 157

-- ── STEP 1. Templates ──────────────────────────────────────────────────────
-- 16, 25, 26 are superseded ISO drafts (all UNPUBLISHED, is_active = 0).
-- 18 'RBI SAR PSS kdsksdv' is PUBLISHED and active — a demo hazard.
-- 21 'RBI PA' and 27 'RBI ITGRC' are real work-in-progress. They are NOT
-- deleted here; the DPDPA/CERT-In seeds replace this ground properly and you
-- may want the RBI structure as reference. Unpublish instead of destroying.

SELECT id, template_name, framework_ref, status, is_active
  FROM audit_templates WHERE id NOT IN (28, 12);

START TRANSACTION;

UPDATE audit_templates SET is_active = 0, status = 'UNPUBLISHED', unpublished_at = NOW(6)
 WHERE id IN (21, 27);

DELETE FROM audit_template_section_mappings WHERE template_id IN (16, 18, 25, 26);
DELETE FROM audit_templates                 WHERE id          IN (16, 18, 25, 26);

COMMIT;

-- ── STEP 2. Sections ───────────────────────────────────────────────────────
-- Anything not under 28/12 and not under a surviving RBI template.

SELECT s.id, s.section_code, s.name, s.framework_ref, s.depth
  FROM audit_sections s
 WHERE s.id NOT IN (SELECT id FROM keep_sections)
   AND s.id NOT IN (
       SELECT s2.id FROM audit_sections s2
        JOIN audit_sections r
          ON s2.id = r.id OR s2.path LIKE CONCAT(r.path, '%')
        WHERE r.id IN (SELECT section_id FROM audit_template_section_mappings))
 ORDER BY s.depth DESC, s.id;

START TRANSACTION;

DELETE FROM audit_section_control_mappings
 WHERE section_id NOT IN (SELECT id FROM keep_sections)
   AND section_id NOT IN (SELECT section_id FROM audit_template_section_mappings);

-- Deepest first so no row is orphaned mid-delete.
DELETE FROM audit_sections
 WHERE id NOT IN (SELECT id FROM keep_sections)
   AND id NOT IN (SELECT section_id FROM audit_template_section_mappings)
   AND id NOT IN (SELECT DISTINCT parent_id FROM (SELECT parent_id FROM audit_sections) x
                   WHERE parent_id IS NOT NULL);

COMMIT;

-- Re-run the DELETE above until it affects 0 rows: each pass removes one level
-- of leaves. Three passes clears a three-deep tree. Doing it iteratively avoids
-- a self-referential DELETE, which MySQL will not accept in one statement.

-- ── STEP 3. Controls ───────────────────────────────────────────────────────

SELECT id, control_code, framework_ref, name
  FROM audit_controls
 WHERE id NOT IN (SELECT id FROM keep_controls)
 ORDER BY framework_ref, control_code;

START TRANSACTION;

DELETE FROM audit_section_control_mappings
 WHERE control_id NOT IN (SELECT id FROM keep_controls);

DELETE FROM audit_controls
 WHERE id NOT IN (SELECT id FROM keep_controls);

COMMIT;

-- ── STEP 4. Tests ──────────────────────────────────────────────────────────
-- Only two rows qualify, and neither has an automation_key:
--   123  name 'ggghb'      test_ref 'jbjbj'  tenant_id 1  no control_tag
--   124  name 'test name'  test_ref 't-ref'  tenant_id 1  AUTOMATED but no key
--
-- Note 124 is automation_type = AUTOMATED with automation_key NULL. It cannot
-- receive integration results — an automated test with no checkKey is inert —
-- so removing it does not touch a working check. Confirm that yourself with the
-- SELECT before deleting.

SELECT id, test_ref, name, framework_ref, control_tag, automation_type, automation_key, tenant_id
  FROM audit_tests
 WHERE id NOT IN (SELECT id FROM keep_tests);

START TRANSACTION;

DELETE FROM audit_tests
 WHERE id NOT IN (SELECT id FROM keep_tests)
   AND automation_key IS NULL;                       -- belt and braces

COMMIT;

-- ── STEP 5. Repair, do not delete, test 122 ────────────────────────────────
-- Column shift on import: the description landed in framework_ref and name is
-- NULL. This is a REAL test (Antimalware Active Check, END-01.1, AT-0041) and
-- should be fixed rather than removed.

SELECT id, test_ref, name, description, framework_ref FROM audit_tests WHERE id = 122;

START TRANSACTION;

UPDATE audit_tests
   SET name          = 'Antimalware Active Check',
       description   = 'Verify antimalware software is active and up-to-date on all endpoints',
       framework_ref = NULL,
       updated_at    = NOW(6)
 WHERE id = 122
   AND CHAR_LENGTH(framework_ref) > 20;              -- only if still unrepaired

COMMIT;

-- ── STEP 6. Policies ───────────────────────────────────────────────────────
-- Keyed on content, not on ids, because policy ids may have moved since my
-- export. POL-01..POL-26 carry real bodies and are kept.

SELECT id, policy_ref, title, status, CHAR_LENGTH(content_body) AS len
  FROM audit_policies
 WHERE content_body IS NULL
    OR CHAR_LENGTH(content_body) < 200
    OR content_body LIKE '%content pending%'
 ORDER BY status, policy_ref;

START TRANSACTION;

-- Empty placeholders must not be APPROVED: AuditTestPolicySnapshotService only
-- snapshots APPROVED policies, so an empty approved policy propagates a blank
-- into every new engagement.
UPDATE audit_policies
   SET status = 'DRAFT', updated_at = NOW(6)
 WHERE status = 'APPROVED'
   AND (content_body IS NULL
        OR CHAR_LENGTH(content_body) < 200
        OR content_body LIKE '%content pending%');

-- Genuinely nameless junk only.
DELETE FROM audit_policy_control_mappings
 WHERE policy_id IN (SELECT id FROM audit_policies
                      WHERE (title IS NULL OR title = '')
                        AND (content_body IS NULL OR CHAR_LENGTH(content_body) < 50));

DELETE FROM audit_policies
 WHERE (title IS NULL OR title = '')
   AND (content_body IS NULL OR CHAR_LENGTH(content_body) < 50);

COMMIT;

-- ── STEP 7. Verify ─────────────────────────────────────────────────────────

SELECT 'templates' t, COUNT(*) FROM audit_templates
UNION ALL SELECT 'sections',  COUNT(*) FROM audit_sections
UNION ALL SELECT 'controls',  COUNT(*) FROM audit_controls
UNION ALL SELECT 'tests',     COUNT(*) FROM audit_tests
UNION ALL SELECT 'automated tests', COUNT(*) FROM audit_tests WHERE automation_key IS NOT NULL;
-- automated tests MUST still be 19.

-- Template 28 must still resolve to 116 controls.
SELECT COUNT(*) AS tpl28_controls
  FROM audit_section_control_mappings scm
  JOIN audit_sections s ON s.id = scm.section_id
 WHERE s.path LIKE '/651/%' OR s.path LIKE '/682/%' OR s.id IN (651, 682);

-- Every automated test still anchored to a live UCF leaf. Expect zero rows.
SELECT t.id, t.automation_key, t.control_tag
  FROM audit_tests t
  LEFT JOIN common_controls c
    ON c.code COLLATE utf8mb4_0900_ai_ci = t.control_tag COLLATE utf8mb4_0900_ai_ci
   AND c.node_level = 'CONTROL' AND c.is_active = 1
 WHERE t.automation_key IS NOT NULL AND c.code IS NULL;

DROP TEMPORARY TABLE IF EXISTS keep_sections;
DROP TEMPORARY TABLE IF EXISTS keep_controls;
DROP TEMPORARY TABLE IF EXISTS keep_tests;
