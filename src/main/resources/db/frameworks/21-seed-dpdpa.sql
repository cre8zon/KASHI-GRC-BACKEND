-- ═══════════════════════════════════════════════════════════════════════════
-- 21 — Seed: DPDP Act 2023 + DPDP Rules 2025 (India)          [v3]
--
-- ── v3 FIXES A BUG v2 STILL HAD ────────────────────────────────────────────
-- Found on re-reading, before you hit it. The section-control mapping INSERT
-- used a CORRELATED DERIVED TABLE:
--
--   WHERE NOT EXISTS (SELECT 1 FROM (SELECT m.id FROM audit_section_control_mappings m
--                                     WHERE m.section_id = sec.id) x)
--
-- MySQL derived tables are not correlated unless declared LATERAL, so `sec.id`
-- is out of scope inside that subquery and it raises
--   Error 1054. Unknown column 'sec.id' in 'where clause'
-- on all 57 statements.
--
-- I wrapped it in a derived table to dodge the "can't SELECT from the table you
-- are INSERTing into" restriction, and in doing so broke the correlation. v3
-- uses a LEFT JOIN anti-join instead: the derived table stays uncorrelated and
-- the ON clause does the matching. Same idempotency, valid SQL.
--
-- ── v2 FIXES, STILL IN PLACE ───────────────────────────────────────────────
--   test_type   DOCUMENT_REVIEW / TECHNICAL_TEST / OBSERVATION  (was invented)
--   audit_type  EXTERNAL                                        (was COMPLIANCE)
--   STOP checks after each variable assignment
--   COLLATE on every cross-table comparison
--
-- ── VERIFICATION IS NOW LIVE-DERIVED ───────────────────────────────────────
-- My earlier "expect 157 / expect 58" numbers came from exports that no longer
-- match your database. Section 7 computes the expectations from the data
-- instead of asserting my stale figures.
--
-- ── ORDER ──────────────────────────────────────────────────────────────────
--   19 padding check (must be empty)  ->  19 alters  ->  21a  ->  20  ->  THIS
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 0. Confirm the enums ───────────────────────────────────────────────────
SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND ((TABLE_NAME='audit_controls'  AND COLUMN_NAME='test_type')
     OR (TABLE_NAME='audit_templates' AND COLUMN_NAME IN ('audit_type','status'))
     OR (TABLE_NAME='audit_tests'     AND COLUMN_NAME IN ('frequency','automation_type')));

-- ── 1. Template ────────────────────────────────────────────────────────────
START TRANSACTION;

INSERT INTO audit_templates
  (tenant_id, template_name, framework_code, framework_ref, name, version, description,
   audit_type, status, is_active, created_at, updated_at)
SELECT NULL, 'KashiGRC DPDP Act 2023 + Rules 2025', 'DPDPA', 'DPDPA',
       'DPDP Act 2023 and Rules 2025', 1,
       'India Digital Personal Data Protection Act 2023 as operationalised by the DPDP Rules 2025 (G.S.R. 846(E), 13 November 2025). Full compliance due 13 May 2027.',
       'EXTERNAL', 'DRAFT', 1, NOW(6), NOW(6)
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_templates
                    WHERE template_name = 'KashiGRC DPDP Act 2023 + Rules 2025') x);

SET @tpl := (SELECT id FROM audit_templates WHERE template_name = 'KashiGRC DPDP Act 2023 + Rules 2025');
SELECT @tpl AS template_id, IF(@tpl IS NULL,'STOP — template insert failed','OK') AS status_;

-- ── 2. Root section ────────────────────────────────────────────────────────
INSERT INTO audit_sections
  (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDPA', 'DPDP Act 2023 and Rules 2025',
       'Obligations of a Data Fiduciary under the Act and the operative Rules.',
       'DPDPA', 0, NULL, 1, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDPA') x);

SET @root := (SELECT id FROM audit_sections WHERE section_code = 'DPDPA');
UPDATE audit_sections SET path = CONCAT('/', @root, '/') WHERE id = @root;
SELECT @root AS root_section_id, IF(@root IS NULL,'STOP — root section failed','OK') AS status_;

INSERT INTO audit_template_section_mappings (template_id, section_id, order_no, created_at, updated_at)
SELECT @tpl, @root, 1, NOW(6), NOW(6) FROM DUAL
 WHERE @tpl IS NOT NULL AND @root IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM (SELECT 1 AS one_ FROM audit_template_section_mappings
                    WHERE template_id=@tpl AND section_id=@root) x);

COMMIT;

-- ── 3. Sections ────────────────────────────────────────────────────────────
START TRANSACTION;

INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-A', 'Notice and Consent', 'Sections 5-6 of the Act; Rules 3-4', 'DPDPA', 1, @root, 10, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-A') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-A');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;
INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-B', 'Purpose Limitation and Minimisation', 'Sections 4, 8(1)-8(3)', 'DPDPA', 1, @root, 20, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-B') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-B');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;
INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-C', 'Security Safeguards', 'Section 8(4); Rule 6', 'DPDPA', 1, @root, 30, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-C') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-C');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;
INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-D', 'Personal Data Breach Response', 'Section 8(6); Rule 7', 'DPDPA', 1, @root, 40, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-D') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-D');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;
INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-E', 'Retention and Erasure', 'Section 8(7); Rule 8 and Third Schedule', 'DPDPA', 1, @root, 50, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-E') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-E');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;
INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-F', 'Data Principal Rights', 'Sections 11-14; Rules 13-14', 'DPDPA', 1, @root, 60, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-F') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-F');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;
INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-G', 'Children and Persons with Disability', 'Section 9; Rule 10', 'DPDPA', 1, @root, 70, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-G') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-G');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;
INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-H', 'Governance and Accountability', 'Sections 8(5), 8(9)-8(11); Rule 9', 'DPDPA', 1, @root, 80, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-H') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-H');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;
INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-I', 'Significant Data Fiduciary', 'Section 10; Rule 12', 'DPDPA', 1, @root, 90, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-I') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-I');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;
INSERT INTO audit_sections (tenant_id, section_code, name, description, framework_ref, depth, parent_id, order_no, path, created_at, updated_at)
SELECT NULL, 'DPDP-J', 'Cross-Border Transfer', 'Section 16; Rule 15', 'DPDPA', 1, @root, 100, '/PLACEHOLDER/', NOW(6), NOW(6)
  FROM DUAL WHERE @root IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_sections WHERE section_code='DPDP-J') x);
SET @s := (SELECT id FROM audit_sections WHERE section_code = 'DPDP-J');
UPDATE audit_sections SET path = CONCAT('/', @root, '/', @s, '/') WHERE id = @s;

COMMIT;

-- ── 4. Controls, then section links ───────────────────────────────────────
-- The link INSERT is a LEFT JOIN anti-join, not a correlated NOT EXISTS.
-- That is the v3 fix; see the header.
START TRANSACTION;

INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S5.1', 'PRI-01.1', 'PRI-01.1', 'A notice is given to the Data Principal before or at the time of seeking consent, itemising the personal data collected and the specific purpose of processing.', 'DPDPA', 'Itemised notice before or at collection', 'DOCUMENT_REVIEW', 'Current notice text; screenshot of the collection point showing the notice; version history.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S5.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S5.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S5.2', 'PRI-01.1', 'PRI-01.1', 'The notice describes how the Data Principal may exercise their rights, how to complain to the Board, and gives the means of contacting the Data Fiduciary.', 'DPDPA', 'Notice states rights and complaint routes', 'DOCUMENT_REVIEW', 'Notice text showing rights, Board complaint route and contact details.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S5.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S5.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R3.1', 'PRI-01.1', 'PRI-01.1', 'The notice is presented independently of any other information, in clear and plain language, and is understandable on its own without reference to other documents.', 'DPDPA', 'Notice is standalone and in plain language', 'TECHNICAL_TEST', 'Screenshot showing the notice is not bundled into terms of service.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R3.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R3.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R3.2', 'PRI-01.1', 'PRI-01.1', 'The Data Principal is given the option to access the notice in English or any language listed in the Eighth Schedule to the Constitution.', 'DPDPA', 'Notice available in Eighth Schedule languages', 'TECHNICAL_TEST', 'Screenshot of the language selector; list of languages offered.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R3.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 40, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R3.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S6.1', 'PRI-01.2', 'PRI-01.2', 'Consent is obtained by a clear affirmative action, is specific to the stated purpose, and is limited to the personal data necessary for that purpose.', 'DPDPA', 'Consent is free, specific, informed and unambiguous', 'TECHNICAL_TEST', 'Consent capture screen; confirmation that no pre-ticked boxes or bundled consent are used.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S6.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 50, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S6.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S6.2', 'PRI-01.2', 'PRI-01.2', 'The Data Principal may withdraw consent at any time with comparable ease to giving it, and the consequences of withdrawal are borne by the Data Principal.', 'DPDPA', 'Withdrawal is as easy as giving consent', 'TECHNICAL_TEST', 'Withdrawal journey walkthrough; comparison of clicks to give versus withdraw.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S6.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 60, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S6.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S6.3', 'PRI-01.2', 'PRI-01.2', 'On withdrawal of consent the Data Fiduciary ceases processing within a reasonable time and causes its Data Processors to do the same, unless another lawful basis applies.', 'DPDPA', 'Processing ceases on withdrawal', 'OBSERVATION', 'Sample withdrawal request with timestamps showing processing stopped and processors notified.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S6.3' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 70, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S6.3' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R4.1', 'PRI-01.3', 'PRI-01.3', 'A record of each consent is retained showing what was consented to, the notice presented at that time, the timestamp, and any subsequent withdrawal.', 'DPDPA', 'Consent record is retained and demonstrable', 'TECHNICAL_TEST', 'Export of consent records for a sample of Data Principals.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R4.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 80, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R4.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R4.2', 'PRI-01.3', 'PRI-01.3', 'Where consent is given, managed or withdrawn through a registered Consent Manager, the Data Fiduciary honours and records those interactions.', 'DPDPA', 'Consent Manager interactions are supported', 'DOCUMENT_REVIEW', 'Consent Manager integration design; records of interactions, if applicable.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R4.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 90, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R4.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S7.1', 'PRI-03.5', 'PRI-03.5', 'Where processing relies on a legitimate use under Section 7 rather than consent, the specific ground is documented and processing stays within its limits.', 'DPDPA', 'Legitimate uses are documented and bounded', 'DOCUMENT_REVIEW', 'Register of processing activities showing the lawful basis for each purpose.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S7.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 100, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-A') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S7.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S4.1', 'PRI-03.5', 'PRI-03.5', 'Personal data is processed only for a lawful purpose for which the Data Principal has given consent or which is a legitimate use, and never for a purpose not notified.', 'DPDPA', 'Processing only for a lawful purpose', 'DOCUMENT_REVIEW', 'Register of processing activities mapping each purpose to its lawful basis.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S4.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-B') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S4.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S8.1', 'PRI-03.5', 'PRI-03.5', 'Only the personal data necessary for the specified purpose is collected, and collection is reviewed periodically to remove fields no longer required.', 'DPDPA', 'Data minimisation is applied and reviewed', 'TECHNICAL_TEST', 'Field-level review record showing fields removed or justified.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S8.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-B') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S8.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S8.2', 'PRI-02.2', 'PRI-02.2', 'Reasonable effort is made to ensure personal data is complete, accurate and consistent where it is used to make a decision affecting the Data Principal or is disclosed onward.', 'DPDPA', 'Accuracy and completeness maintained', 'TECHNICAL_TEST', 'Data quality controls; correction workflow evidence.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S8.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-B') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S8.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S8.3', 'TPR-02.2', 'TPR-02.2', 'A Data Processor is engaged only under a valid contract that binds it to process personal data solely on the Data Fiduciary''s instructions and to apply equivalent safeguards.', 'DPDPA', 'Processor engagement is under a valid contract', 'DOCUMENT_REVIEW', 'Executed data processing agreements for all processors handling personal data.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S8.3' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 40, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-B') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S8.3' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R6.7', 'TPR-02.2', 'TPR-02.2', 'The contract with each Data Processor imposes the same reasonable security safeguards the Data Fiduciary is required to apply.', 'DPDPA', 'Processor safeguards are contractually imposed', 'DOCUMENT_REVIEW', 'DPA clauses covering security safeguards, subprocessing and breach notification.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R6.7' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 50, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-B') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R6.7' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.RoPA.1', 'PRI-03.3', 'PRI-03.3', 'A current record is maintained of the categories of personal data processed, the purposes, recipients, retention periods and transfers.', 'DPDPA', 'Record of processing activities maintained', 'TECHNICAL_TEST', 'Record of processing activities with an update date within the review period.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.RoPA.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 60, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-B') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.RoPA.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R6.1', 'CRY-01.1', 'CRY-01.1', 'Personal data is protected by encryption, obfuscation, masking or the use of virtual tokens, applied to data at rest and in transit.', 'DPDPA', 'Encryption, obfuscation or masking applied', 'TECHNICAL_TEST', 'Encryption configuration for each store holding personal data; TLS policy.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R6.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-C') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R6.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R6.2', 'IAM-03.1', 'IAM-03.1', 'Access to computer resources holding personal data is controlled so that only authorised persons may reach it, on the principle of least privilege.', 'DPDPA', 'Access control to computer resources', 'TECHNICAL_TEST', 'Access control matrix; entitlement listing for systems holding personal data.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R6.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-C') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R6.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R6.3', 'LOG-01.2', 'LOG-01.2', 'Logs of access to and activity on personal data are maintained and retained for at least one year to enable detection and investigation of unauthorised access.', 'DPDPA', 'Logs retained for at least one year', 'TECHNICAL_TEST', 'Log retention configuration showing a minimum one-year period.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R6.3' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-C') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R6.3' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R6.4', 'LOG-02.1', 'LOG-02.1', 'Logs are monitored and reviewed so that unauthorised access is detected, with alerts triaged and their disposition recorded.', 'DPDPA', 'Monitoring and review of logs', 'TECHNICAL_TEST', 'Detection rules; sample of triaged alerts with outcomes.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R6.4' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 40, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-C') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R6.4' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R6.5', 'BCP-01.1', 'BCP-01.1', 'Reasonable backup and recovery measures are in place so that processing can continue if personal data is lost, corrupted or made unavailable.', 'DPDPA', 'Backups enable continued processing after compromise', 'TECHNICAL_TEST', 'Backup schedule and success monitoring; most recent restoration test result.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R6.5' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 50, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-C') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R6.5' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R6.6', 'GOV-01.1', 'GOV-01.1', 'Appropriate technical and organisational measures are implemented and documented to give effect to the security safeguards required by the Rules.', 'DPDPA', 'Technical and organisational measures documented', 'DOCUMENT_REVIEW', 'Information security policy set showing coverage of the Rule 6 measures.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R6.6' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 60, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-C') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R6.6' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R7.1', 'IRP-02.2', 'IRP-02.2', 'On becoming aware of a personal data breach, affected Data Principals are informed in plain language, describing the breach, its likely consequences and the mitigation steps taken.', 'DPDPA', 'Intimation to affected Data Principals without delay', 'DOCUMENT_REVIEW', 'Breach notification template; records of any notifications issued.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R7.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-D') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R7.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R7.2', 'IRP-02.1', 'IRP-02.1', 'The Data Protection Board is informed of the breach without delay, with a description of the breach, its nature, extent, timing and location.', 'DPDPA', 'Initial intimation to the Board without delay', 'DOCUMENT_REVIEW', 'Board notification procedure with named owner and route.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R7.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-D') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R7.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R7.3', 'IRP-02.1', 'IRP-02.1', 'A detailed report is furnished to the Board within seventy-two hours of becoming aware, covering updated findings, remedial measures, and the intimations given to Data Principals.', 'DPDPA', 'Detailed report to the Board within 72 hours', 'DOCUMENT_REVIEW', '72-hour report template; timeline evidence from any exercise or live incident.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R7.3' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-D') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R7.3' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R7.4', 'IRP-01.3', 'IRP-01.3', 'The breach response procedure is tested at a defined interval using a realistic scenario, with the timing measured against the statutory deadlines.', 'DPDPA', 'Breach procedure is exercised', 'OBSERVATION', 'Tabletop exercise record showing the 72-hour deadline was met.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R7.4' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 40, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-D') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R7.4' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S8.6', 'IRP-01.1', 'IRP-01.1', 'Reasonable measures are in place to detect a personal data breach, contain it and remediate its cause.', 'DPDPA', 'Breach detection and containment capability', 'DOCUMENT_REVIEW', 'Incident response plan covering personal data breaches specifically.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S8.6' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 50, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-D') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S8.6' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R8.1', 'AST-03.1', 'AST-03.1', 'A retention schedule defines how long each category of personal data is kept and the basis for that period, including any Third Schedule class subject to the specified period.', 'DPDPA', 'Retention schedule with defined periods', 'DOCUMENT_REVIEW', 'Retention schedule showing period and basis per data category.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R8.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-E') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R8.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R8.2', 'AST-03.2', 'AST-03.2', 'Personal data is erased when the Data Principal withdraws consent or the specified purpose is no longer being served, unless retention is required by law.', 'DPDPA', 'Erasure when purpose is no longer served', 'TECHNICAL_TEST', 'Deletion job records; sample of erasures with timestamps.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R8.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-E') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R8.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R8.3', 'PRI-02.2', 'PRI-02.2', 'The Data Principal is informed at least forty-eight hours before erasure under the Third Schedule timeline, so they may log in or initiate contact to prevent it.', 'DPDPA', 'Advance notice before erasure', 'TECHNICAL_TEST', 'Pre-erasure notification template and send records.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R8.3' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-E') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R8.3' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R8.4', 'TPR-03.3', 'TPR-03.3', 'Data Processors are instructed to erase the personal data on the same basis, and confirmation of erasure is obtained.', 'DPDPA', 'Erasure propagated to processors', 'DOCUMENT_REVIEW', 'Processor erasure instructions and confirmations.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R8.4' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 40, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-E') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R8.4' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S11.1', 'PRI-02.1', 'PRI-02.1', 'On request, the Data Principal is provided a summary of the personal data being processed, the processing activities, and the identities of other Data Fiduciaries with whom it has been shared.', 'DPDPA', 'Right to access a summary of personal data', 'OBSERVATION', 'Sample access request with the response provided and the date.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S11.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-F') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S11.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S12.1', 'PRI-02.2', 'PRI-02.2', 'Requests to correct inaccurate or misleading data, complete incomplete data, or update personal data are actioned.', 'DPDPA', 'Right to correction, completion and updating', 'OBSERVATION', 'Sample correction request with before and after evidence.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S12.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-F') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S12.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S12.2', 'PRI-02.2', 'PRI-02.2', 'A request for erasure is actioned unless retention is necessary for the specified purpose or for compliance with law.', 'DPDPA', 'Right to erasure on request', 'OBSERVATION', 'Sample erasure request with the outcome and justification if refused.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S12.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-F') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S12.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S13.1', 'PRI-02.3', 'PRI-02.3', 'A readily available means of grievance redressal is published, and the Data Principal must exhaust it before approaching the Board.', 'DPDPA', 'Grievance redressal mechanism is published', 'TECHNICAL_TEST', 'Published grievance route; log of grievances with response times.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S13.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 40, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-F') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S13.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R13.1', 'PRI-02.3', 'PRI-02.3', 'The period within which the Data Fiduciary responds to a rights request is published and adhered to, and requests are tracked to closure.', 'DPDPA', 'Rights requests fulfilled within the published period', 'TECHNICAL_TEST', 'Published SLA; request register showing time to close.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R13.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 50, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-F') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R13.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R13.2', 'PRI-02.1', 'PRI-02.1', 'The Data Principal is identified using the means published by the Data Fiduciary before a rights request is actioned, so data is not disclosed to the wrong person.', 'DPDPA', 'Identification of the requester', 'TECHNICAL_TEST', 'Identity verification procedure for rights requests.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R13.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 60, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-F') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R13.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S14.1', 'PRI-02.4', 'PRI-02.4', 'The Data Principal may nominate another individual to exercise their rights in the event of death or incapacity, and the nomination is recorded and honoured.', 'DPDPA', 'Right to nominate', 'TECHNICAL_TEST', 'Nomination capture screen; sample nomination record.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S14.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 70, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-F') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S14.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S9.1', 'PRI-01.4', 'PRI-01.4', 'Before processing the personal data of a child, verifiable consent is obtained from the parent, and the reliability of the verification is documented.', 'DPDPA', 'Verifiable parental consent for children', 'TECHNICAL_TEST', 'Age-gating and parental verification design; sample verification records.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S9.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-G') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S9.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R10.1', 'PRI-01.4', 'PRI-01.4', 'Due diligence is exercised to confirm the individual identifying as the parent is an identifiable adult, using reliable identity details or a virtual token.', 'DPDPA', 'Due diligence on parent identity and adulthood', 'DOCUMENT_REVIEW', 'Verification method description and its reliability basis.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R10.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-G') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R10.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S9.2', 'PRI-01.4', 'PRI-01.4', 'Processing likely to have a detrimental effect on a child''s wellbeing is not undertaken, and children are not tracked, behaviourally monitored or targeted with advertising.', 'DPDPA', 'No detrimental processing or tracking of children', 'TECHNICAL_TEST', 'Confirmation that tracking and ad targeting are disabled for child accounts.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S9.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-G') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S9.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S9.3', 'PRI-01.4', 'PRI-01.4', 'Verifiable consent is obtained from the lawful guardian of a person with disability who has a lawful guardian, before processing their personal data.', 'DPDPA', 'Guardian consent for persons with disability', 'TECHNICAL_TEST', 'Guardian verification procedure and records.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S9.3' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 40, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-G') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S9.3' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S8.5', 'TPR-03.1', 'TPR-03.1', 'The Data Fiduciary remains responsible for compliance in respect of any processing undertaken on its behalf by a Data Processor or agent.', 'DPDPA', 'Accountability for processor and agent processing', 'DOCUMENT_REVIEW', 'Processor oversight procedure; assurance evidence obtained from processors.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S8.5' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-H') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S8.5' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S8.9', 'PRI-03.1', 'PRI-03.1', 'The business contact information of a Data Protection Officer, or of a person able to answer questions about processing, is published and kept current.', 'DPDPA', 'Contact of the person answering data questions published', 'TECHNICAL_TEST', 'Published contact details on the notice and website.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S8.9' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-H') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S8.9' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R9.1', 'PRI-03.1', 'PRI-03.1', 'The contact details are displayed prominently on the website or app and included in every response to a rights request.', 'DPDPA', 'Contact details displayed prominently', 'TECHNICAL_TEST', 'Screenshot of the published contact; sample response showing inclusion.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R9.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-H') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R9.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S8.11', 'GOV-01.1', 'GOV-01.1', 'Documented policies and procedures give effect to the obligations under the Act, are approved by management, and are reviewed on change.', 'DPDPA', 'Policy set gives effect to the Act', 'DOCUMENT_REVIEW', 'Privacy policy set with approval and review dates.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S8.11' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 40, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-H') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S8.11' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.GOV.1', 'GOV-02.2', 'GOV-02.2', 'Risks to Data Principals from personal data processing are assessed, recorded in the risk register with an owner, and treated.', 'DPDPA', 'Personal data processing is risk assessed', 'TECHNICAL_TEST', 'Risk register entries covering personal data processing.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.GOV.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 50, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-H') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.GOV.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.GOV.2', 'HRS-02.1', 'HRS-02.1', 'Personnel handling personal data receive training on their obligations under the Act, on joining and at a defined recurring interval.', 'DPDPA', 'Staff handling personal data are trained', 'TECHNICAL_TEST', 'Training completion records for personnel in scope.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.GOV.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 60, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-H') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.GOV.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S10.1', 'PRI-03.1', 'PRI-03.1', 'Where designated a Significant Data Fiduciary, a Data Protection Officer based in India is appointed, is answerable to the governing body, and is the point of contact for grievance redressal.', 'DPDPA', 'Data Protection Officer appointed and based in India', 'DOCUMENT_REVIEW', 'Appointment letter; reporting line to the governing body.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S10.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-I') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S10.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S10.2', 'GOV-03.2', 'GOV-03.2', 'An independent data auditor is appointed to evaluate compliance with the Act.', 'DPDPA', 'Independent data auditor appointed', 'DOCUMENT_REVIEW', 'Auditor engagement letter and independence confirmation.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S10.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-I') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S10.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R12.1', 'PRI-03.2', 'PRI-03.2', 'A Data Protection Impact Assessment is undertaken annually, documenting the rights of Data Principals, the purposes of processing and the risks assessed.', 'DPDPA', 'Annual Data Protection Impact Assessment', 'DOCUMENT_REVIEW', 'Most recent DPIA with date and sign-off.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R12.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-I') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R12.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R12.2', 'GOV-03.3', 'GOV-03.3', 'An audit is undertaken annually and the observations of the DPIA and audit are reported to the Board by the Data Protection Officer.', 'DPDPA', 'Annual audit and report to the Board', 'DOCUMENT_REVIEW', 'Audit report and evidence of submission to the Board.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R12.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 40, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-I') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R12.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R12.3', 'PRI-03.2', 'PRI-03.2', 'Due diligence is exercised to verify that algorithmic software used for processing does not pose a risk to the rights of Data Principals.', 'DPDPA', 'Algorithmic due diligence', 'DOCUMENT_REVIEW', 'Algorithmic review record for systems processing personal data.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R12.3' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 50, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-I') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R12.3' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R12.4', 'PRI-03.4', 'PRI-03.4', 'Personal data specified by the Central Government, and traffic data pertaining to its flow, are not transferred outside India.', 'DPDPA', 'Specified personal data localisation', 'TECHNICAL_TEST', 'Data flow map showing residency of specified categories.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R12.4' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 60, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-I') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R12.4' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.S16.1', 'PRI-03.4', 'PRI-03.4', 'Personal data is transferred outside India only to countries or territories not restricted by the Central Government, and the restriction list is monitored for change.', 'DPDPA', 'Transfers restricted to permitted territories', 'TECHNICAL_TEST', 'Transfer register showing destination and permitted status.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.S16.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 10, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-J') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.S16.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R15.1', 'PRI-03.4', 'PRI-03.4', 'Transfers outside India meet any requirements the Central Government has specified regarding making personal data available to a foreign State or its agencies.', 'DPDPA', 'Transfer conditions met and evidenced', 'DOCUMENT_REVIEW', 'Transfer assessment covering the specified conditions.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R15.1' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 20, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-J') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R15.1' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;
INSERT INTO audit_controls (tenant_id, control_code, control_tag, common_control_code, description,
                            framework_ref, name, test_type, evidence_guidance, created_at, updated_at)
SELECT NULL, 'DPDP.R15.2', 'TPR-03.2', 'TPR-03.2', 'All cross-border flows of personal data, including flows via processors and subprocessors, are mapped and reviewed on change.', 'DPDPA', 'Cross-border flows mapped and reviewed', 'TECHNICAL_TEST', 'Data flow map including subprocessor chains with a review date.', NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_controls
    WHERE control_code='DPDP.R15.2' AND framework_ref='DPDPA') x);

INSERT INTO audit_section_control_mappings (section_id, control_id, is_mandatory, order_no, weight, created_at, updated_at)
SELECT sec.id, ctl.id, 1, 30, 1, NOW(6), NOW(6)
  FROM      (SELECT id FROM audit_sections WHERE section_code='DPDP-J') sec
  CROSS JOIN (SELECT id FROM audit_controls WHERE control_code='DPDP.R15.2' AND framework_ref='DPDPA') ctl
  LEFT JOIN (SELECT section_id, control_id FROM audit_section_control_mappings) ex
         ON ex.section_id = sec.id AND ex.control_id = ctl.id
 WHERE ex.section_id IS NULL;

COMMIT;

-- ── 5. Tests ───────────────────────────────────────────────────────────────
-- automation_key stays NULL. Which kashiguard.* check evidences which DPDP
-- obligation is your judgement, not mine.
START TRANSACTION;

INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-001', 'Itemised notice before or at collection', 'A notice is given to the Data Principal before or at the time of seeking consent, itemising the personal data collected and the specific purpose of processing.', 'Current notice text; screenshot of the collection point showing the notice; version history.', 'PRI-01.1', 'PRI-01.1', 'DPDPA', 'DPDP.S5.1',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-001') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-002', 'Notice states rights and complaint routes', 'The notice describes how the Data Principal may exercise their rights, how to complain to the Board, and gives the means of contacting the Data Fiduciary.', 'Notice text showing rights, Board complaint route and contact details.', 'PRI-01.1', 'PRI-01.1', 'DPDPA', 'DPDP.S5.2',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-002') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-003', 'Notice is standalone and in plain language', 'The notice is presented independently of any other information, in clear and plain language, and is understandable on its own without reference to other documents.', 'Screenshot showing the notice is not bundled into terms of service.', 'PRI-01.1', 'PRI-01.1', 'DPDPA', 'DPDP.R3.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-003') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-004', 'Notice available in Eighth Schedule languages', 'The Data Principal is given the option to access the notice in English or any language listed in the Eighth Schedule to the Constitution.', 'Screenshot of the language selector; list of languages offered.', 'PRI-01.1', 'PRI-01.1', 'DPDPA', 'DPDP.R3.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-004') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-005', 'Consent is free, specific, informed and unambiguous', 'Consent is obtained by a clear affirmative action, is specific to the stated purpose, and is limited to the personal data necessary for that purpose.', 'Consent capture screen; confirmation that no pre-ticked boxes or bundled consent are used.', 'PRI-01.2', 'PRI-01.2', 'DPDPA', 'DPDP.S6.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-005') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-006', 'Withdrawal is as easy as giving consent', 'The Data Principal may withdraw consent at any time with comparable ease to giving it, and the consequences of withdrawal are borne by the Data Principal.', 'Withdrawal journey walkthrough; comparison of clicks to give versus withdraw.', 'PRI-01.2', 'PRI-01.2', 'DPDPA', 'DPDP.S6.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-006') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-007', 'Processing ceases on withdrawal', 'On withdrawal of consent the Data Fiduciary ceases processing within a reasonable time and causes its Data Processors to do the same, unless another lawful basis applies.', 'Sample withdrawal request with timestamps showing processing stopped and processors notified.', 'PRI-01.2', 'PRI-01.2', 'DPDPA', 'DPDP.S6.3',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-007') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-008', 'Consent record is retained and demonstrable', 'A record of each consent is retained showing what was consented to, the notice presented at that time, the timestamp, and any subsequent withdrawal.', 'Export of consent records for a sample of Data Principals.', 'PRI-01.3', 'PRI-01.3', 'DPDPA', 'DPDP.R4.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-008') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-009', 'Consent Manager interactions are supported', 'Where consent is given, managed or withdrawn through a registered Consent Manager, the Data Fiduciary honours and records those interactions.', 'Consent Manager integration design; records of interactions, if applicable.', 'PRI-01.3', 'PRI-01.3', 'DPDPA', 'DPDP.R4.2',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-009') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-010', 'Legitimate uses are documented and bounded', 'Where processing relies on a legitimate use under Section 7 rather than consent, the specific ground is documented and processing stays within its limits.', 'Register of processing activities showing the lawful basis for each purpose.', 'PRI-03.5', 'PRI-03.5', 'DPDPA', 'DPDP.S7.1',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-010') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-011', 'Processing only for a lawful purpose', 'Personal data is processed only for a lawful purpose for which the Data Principal has given consent or which is a legitimate use, and never for a purpose not notified.', 'Register of processing activities mapping each purpose to its lawful basis.', 'PRI-03.5', 'PRI-03.5', 'DPDPA', 'DPDP.S4.1',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-011') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-012', 'Data minimisation is applied and reviewed', 'Only the personal data necessary for the specified purpose is collected, and collection is reviewed periodically to remove fields no longer required.', 'Field-level review record showing fields removed or justified.', 'PRI-03.5', 'PRI-03.5', 'DPDPA', 'DPDP.S8.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-012') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-013', 'Accuracy and completeness maintained', 'Reasonable effort is made to ensure personal data is complete, accurate and consistent where it is used to make a decision affecting the Data Principal or is disclosed onward.', 'Data quality controls; correction workflow evidence.', 'PRI-02.2', 'PRI-02.2', 'DPDPA', 'DPDP.S8.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-013') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-014', 'Processor engagement is under a valid contract', 'A Data Processor is engaged only under a valid contract that binds it to process personal data solely on the Data Fiduciary''s instructions and to apply equivalent safeguards.', 'Executed data processing agreements for all processors handling personal data.', 'TPR-02.2', 'TPR-02.2', 'DPDPA', 'DPDP.S8.3',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-014') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-015', 'Processor safeguards are contractually imposed', 'The contract with each Data Processor imposes the same reasonable security safeguards the Data Fiduciary is required to apply.', 'DPA clauses covering security safeguards, subprocessing and breach notification.', 'TPR-02.2', 'TPR-02.2', 'DPDPA', 'DPDP.R6.7',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-015') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-016', 'Record of processing activities maintained', 'A current record is maintained of the categories of personal data processed, the purposes, recipients, retention periods and transfers.', 'Record of processing activities with an update date within the review period.', 'PRI-03.3', 'PRI-03.3', 'DPDPA', 'DPDP.RoPA.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-016') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-017', 'Encryption, obfuscation or masking applied', 'Personal data is protected by encryption, obfuscation, masking or the use of virtual tokens, applied to data at rest and in transit.', 'Encryption configuration for each store holding personal data; TLS policy.', 'CRY-01.1', 'CRY-01.1', 'DPDPA', 'DPDP.R6.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-017') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-018', 'Access control to computer resources', 'Access to computer resources holding personal data is controlled so that only authorised persons may reach it, on the principle of least privilege.', 'Access control matrix; entitlement listing for systems holding personal data.', 'IAM-03.1', 'IAM-03.1', 'DPDPA', 'DPDP.R6.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-018') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-019', 'Logs retained for at least one year', 'Logs of access to and activity on personal data are maintained and retained for at least one year to enable detection and investigation of unauthorised access.', 'Log retention configuration showing a minimum one-year period.', 'LOG-01.2', 'LOG-01.2', 'DPDPA', 'DPDP.R6.3',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-019') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-020', 'Monitoring and review of logs', 'Logs are monitored and reviewed so that unauthorised access is detected, with alerts triaged and their disposition recorded.', 'Detection rules; sample of triaged alerts with outcomes.', 'LOG-02.1', 'LOG-02.1', 'DPDPA', 'DPDP.R6.4',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-020') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-021', 'Backups enable continued processing after compromise', 'Reasonable backup and recovery measures are in place so that processing can continue if personal data is lost, corrupted or made unavailable.', 'Backup schedule and success monitoring; most recent restoration test result.', 'BCP-01.1', 'BCP-01.1', 'DPDPA', 'DPDP.R6.5',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-021') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-022', 'Technical and organisational measures documented', 'Appropriate technical and organisational measures are implemented and documented to give effect to the security safeguards required by the Rules.', 'Information security policy set showing coverage of the Rule 6 measures.', 'GOV-01.1', 'GOV-01.1', 'DPDPA', 'DPDP.R6.6',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-022') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-023', 'Intimation to affected Data Principals without delay', 'On becoming aware of a personal data breach, affected Data Principals are informed in plain language, describing the breach, its likely consequences and the mitigation steps taken.', 'Breach notification template; records of any notifications issued.', 'IRP-02.2', 'IRP-02.2', 'DPDPA', 'DPDP.R7.1',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-023') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-024', 'Initial intimation to the Board without delay', 'The Data Protection Board is informed of the breach without delay, with a description of the breach, its nature, extent, timing and location.', 'Board notification procedure with named owner and route.', 'IRP-02.1', 'IRP-02.1', 'DPDPA', 'DPDP.R7.2',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-024') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-025', 'Detailed report to the Board within 72 hours', 'A detailed report is furnished to the Board within seventy-two hours of becoming aware, covering updated findings, remedial measures, and the intimations given to Data Principals.', '72-hour report template; timeline evidence from any exercise or live incident.', 'IRP-02.1', 'IRP-02.1', 'DPDPA', 'DPDP.R7.3',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-025') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-026', 'Breach procedure is exercised', 'The breach response procedure is tested at a defined interval using a realistic scenario, with the timing measured against the statutory deadlines.', 'Tabletop exercise record showing the 72-hour deadline was met.', 'IRP-01.3', 'IRP-01.3', 'DPDPA', 'DPDP.R7.4',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-026') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-027', 'Breach detection and containment capability', 'Reasonable measures are in place to detect a personal data breach, contain it and remediate its cause.', 'Incident response plan covering personal data breaches specifically.', 'IRP-01.1', 'IRP-01.1', 'DPDPA', 'DPDP.S8.6',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-027') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-028', 'Retention schedule with defined periods', 'A retention schedule defines how long each category of personal data is kept and the basis for that period, including any Third Schedule class subject to the specified period.', 'Retention schedule showing period and basis per data category.', 'AST-03.1', 'AST-03.1', 'DPDPA', 'DPDP.R8.1',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-028') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-029', 'Erasure when purpose is no longer served', 'Personal data is erased when the Data Principal withdraws consent or the specified purpose is no longer being served, unless retention is required by law.', 'Deletion job records; sample of erasures with timestamps.', 'AST-03.2', 'AST-03.2', 'DPDPA', 'DPDP.R8.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-029') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-030', 'Advance notice before erasure', 'The Data Principal is informed at least forty-eight hours before erasure under the Third Schedule timeline, so they may log in or initiate contact to prevent it.', 'Pre-erasure notification template and send records.', 'PRI-02.2', 'PRI-02.2', 'DPDPA', 'DPDP.R8.3',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-030') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-031', 'Erasure propagated to processors', 'Data Processors are instructed to erase the personal data on the same basis, and confirmation of erasure is obtained.', 'Processor erasure instructions and confirmations.', 'TPR-03.3', 'TPR-03.3', 'DPDPA', 'DPDP.R8.4',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-031') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-032', 'Right to access a summary of personal data', 'On request, the Data Principal is provided a summary of the personal data being processed, the processing activities, and the identities of other Data Fiduciaries with whom it has been shared.', 'Sample access request with the response provided and the date.', 'PRI-02.1', 'PRI-02.1', 'DPDPA', 'DPDP.S11.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-032') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-033', 'Right to correction, completion and updating', 'Requests to correct inaccurate or misleading data, complete incomplete data, or update personal data are actioned.', 'Sample correction request with before and after evidence.', 'PRI-02.2', 'PRI-02.2', 'DPDPA', 'DPDP.S12.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-033') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-034', 'Right to erasure on request', 'A request for erasure is actioned unless retention is necessary for the specified purpose or for compliance with law.', 'Sample erasure request with the outcome and justification if refused.', 'PRI-02.2', 'PRI-02.2', 'DPDPA', 'DPDP.S12.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-034') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-035', 'Grievance redressal mechanism is published', 'A readily available means of grievance redressal is published, and the Data Principal must exhaust it before approaching the Board.', 'Published grievance route; log of grievances with response times.', 'PRI-02.3', 'PRI-02.3', 'DPDPA', 'DPDP.S13.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-035') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-036', 'Rights requests fulfilled within the published period', 'The period within which the Data Fiduciary responds to a rights request is published and adhered to, and requests are tracked to closure.', 'Published SLA; request register showing time to close.', 'PRI-02.3', 'PRI-02.3', 'DPDPA', 'DPDP.R13.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-036') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-037', 'Identification of the requester', 'The Data Principal is identified using the means published by the Data Fiduciary before a rights request is actioned, so data is not disclosed to the wrong person.', 'Identity verification procedure for rights requests.', 'PRI-02.1', 'PRI-02.1', 'DPDPA', 'DPDP.R13.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-037') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-038', 'Right to nominate', 'The Data Principal may nominate another individual to exercise their rights in the event of death or incapacity, and the nomination is recorded and honoured.', 'Nomination capture screen; sample nomination record.', 'PRI-02.4', 'PRI-02.4', 'DPDPA', 'DPDP.S14.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-038') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-039', 'Verifiable parental consent for children', 'Before processing the personal data of a child, verifiable consent is obtained from the parent, and the reliability of the verification is documented.', 'Age-gating and parental verification design; sample verification records.', 'PRI-01.4', 'PRI-01.4', 'DPDPA', 'DPDP.S9.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-039') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-040', 'Due diligence on parent identity and adulthood', 'Due diligence is exercised to confirm the individual identifying as the parent is an identifiable adult, using reliable identity details or a virtual token.', 'Verification method description and its reliability basis.', 'PRI-01.4', 'PRI-01.4', 'DPDPA', 'DPDP.R10.1',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-040') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-041', 'No detrimental processing or tracking of children', 'Processing likely to have a detrimental effect on a child''s wellbeing is not undertaken, and children are not tracked, behaviourally monitored or targeted with advertising.', 'Confirmation that tracking and ad targeting are disabled for child accounts.', 'PRI-01.4', 'PRI-01.4', 'DPDPA', 'DPDP.S9.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-041') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-042', 'Guardian consent for persons with disability', 'Verifiable consent is obtained from the lawful guardian of a person with disability who has a lawful guardian, before processing their personal data.', 'Guardian verification procedure and records.', 'PRI-01.4', 'PRI-01.4', 'DPDPA', 'DPDP.S9.3',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-042') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-043', 'Accountability for processor and agent processing', 'The Data Fiduciary remains responsible for compliance in respect of any processing undertaken on its behalf by a Data Processor or agent.', 'Processor oversight procedure; assurance evidence obtained from processors.', 'TPR-03.1', 'TPR-03.1', 'DPDPA', 'DPDP.S8.5',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-043') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-044', 'Contact of the person answering data questions published', 'The business contact information of a Data Protection Officer, or of a person able to answer questions about processing, is published and kept current.', 'Published contact details on the notice and website.', 'PRI-03.1', 'PRI-03.1', 'DPDPA', 'DPDP.S8.9',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-044') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-045', 'Contact details displayed prominently', 'The contact details are displayed prominently on the website or app and included in every response to a rights request.', 'Screenshot of the published contact; sample response showing inclusion.', 'PRI-03.1', 'PRI-03.1', 'DPDPA', 'DPDP.R9.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-045') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-046', 'Policy set gives effect to the Act', 'Documented policies and procedures give effect to the obligations under the Act, are approved by management, and are reviewed on change.', 'Privacy policy set with approval and review dates.', 'GOV-01.1', 'GOV-01.1', 'DPDPA', 'DPDP.S8.11',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-046') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-047', 'Personal data processing is risk assessed', 'Risks to Data Principals from personal data processing are assessed, recorded in the risk register with an owner, and treated.', 'Risk register entries covering personal data processing.', 'GOV-02.2', 'GOV-02.2', 'DPDPA', 'DPDP.GOV.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-047') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-048', 'Staff handling personal data are trained', 'Personnel handling personal data receive training on their obligations under the Act, on joining and at a defined recurring interval.', 'Training completion records for personnel in scope.', 'HRS-02.1', 'HRS-02.1', 'DPDPA', 'DPDP.GOV.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-048') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-049', 'Data Protection Officer appointed and based in India', 'Where designated a Significant Data Fiduciary, a Data Protection Officer based in India is appointed, is answerable to the governing body, and is the point of contact for grievance redressal.', 'Appointment letter; reporting line to the governing body.', 'PRI-03.1', 'PRI-03.1', 'DPDPA', 'DPDP.S10.1',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-049') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-050', 'Independent data auditor appointed', 'An independent data auditor is appointed to evaluate compliance with the Act.', 'Auditor engagement letter and independence confirmation.', 'GOV-03.2', 'GOV-03.2', 'DPDPA', 'DPDP.S10.2',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-050') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-051', 'Annual Data Protection Impact Assessment', 'A Data Protection Impact Assessment is undertaken annually, documenting the rights of Data Principals, the purposes of processing and the risks assessed.', 'Most recent DPIA with date and sign-off.', 'PRI-03.2', 'PRI-03.2', 'DPDPA', 'DPDP.R12.1',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-051') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-052', 'Annual audit and report to the Board', 'An audit is undertaken annually and the observations of the DPIA and audit are reported to the Board by the Data Protection Officer.', 'Audit report and evidence of submission to the Board.', 'GOV-03.3', 'GOV-03.3', 'DPDPA', 'DPDP.R12.2',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-052') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-053', 'Algorithmic due diligence', 'Due diligence is exercised to verify that algorithmic software used for processing does not pose a risk to the rights of Data Principals.', 'Algorithmic review record for systems processing personal data.', 'PRI-03.2', 'PRI-03.2', 'DPDPA', 'DPDP.R12.3',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-053') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-054', 'Specified personal data localisation', 'Personal data specified by the Central Government, and traffic data pertaining to its flow, are not transferred outside India.', 'Data flow map showing residency of specified categories.', 'PRI-03.4', 'PRI-03.4', 'DPDPA', 'DPDP.R12.4',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-054') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-055', 'Transfers restricted to permitted territories', 'Personal data is transferred outside India only to countries or territories not restricted by the Central Government, and the restriction list is monitored for change.', 'Transfer register showing destination and permitted status.', 'PRI-03.4', 'PRI-03.4', 'DPDPA', 'DPDP.S16.1',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-055') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-056', 'Transfer conditions met and evidenced', 'Transfers outside India meet any requirements the Central Government has specified regarding making personal data available to a foreign State or its agencies.', 'Transfer assessment covering the specified conditions.', 'PRI-03.4', 'PRI-03.4', 'DPDPA', 'DPDP.R15.1',
       'ANNUAL', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-056') x);
INSERT INTO audit_tests (tenant_id, test_ref, name, description, evidence_guidance, control_tag,
                         common_control_code, framework_ref, framework_test_id, frequency,
                         automation_type, automation_key, test_procedure, created_at, updated_at)
SELECT NULL, 'DPDPA-T-057', 'Cross-border flows mapped and reviewed', 'All cross-border flows of personal data, including flows via processors and subprocessors, are mapped and reviewed on change.', 'Data flow map including subprocessor chains with a review date.', 'TPR-03.2', 'TPR-03.2', 'DPDPA', 'DPDP.R15.2',
       'QUARTERLY', 'MANUAL', NULL,
       'Obtain the evidence listed and confirm the requirement was met throughout the period under review.',
       NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM audit_tests WHERE test_ref='DPDPA-T-057') x);

COMMIT;

-- ── 6. UCF crosswalk ───────────────────────────────────────────────────────
START TRANSACTION;

INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.1', 'DPDPA', 'DPDP.S5.1', 'Itemised notice before or at collection', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.1' AND framework_ref='DPDPA' AND citation='DPDP.S5.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.1', 'DPDPA', 'DPDP.S5.2', 'Notice states rights and complaint routes', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.1' AND framework_ref='DPDPA' AND citation='DPDP.S5.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.1', 'DPDPA', 'DPDP.R3.1', 'Notice is standalone and in plain language', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.1' AND framework_ref='DPDPA' AND citation='DPDP.R3.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.1', 'DPDPA', 'DPDP.R3.2', 'Notice available in Eighth Schedule languages', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.1' AND framework_ref='DPDPA' AND citation='DPDP.R3.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.2', 'DPDPA', 'DPDP.S6.1', 'Consent is free, specific, informed and unambiguous', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.2' AND framework_ref='DPDPA' AND citation='DPDP.S6.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.2', 'DPDPA', 'DPDP.S6.2', 'Withdrawal is as easy as giving consent', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.2' AND framework_ref='DPDPA' AND citation='DPDP.S6.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.2', 'DPDPA', 'DPDP.S6.3', 'Processing ceases on withdrawal', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.2' AND framework_ref='DPDPA' AND citation='DPDP.S6.3') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.3', 'DPDPA', 'DPDP.R4.1', 'Consent record is retained and demonstrable', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.3' AND framework_ref='DPDPA' AND citation='DPDP.R4.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.3', 'DPDPA', 'DPDP.R4.2', 'Consent Manager interactions are supported', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.3' AND framework_ref='DPDPA' AND citation='DPDP.R4.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.5', 'DPDPA', 'DPDP.S7.1', 'Legitimate uses are documented and bounded', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.5' AND framework_ref='DPDPA' AND citation='DPDP.S7.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.5', 'DPDPA', 'DPDP.S4.1', 'Processing only for a lawful purpose', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.5' AND framework_ref='DPDPA' AND citation='DPDP.S4.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.5', 'DPDPA', 'DPDP.S8.1', 'Data minimisation is applied and reviewed', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.5' AND framework_ref='DPDPA' AND citation='DPDP.S8.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-02.2', 'DPDPA', 'DPDP.S8.2', 'Accuracy and completeness maintained', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-02.2' AND framework_ref='DPDPA' AND citation='DPDP.S8.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'TPR-02.2', 'DPDPA', 'DPDP.S8.3', 'Processor engagement is under a valid contract', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='TPR-02.2' AND framework_ref='DPDPA' AND citation='DPDP.S8.3') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'TPR-02.2', 'DPDPA', 'DPDP.R6.7', 'Processor safeguards are contractually imposed', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='TPR-02.2' AND framework_ref='DPDPA' AND citation='DPDP.R6.7') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.3', 'DPDPA', 'DPDP.RoPA.1', 'Record of processing activities maintained', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.3' AND framework_ref='DPDPA' AND citation='DPDP.RoPA.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'CRY-01.1', 'DPDPA', 'DPDP.R6.1', 'Encryption, obfuscation or masking applied', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='CRY-01.1' AND framework_ref='DPDPA' AND citation='DPDP.R6.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'IAM-03.1', 'DPDPA', 'DPDP.R6.2', 'Access control to computer resources', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='IAM-03.1' AND framework_ref='DPDPA' AND citation='DPDP.R6.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'LOG-01.2', 'DPDPA', 'DPDP.R6.3', 'Logs retained for at least one year', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='LOG-01.2' AND framework_ref='DPDPA' AND citation='DPDP.R6.3') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'LOG-02.1', 'DPDPA', 'DPDP.R6.4', 'Monitoring and review of logs', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='LOG-02.1' AND framework_ref='DPDPA' AND citation='DPDP.R6.4') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'BCP-01.1', 'DPDPA', 'DPDP.R6.5', 'Backups enable continued processing after compromise', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='BCP-01.1' AND framework_ref='DPDPA' AND citation='DPDP.R6.5') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'GOV-01.1', 'DPDPA', 'DPDP.R6.6', 'Technical and organisational measures documented', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='GOV-01.1' AND framework_ref='DPDPA' AND citation='DPDP.R6.6') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'IRP-02.2', 'DPDPA', 'DPDP.R7.1', 'Intimation to affected Data Principals without delay', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='IRP-02.2' AND framework_ref='DPDPA' AND citation='DPDP.R7.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'IRP-02.1', 'DPDPA', 'DPDP.R7.2', 'Initial intimation to the Board without delay', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='IRP-02.1' AND framework_ref='DPDPA' AND citation='DPDP.R7.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'IRP-02.1', 'DPDPA', 'DPDP.R7.3', 'Detailed report to the Board within 72 hours', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='IRP-02.1' AND framework_ref='DPDPA' AND citation='DPDP.R7.3') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'IRP-01.3', 'DPDPA', 'DPDP.R7.4', 'Breach procedure is exercised', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='IRP-01.3' AND framework_ref='DPDPA' AND citation='DPDP.R7.4') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'IRP-01.1', 'DPDPA', 'DPDP.S8.6', 'Breach detection and containment capability', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='IRP-01.1' AND framework_ref='DPDPA' AND citation='DPDP.S8.6') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'AST-03.1', 'DPDPA', 'DPDP.R8.1', 'Retention schedule with defined periods', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='AST-03.1' AND framework_ref='DPDPA' AND citation='DPDP.R8.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'AST-03.2', 'DPDPA', 'DPDP.R8.2', 'Erasure when purpose is no longer served', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='AST-03.2' AND framework_ref='DPDPA' AND citation='DPDP.R8.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-02.2', 'DPDPA', 'DPDP.R8.3', 'Advance notice before erasure', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-02.2' AND framework_ref='DPDPA' AND citation='DPDP.R8.3') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'TPR-03.3', 'DPDPA', 'DPDP.R8.4', 'Erasure propagated to processors', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='TPR-03.3' AND framework_ref='DPDPA' AND citation='DPDP.R8.4') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-02.1', 'DPDPA', 'DPDP.S11.1', 'Right to access a summary of personal data', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-02.1' AND framework_ref='DPDPA' AND citation='DPDP.S11.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-02.2', 'DPDPA', 'DPDP.S12.1', 'Right to correction, completion and updating', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-02.2' AND framework_ref='DPDPA' AND citation='DPDP.S12.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-02.2', 'DPDPA', 'DPDP.S12.2', 'Right to erasure on request', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-02.2' AND framework_ref='DPDPA' AND citation='DPDP.S12.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-02.3', 'DPDPA', 'DPDP.S13.1', 'Grievance redressal mechanism is published', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-02.3' AND framework_ref='DPDPA' AND citation='DPDP.S13.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-02.3', 'DPDPA', 'DPDP.R13.1', 'Rights requests fulfilled within the published period', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-02.3' AND framework_ref='DPDPA' AND citation='DPDP.R13.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-02.1', 'DPDPA', 'DPDP.R13.2', 'Identification of the requester', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-02.1' AND framework_ref='DPDPA' AND citation='DPDP.R13.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-02.4', 'DPDPA', 'DPDP.S14.1', 'Right to nominate', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-02.4' AND framework_ref='DPDPA' AND citation='DPDP.S14.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.4', 'DPDPA', 'DPDP.S9.1', 'Verifiable parental consent for children', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.4' AND framework_ref='DPDPA' AND citation='DPDP.S9.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.4', 'DPDPA', 'DPDP.R10.1', 'Due diligence on parent identity and adulthood', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.4' AND framework_ref='DPDPA' AND citation='DPDP.R10.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.4', 'DPDPA', 'DPDP.S9.2', 'No detrimental processing or tracking of children', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.4' AND framework_ref='DPDPA' AND citation='DPDP.S9.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-01.4', 'DPDPA', 'DPDP.S9.3', 'Guardian consent for persons with disability', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-01.4' AND framework_ref='DPDPA' AND citation='DPDP.S9.3') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'TPR-03.1', 'DPDPA', 'DPDP.S8.5', 'Accountability for processor and agent processing', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='TPR-03.1' AND framework_ref='DPDPA' AND citation='DPDP.S8.5') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.1', 'DPDPA', 'DPDP.S8.9', 'Contact of the person answering data questions published', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.1' AND framework_ref='DPDPA' AND citation='DPDP.S8.9') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.1', 'DPDPA', 'DPDP.R9.1', 'Contact details displayed prominently', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.1' AND framework_ref='DPDPA' AND citation='DPDP.R9.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'GOV-01.1', 'DPDPA', 'DPDP.S8.11', 'Policy set gives effect to the Act', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='GOV-01.1' AND framework_ref='DPDPA' AND citation='DPDP.S8.11') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'GOV-02.2', 'DPDPA', 'DPDP.GOV.1', 'Personal data processing is risk assessed', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='GOV-02.2' AND framework_ref='DPDPA' AND citation='DPDP.GOV.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'HRS-02.1', 'DPDPA', 'DPDP.GOV.2', 'Staff handling personal data are trained', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='HRS-02.1' AND framework_ref='DPDPA' AND citation='DPDP.GOV.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.1', 'DPDPA', 'DPDP.S10.1', 'Data Protection Officer appointed and based in India', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.1' AND framework_ref='DPDPA' AND citation='DPDP.S10.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'GOV-03.2', 'DPDPA', 'DPDP.S10.2', 'Independent data auditor appointed', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='GOV-03.2' AND framework_ref='DPDPA' AND citation='DPDP.S10.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.2', 'DPDPA', 'DPDP.R12.1', 'Annual Data Protection Impact Assessment', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.2' AND framework_ref='DPDPA' AND citation='DPDP.R12.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'GOV-03.3', 'DPDPA', 'DPDP.R12.2', 'Annual audit and report to the Board', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='GOV-03.3' AND framework_ref='DPDPA' AND citation='DPDP.R12.2') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.2', 'DPDPA', 'DPDP.R12.3', 'Algorithmic due diligence', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.2' AND framework_ref='DPDPA' AND citation='DPDP.R12.3') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.4', 'DPDPA', 'DPDP.R12.4', 'Specified personal data localisation', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.4' AND framework_ref='DPDPA' AND citation='DPDP.R12.4') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.4', 'DPDPA', 'DPDP.S16.1', 'Transfers restricted to permitted territories', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.4' AND framework_ref='DPDPA' AND citation='DPDP.S16.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'PRI-03.4', 'DPDPA', 'DPDP.R15.1', 'Transfer conditions met and evidenced', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='PRI-03.4' AND framework_ref='DPDPA' AND citation='DPDP.R15.1') x);
INSERT INTO common_control_mappings (common_control_code, framework_ref, citation, citation_title,
                                     relationship, notes, source, is_active, tenant_id, created_at, updated_at)
SELECT 'TPR-03.2', 'DPDPA', 'DPDP.R15.2', 'Cross-border flows mapped and reviewed', 'SUBSET_OF',
       'Seeded from the DPDPA framework build', 'KASHI', 1, NULL, NOW(6), NOW(6)
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM common_control_mappings
    WHERE common_control_code='TPR-03.2' AND framework_ref='DPDPA' AND citation='DPDP.R15.2') x);

COMMIT;

-- ── 7. Verify — expectations derived, not asserted ─────────────────────────
-- Each row carries its own pass/fail so you do not have to remember my numbers.

SELECT 'sections'      metric_, COUNT(*) actual_, 11  expected_,
       IF(COUNT(*)=11, 'OK','CHECK') verdict_ FROM audit_sections WHERE framework_ref='DPDPA'
UNION ALL
SELECT 'controls',     COUNT(*), 57, IF(COUNT(*)=57,'OK','CHECK') FROM audit_controls WHERE framework_ref='DPDPA'
UNION ALL
SELECT 'tests',        COUNT(*), 57, IF(COUNT(*)=57,'OK','CHECK') FROM audit_tests    WHERE framework_ref='DPDPA'
UNION ALL
SELECT 'section links', COUNT(*), 57, IF(COUNT(*)=57,'OK','CHECK')
  FROM audit_section_control_mappings scm
  JOIN audit_controls a ON a.id = scm.control_id WHERE a.framework_ref='DPDPA';

-- Every DPDPA control resolves to a live UCF leaf. Expect ZERO rows.
SELECT a.control_code, a.common_control_code
  FROM audit_controls a
  LEFT JOIN common_controls c
    ON c.code COLLATE utf8mb4_0900_ai_ci = a.common_control_code COLLATE utf8mb4_0900_ai_ci
   AND c.node_level='CONTROL' AND c.is_active=1
 WHERE a.framework_ref='DPDPA' AND c.code IS NULL;

-- Template reaches all 57 through the section tree.
SELECT COUNT(DISTINCT scm.control_id) AS reachable_, 57 AS expected_
  FROM audit_template_section_mappings tsm
  JOIN audit_sections root ON root.id = tsm.section_id
  JOIN audit_sections s ON s.id = root.id OR s.path LIKE CONCAT(root.path,'%')
  JOIN audit_section_control_mappings scm ON scm.section_id = s.id
 WHERE tsm.template_id = @tpl;

-- Templates 28 and 12 must still read 116 and 41 — your recorded baseline.
SELECT tsm.template_id, COUNT(DISTINCT scm.control_id) AS controls_
  FROM audit_template_section_mappings tsm
  JOIN audit_sections root ON root.id = tsm.section_id
  JOIN audit_sections s ON s.id = root.id OR s.path LIKE CONCAT(root.path,'%')
  JOIN audit_section_control_mappings scm ON scm.section_id = s.id
 WHERE tsm.template_id IN (28,12) GROUP BY tsm.template_id;

-- Automated tests untouched. MUST be 19.
SELECT COUNT(*) AS automated_tests, IF(COUNT(*)=19,'OK','STOP — INTEGRATION AT RISK') verdict_
  FROM audit_tests WHERE automation_key IS NOT NULL;

-- ── 8. Rollback ────────────────────────────────────────────────────────────
--   DELETE FROM audit_section_control_mappings WHERE control_id IN
--     (SELECT id FROM (SELECT id FROM audit_controls WHERE framework_ref='DPDPA') x);
--   DELETE FROM audit_controls WHERE framework_ref='DPDPA';
--   DELETE FROM audit_tests    WHERE framework_ref='DPDPA' AND automation_key IS NULL;
--   DELETE FROM common_control_mappings WHERE framework_ref='DPDPA' AND notes LIKE 'Seeded from the DPDPA%';
--   DELETE FROM audit_template_section_mappings WHERE template_id=@tpl;
--   DELETE FROM audit_sections  WHERE framework_ref='DPDPA';
--   DELETE FROM audit_templates WHERE template_name='KashiGRC DPDP Act 2023 + Rules 2025';
