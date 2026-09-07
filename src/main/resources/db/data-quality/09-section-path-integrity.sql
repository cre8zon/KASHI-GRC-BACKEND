-- ═══════════════════════════════════════════════════════════════════════════
-- 09 — audit_sections.path format is inconsistent
--
-- VERIFIED FIRST: the template -> section -> control chain is sound.
--
--   tpl 28  KashiGRC ISO 27001:2022   116 links, 116 resolve, 0 orphans
--            93/93 Annex A  (A.5 x37, A.6 x8, A.7 x14, A.8 x34 — exact)
--            23/23 clause items (CL.4.1 ... CL.10.2)
--            every link carries a valid UCF leaf code
--   tpl 12  SOC 2 Type II              41 links, 41 resolve, 40 UCF leaves
--
-- No dangling control_ids anywhere. This is a clean model.
--
-- ── THE ONE DEFECT ─────────────────────────────────────────────────────────
-- Two path conventions coexist:
--
--   /599/600/     leading and trailing slashes   (older sections)
--   651/652/653   neither                        (the newer ISMS tree, incl. tpl 28)
--
-- Materialised-path subtree queries are written as
--   WHERE path LIKE CONCAT(parent.path, '%')
-- and that only works if the separator is present at both ends. Without a
-- trailing slash, '/6/' also prefix-matches '/61/' and '/612/' — the query
-- returns MORE sections than the subtree contains, silently. With the current
-- id ranges you would not notice; at 700+ sections you would, and the symptom
-- would be a control appearing in the wrong template's scope.
--
-- PolicyContextAssembler.candidateControlsForTemplate() currently matches both
-- forms defensively. Normalise the data and that defensive branch becomes
-- unnecessary.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── A. See the split ───────────────────────────────────────────────────────
SELECT CASE
         WHEN path LIKE '/%/' THEN 'slash-wrapped (correct)'
         WHEN path LIKE '/%'  THEN 'leading only'
         WHEN path LIKE '%/'  THEN 'trailing only'
         ELSE 'bare'
       END AS form,
       COUNT(*), MIN(id), MAX(id)
  FROM audit_sections GROUP BY form;

-- ── B. Normalise to /a/b/c/ ────────────────────────────────────────────────
START TRANSACTION;

UPDATE audit_sections
   SET path = CONCAT('/', TRIM(BOTH '/' FROM path), '/'), updated_at = NOW(6)
 WHERE path IS NOT NULL AND path <> '' AND path NOT LIKE '/%/';

COMMIT;

-- ── C. Verify the path agrees with parent_id ───────────────────────────────
-- A materialised path that disagrees with the pointer it denormalises is worse
-- than no path at all, because queries silently trust it.
SELECT c.id, c.section_code, c.path AS child_path, p.path AS parent_path
  FROM audit_sections c
  JOIN audit_sections p ON p.id = c.parent_id
 WHERE c.path <> CONCAT(p.path, c.id, '/');

-- Roots must be '/{id}/':
SELECT id, section_code, path FROM audit_sections
 WHERE parent_id IS NULL AND path <> CONCAT('/', id, '/');

-- ── D. Confirmed safe: 03-iso27001-normalise.sql does NOT break tpl 28 ─────
-- I checked every template against the supersede list before recommending it:
--
--   tpl 28  KashiGRC ISO 27001:2022   116 links,  0 would be superseded
--   tpl 12  SOC 2 Type II              41 links,  0 would be superseded
--   tpl 16  ISO 27001 (UNPUBLISHED)    21 links, 21 would be superseded
--   tpl 25  ISO Annex A (UNPUBLISHED)  10 links, 10 would be superseded
--
-- Only the two inactive, unpublished templates lose controls — they are the
-- earlier drafts that template 28 replaced, so that is the intended outcome
-- rather than collateral damage. Run 03 with confidence.
