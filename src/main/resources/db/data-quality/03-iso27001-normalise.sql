-- ═══════════════════════════════════════════════════════════════════════════
-- 03 — Normalise the ISO 27001:2022 control set
--
-- WHAT THE DATA ACTUALLY SHOWS
--
-- Your ISO framework is genuinely complete. All 93 Annex A:2022 controls are
-- present (A.5.1-5.37, A.6.1-6.8, A.7.1-7.14, A.8.1-8.34) and all of clauses
-- 4-10 are covered. That is a real, sellable framework and better than most
-- early-stage GRC catalogues.
--
-- The problem is not coverage. It is that FOUR competing code schemes describe
-- the same requirements, accreted over time:
--
--     A.5.1        <- canonical 2022        (92 rows)  KEEP
--     CL.5.1       <- canonical clause      (23 rows)  KEEP
--     ISO.5.1.a    <- clause sub-item       (19 rows)  fold into CL.*
--     ISS-5-1-C2   <- older Annex A form    (11 rows)  fold into A.*
--     C.5.1        <- clause variant        (10 rows)  fold into CL.*
--     ISO(C).4.1   <- clause variant        ( 4 rows)  fold into CL.*
--     A.5.1.1      <- ISO 27001:2013 numbering (2 rows) RETIRE — wrong standard version
--
-- 162 rows describe 121 distinct requirements.
--
-- WHY THIS MATTERS FOR AI SPECIFICALLY
--
-- ReferenceIntegrityGuard validates model output against an enumerated
-- candidate set. If that set contains A.5.1, ISS-5-1-C2 and A.5.1.1 as three
-- separate options for one requirement, three things break:
--   1. the model picks inconsistently between runs, so eval results look noisy
--      when the prompt is fine
--   2. coverage dashboards triple-count A.5.1 and report false confidence
--   3. a reviewer sees three suggestions that are the same control and stops
--      trusting the panel — the fastest way to kill adoption of the feature
--
-- REVIEW EACH BLOCK BEFORE RUNNING. This deactivates rather than deletes, so
-- it is reversible, and nothing is lost if a judgement call here is wrong.
-- ═══════════════════════════════════════════════════════════════════════════

START TRANSACTION;

-- ── A. Retire ISO 27001:2013 Annex A numbering ─────────────────────────────
-- A.5.1.1 / A.5.1.2 are 2013-edition codes. 2022 flattened these into A.5.1.
-- Leaving them in a set you sell as ":2022" is the kind of detail a sharp
-- prospect's auditor will notice.
UPDATE audit_controls SET framework_ref = 'ISO27001_2013_RETIRED', updated_at = NOW(6)
 WHERE framework_ref = 'ISO27001' AND control_code IN ('A.5.1.1','A.5.1.2');

-- ── B. Fold the redundant clause schemes into CL.* ─────────────────────────

-- ISO.x.y.z sub-item: 19 rows
UPDATE audit_controls SET framework_ref = 'ISO27001_SUPERSEDED', updated_at = NOW(6)
 WHERE framework_ref = 'ISO27001' AND control_code IN ('ISO 6.1.2.a','ISO.5.1.a','ISO.5.1.b','ISO.5.1.c','ISO.5.1.d','ISO.5.1.e','ISO.5.2.a','ISO.5.2.b','ISO.5.2.c','ISO.5.2.d','ISO.5.3.a','ISO.5.3.b','ISO.5.3.c','ISO.6.1.1.a','ISO.6.1.1.b','ISO.6.1.1.c','ISO.6.1.2.b','ISO.6.1.3','iso.6.1.2.a');

-- C.x variant: 10 rows
UPDATE audit_controls SET framework_ref = 'ISO27001_SUPERSEDED', updated_at = NOW(6)
 WHERE framework_ref = 'ISO27001' AND control_code IN ('C.5.1','C.5.2','C.5.3','C.5.4','C.5.5','C.6.1','C.6.2','C.6.3','C.6.4','C.6.5');

-- ISO(C).x variant: 4 rows
UPDATE audit_controls SET framework_ref = 'ISO27001_SUPERSEDED', updated_at = NOW(6)
 WHERE framework_ref = 'ISO27001' AND control_code IN ('ISO(C).4.1','ISO(C).4.2','ISO(C).4.3');

-- ── C. Fold ISS-*-C* into canonical A.x.y ──────────────────────────────────
-- 11 rows. Each duplicates an A.x.y row that already exists.
-- CHECK FIRST: if the ISS-* row carries better description text than its A.x.y
-- twin, migrate the text across before deactivating, rather than losing it.
UPDATE audit_controls SET framework_ref = 'ISO27001_SUPERSEDED', updated_at = NOW(6)
 WHERE framework_ref = 'ISO27001' AND control_code IN ('ISS-5-1-C2','ISS-5-2-C1','ISS-5-3-C1','ISS-6-1-C1','ISS-6-2-C1','ISS-7-2-C1','ISS-7-3-C1','ISS-7-4-C1','ISS-8-1-C1','ISS-8-2-C1','ISS-8-7-C1');

-- ── D. Case and separator inconsistency ────────────────────────────────────
-- 'iso.6.1.2.a' and 'ISO 6.1.2.a' vs 'ISO.6.1.2.a'. String comparison in the
-- reference guard is case-insensitive, but the mapping join is not.
UPDATE audit_controls SET control_code = UPPER(REPLACE(control_code, ' ', '.')), updated_at = NOW(6)
 WHERE framework_ref = 'ISO27001' AND (BINARY control_code <> UPPER(control_code) OR control_code LIKE '% %');

COMMIT;

-- ── E. Verify ────────────────────────────────────────────────────────────────
-- Expect roughly 115 canonical rows remaining, 93 Annex A + clauses 4-10.
SELECT framework_ref, COUNT(*) FROM audit_controls
 WHERE framework_ref LIKE 'ISO27001%' GROUP BY framework_ref;
