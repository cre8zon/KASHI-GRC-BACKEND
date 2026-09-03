-- ═══════════════════════════════════════════════════════════════════════════
-- 21a — Clear the partial rows left by the failed v1 run. Run BEFORE 21 v2.
--
-- v1 failed mid-way: most audit_controls INSERTs were rejected on the test_type
-- enum, but the audit_tests INSERTs succeeded (you can see "1 row(s) affected"
-- on statement 272 in your output). So you have DPDPA tests with no controls,
-- and a handful of controls that slipped through. Clear all of it and re-run
-- cleanly rather than trying to reconcile a half-applied script.
--
-- The automation_key guard is here too, even though nothing v1 created had one.
-- ═══════════════════════════════════════════════════════════════════════════

START TRANSACTION;

SELECT 'controls' t, COUNT(*) n FROM audit_controls WHERE framework_ref='DPDPA'
UNION ALL SELECT 'tests',    COUNT(*) FROM audit_tests    WHERE framework_ref='DPDPA'
UNION ALL SELECT 'sections', COUNT(*) FROM audit_sections WHERE framework_ref='DPDPA'
UNION ALL SELECT 'mappings', COUNT(*) FROM common_control_mappings
   WHERE framework_ref='DPDPA' AND notes LIKE 'Seeded from the DPDPA%';

DELETE FROM audit_section_control_mappings
 WHERE control_id IN (SELECT id FROM audit_controls WHERE framework_ref='DPDPA');

DELETE FROM audit_controls WHERE framework_ref='DPDPA';

DELETE FROM audit_tests
 WHERE framework_ref='DPDPA' AND test_ref LIKE 'DPDPA-T-%' AND automation_key IS NULL;

DELETE FROM common_control_mappings
 WHERE framework_ref='DPDPA' AND notes LIKE 'Seeded from the DPDPA%';

DELETE FROM audit_template_section_mappings
 WHERE section_id IN (SELECT id FROM (SELECT id FROM audit_sections WHERE framework_ref='DPDPA') x);

DELETE FROM audit_sections  WHERE framework_ref='DPDPA';
DELETE FROM audit_templates WHERE template_name='KashiGRC DPDP Act 2023 + Rules 2025';

COMMIT;

-- All four counts should now be zero. Your 24 curated DPDPA mappings survive:
SELECT COUNT(*) AS curated_dpdpa_mappings_kept FROM common_control_mappings
 WHERE framework_ref='DPDPA';
-- Expect 24.

SELECT COUNT(*) AS automated_tests FROM audit_tests WHERE automation_key IS NOT NULL;
-- Expect 19.
