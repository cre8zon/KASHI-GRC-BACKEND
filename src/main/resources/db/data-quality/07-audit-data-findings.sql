-- ═══════════════════════════════════════════════════════════════════════════
-- 07 — Findings from audit_policies / audit_tests / mapping tables
--
-- Each block is independent. Review, then run what you agree with.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── A. framework_refs value drift ──────────────────────────────────────────
-- 'ISO 27001' (with a space) exists alongside 'ISO27001'. This is not
-- cosmetic: PolicyContextAssembler.candidateControls() filters with
-- `m.frameworkRef in :frameworks`, an exact match. A policy tagged
-- 'ISO 27001' silently gets the fallback path and is offered the full
-- 134-control catalogue instead of the ISO-mapped subset — and nothing in the
-- logs above DEBUG says so.
UPDATE audit_policies SET framework_refs = 'ISO27001', updated_at = NOW(6)
 WHERE framework_refs = 'ISO 27001';

-- Same check across the other tables that carry a framework code:
SELECT 'audit_policies' t, framework_refs v, COUNT(*) FROM audit_policies GROUP BY framework_refs
UNION ALL SELECT 'audit_tests', framework_ref, COUNT(*) FROM audit_tests GROUP BY framework_ref
UNION ALL SELECT 'audit_controls', framework_ref, COUNT(*) FROM audit_controls GROUP BY framework_ref
UNION ALL SELECT 'ccm', framework_ref, COUNT(*) FROM common_control_mappings GROUP BY framework_ref;


-- ── B. RETRACTED — see 08-test-tagging-notes.md ────────────────────────────
-- I previously claimed control_tag and common_control_code were "two columns
-- disagreeing" and shipped a backfill here. Wrong: control_tag is 157/159
-- populated, 100% valid UCF leaf codes, and agrees with common_control_code on
-- all 41 rows where both exist. Nothing to backfill. The backfill statement has
-- been removed rather than left commented, so it cannot be run by accident.


-- ── C. One test row has a description in the framework_ref column ──────────
-- framework_ref = 'Verify antimalware software is active and up-to-date on all
-- endpoints'. A shifted column on import. Find and correct it:
SELECT id, framework_ref, name, description FROM audit_tests
 WHERE CHAR_LENGTH(framework_ref) > 20;


-- ── D. Duplicate policy_ref ────────────────────────────────────────────────
-- policy_ref reads as a business key in the UI but is not unique in the data.
-- Two policies sharing a ref makes "which POL-xx did the AI cite" ambiguous in
-- the provenance panel, and ai_document_chunks.source_ref inherits it.
SELECT policy_ref, COUNT(*) c, GROUP_CONCAT(id) ids
  FROM audit_policies WHERE policy_ref IS NOT NULL
 GROUP BY policy_ref HAVING c > 1;


-- ── E. 27 of 47 policies have no framework_refs ────────────────────────────
-- Not urgent for generation, but it means those policies contribute nothing to
-- framework coverage reporting, and a customer asking "what covers ISO A.8.24"
-- gets an incomplete answer.
SELECT id, policy_ref, title, status FROM audit_policies
 WHERE framework_refs IS NULL OR framework_refs = '';


-- ── F. Placeholder policies will be indexed as if they were real ───────────
-- 14 of 47 have a body of "<p>Policy content pending. Please update this
-- policy document.</p>". Several are APPROVED, so PolicyCorpusHook indexes
-- them as POLICY_TEMPLATE and retrieval can return "content pending" as
-- grounding for a new draft.
--
-- The hook only indexes APPROVED policies, which is the right rule — but these
-- are approved and empty. Move them back to DRAFT:
UPDATE audit_policies
   SET status = 'DRAFT', updated_at = NOW(6)
 WHERE status = 'APPROVED'
   AND (content_body IS NULL
        OR CHAR_LENGTH(content_body) < 200
        OR content_body LIKE '%content pending%');


-- ── G. CSV export produced 4 malformed rows ────────────────────────────────
-- Rows 17, 18, 21, 22 of the audit_policies export broke the parser: an
-- embedded double-quote inside content_body was not doubled. Almost certainly
-- an export-tool bug rather than corrupt data, but worth confirming the stored
-- HTML is intact before you rely on these as corpus:
SELECT id, policy_ref, title, CHAR_LENGTH(content_body) len
  FROM audit_policies
 WHERE content_body LIKE '%""%' OR content_body LIKE '%\\"%';
