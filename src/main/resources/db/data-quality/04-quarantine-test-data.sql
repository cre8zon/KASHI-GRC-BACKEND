-- ═══════════════════════════════════════════════════════════════════════════
-- 04 — Quarantine obvious test data before AI ingestion
--
-- These rows will otherwise be embedded into the retrieval corpus and become
-- candidate controls in mapping suggestions. 'tujexnk' appearing in a control
-- picker during an investor demo is a small thing that reads very badly.
--
-- Deactivates rather than deletes. The RBI and PSS rows look like genuine
-- work-in-progress rather than keyboard-mashing, so they are flagged
-- separately for your judgement rather than swept up with the rest.
-- ═══════════════════════════════════════════════════════════════════════════

START TRANSACTION;

-- ── Clear junk ──────────────────────────────────────────────────────────────
UPDATE audit_controls SET framework_ref = 'TEST_DATA', updated_at = NOW(6)
 WHERE framework_ref IN ('gfjih','bzxbz');

-- ── Rows with no framework and no name ──────────────────────────────────────
UPDATE audit_controls SET framework_ref = 'TEST_DATA', updated_at = NOW(6)
 WHERE framework_ref IS NULL AND (name IS NULL OR name = '');

COMMIT;

-- ── FOR YOUR REVIEW, NOT AUTOMATED ─────────────────────────────────────────
-- RBI (10 rows) and PSS (3 rows) look like real in-progress frameworks.
-- If they are not client-ready, exclude them from AI grounding without
-- deleting them:
--
--   UPDATE audit_controls SET framework_ref = 'RBI_DRAFT'
--    WHERE framework_ref = 'RBI';
--
-- The AI module only grounds in what candidateControls() returns, which joins
-- through common_control_mappings — so an unmapped framework is already
-- invisible to generation. Renaming is belt-and-braces.

-- ── Also check: audit_templates ─────────────────────────────────────────────
-- 'RBI SAR PSS kdsksdv' is PUBLISHED and active. Unpublish before any demo:
--   UPDATE audit_templates SET is_active = 0, status = 'UNPUBLISHED' WHERE id = 18;
