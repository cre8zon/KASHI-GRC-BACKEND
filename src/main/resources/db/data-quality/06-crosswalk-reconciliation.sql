-- ═══════════════════════════════════════════════════════════════════════════
-- 06 — Reconcile the two ISO/SOC2 crosswalk sources
--
-- CORRECTION TO AN EARLIER FINDING
--
-- I previously reported "23 Annex A controls not mapped to the UCF". That was
-- wrong. I checked only common_control_mappings and missed that
-- audit_controls.common_control_code carries the mapping too. Checked properly:
--
--   Annex A:2022 controls present in audit_controls        93/93
--   ...carrying a valid UCF leaf code                      93/93
--   ...genuinely unmapped by either route                   0
--
-- All 23 resolve. A.8.11 -> PRI-03.5, A.7.9 -> AST-03.3, A.5.21 -> TPR-02.2,
-- and so on. Your crosswalk is complete. Apologies for the noise.
--
-- ── THE REAL FINDING ───────────────────────────────────────────────────────
--
-- There are TWO crosswalk sources and neither is a superset of the other:
--
--                      common_control_mappings   audit_controls   union
--   ISO 27001                    112                   55          116
--   SOC 2                         79                   40           83
--   DPDPA                         24                    0           24
--
-- Four leaf controls are reachable for ISO only via audit_controls:
--   GOV-01.6, GOV-02.1, PRI-03.3, PRI-03.5
-- And four for SOC 2:
--   APP-01.4, GOV-01.2, PRI-03.3, TPR-02.2
--
-- This is not cosmetic. PolicyContextAssembler.candidateControls() builds the
-- enumerated set the model may choose from, and ReferenceIntegrityGuard rejects
-- anything outside it. A control missing from the candidate set can never be
-- suggested — and if the model names it anyway, it is discarded as fabricated.
-- Reading one table silently removes real controls from ISO engagements.
--
-- The module has been changed to union both sources, so it is correct either
-- way. Running this makes the DATA agree as well, which is worth doing because
-- coverage reporting and the tag picker read common_control_mappings alone.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── A. Preview what would be inserted ──────────────────────────────────────
SELECT DISTINCT a.common_control_code, a.framework_ref, a.control_code AS citation, a.name
  FROM audit_controls a
  JOIN common_controls c
    ON c.code = a.common_control_code AND c.node_level = 'CONTROL'
 WHERE a.common_control_code IS NOT NULL
   AND a.framework_ref IN ('ISO27001','SOC2','DPDPA')
   AND NOT EXISTS (
         SELECT 1 FROM common_control_mappings m
          WHERE m.common_control_code = a.common_control_code
            AND m.framework_ref       = a.framework_ref
            AND m.citation            = a.control_code)
 ORDER BY a.framework_ref, a.common_control_code;

-- ── B. Backfill ────────────────────────────────────────────────────────────
-- relationship defaults to INTERSECTS_WITH, the weakest of your three values.
-- That is deliberate: these rows were not curated with a relationship in mind,
-- and asserting EQUAL or SUBSET_OF on their behalf would put a stronger claim
-- in the crosswalk than anyone actually made. Upgrade individually on review.
--
-- source = 'KASHI_DERIVED' so these are distinguishable from your curated rows
-- and can be re-derived or removed without touching hand-authored mappings.
--
-- RUN 03-iso27001-normalise.sql FIRST, or the superseded ISS-*/ISO.* code
-- schemes will be imported as citations alongside the canonical ones.

START TRANSACTION;

INSERT INTO common_control_mappings
  (common_control_code, framework_ref, citation, citation_title, relationship,
   notes, source, is_active, tenant_id, created_at, updated_at)
SELECT DISTINCT
       a.common_control_code,
       a.framework_ref,
       a.control_code,
       LEFT(a.name, 255),
       'INTERSECTS_WITH',
       'Derived from audit_controls during crosswalk reconciliation — review and upgrade the relationship',
       'KASHI_DERIVED',
       1, NULL, NOW(6), NOW(6)
  FROM audit_controls a
  JOIN common_controls c
    ON c.code = a.common_control_code AND c.node_level = 'CONTROL'
 WHERE a.common_control_code IS NOT NULL
   AND a.framework_ref IN ('ISO27001','SOC2','DPDPA')
   AND a.name IS NOT NULL
   AND NOT EXISTS (
         SELECT 1 FROM common_control_mappings m
          WHERE m.common_control_code = a.common_control_code
            AND m.framework_ref       = a.framework_ref
            AND m.citation            = a.control_code);

COMMIT;

-- ── C. Verify ──────────────────────────────────────────────────────────────
SELECT framework_ref, source, COUNT(*) rows_, COUNT(DISTINCT common_control_code) controls_
  FROM common_control_mappings WHERE is_active = 1
 GROUP BY framework_ref, source ORDER BY framework_ref, source;

-- Expect ISO27001 to reach 116 distinct leaf controls across both sources.
