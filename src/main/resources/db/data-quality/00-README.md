# UCF & ISO 27001 data-quality pack

Generated from `common_controls.csv`, `common_control_mappings.csv`,
`audit_controls.csv`, `audit_templates.csv`.

Run in order. Every script deactivates rather than deletes, so all of it is
reversible.

| File | What it does | Effort |
|---|---|---|
| `01-common-control-descriptions.sql` | Requirement text for **all 134** leaf controls, authored fresh | review then run |
| `02-control-descriptions-review-sheet.csv` | The same 134 with domain, family and citations, for review/edit | ~1 hr to review |
| `03-iso27001-normalise.sql` | Collapses four competing ISO code schemes into two | review then run |
| `04-quarantine-test-data.sql` | Hides junk rows from the AI corpus | run before any demo |
| `05-leaf-controls-with-no-framework-mapping.csv` | 4 orphan controls | 10 min |
| `06-crosswalk-reconciliation.sql` | Makes `common_control_mappings` agree with `audit_controls` (my earlier "23 gaps" claim was wrong — corrected inside) | review then run |
| `08-test-tagging-notes.md` | Correction: `control_tag` is fine, do not backfill it | read |
| `10-INTEGRATION-SAFETY.md` | **Read first.** What each script touches and why your automated evidence is safe | read |
| `09-section-path-integrity.sql` | Normalises the two `audit_sections.path` conventions; confirms `03` is safe for template 28 | run it |
| `07-audit-data-findings.sql` | Findings from the policy/test tables — framework value drift, duplicate refs, empty approved policies | review each block |

## The one that actually matters

`01`. **`common_controls.description` is NULL on all 134 leaf controls**, and
that column is precisely what `PolicyContextAssembler.buildControlBlock()`
renders into every generation prompt as `Requirement:`.

These are authored fresh rather than sourced from `audit_controls.description`,
since you're rewriting those. All 134 are covered — nothing is left for you to
write, only to review.

Right now the model receives:

```
[IAM-01.1] Joiner provisioning and approval
  Domain: IAM
```

After the backfill it receives:

```
[IAM-01.1] Joiner provisioning and approval
  Requirement: Access provisioned only after manager approval through a
               formal request process
  Framework citations: SOC2 CC6.2, ISO27001 A.5.18
  Domain: IAM
```

The first produces "access shall be provisioned appropriately". The second
produces a clause that names the approval step. No prompt tuning closes that
gap, because the information is not in the prompt to begin with.

Run `01` before your first demo generation. `02` is the same content as a
review sheet — worth an hour of a domain expert's time, not three.

**Copyright note:** none of these reproduce ISO, AICPA or DPDP requirement
text. They are original summaries written against each control's title, family
and citations. Clause identifiers stay in `common_control_mappings.citation`,
which is correct — identifiers are not protected, the standard's requirement
text is.

## What your data is already good at

Worth stating plainly, because the list above is all problems:

- **16 domains / 41 families / 134 leaf controls**, cleanly hierarchical.
  Zero orphan `parent_code` references, zero mapping rows pointing at
  non-existent controls. That referential cleanliness is unusual.
- **Template 28 `KashiGRC ISO 27001:2022` verified end to end.** 116
  section→control links, all 116 resolve, zero orphans. Annex A coverage is
  93/93 with the exact theme counts (A.5×37, A.6×8, A.7×14, A.8×34) and clauses
  4–10 are complete at 23/23. Every link carries a valid UCF leaf code. This is
  a genuinely client-ready framework and the audit model behind it is clean.
- **All 93 Annex A controls mapped to the UCF.** The issue across the wider
  `audit_controls` table is duplication between code schemes, not coverage.
- **Three-framework mapping table** with typed relationships
  (`EQUAL` / `SUBSET_OF` / `INTERSECTS_WITH`). The relationship type is more
  than most vendors carry, and it is exactly what makes crosswalk claims
  defensible.
- **ISO 27001 maps to 84% of leaf controls**, SOC 2 to 59%.

This is a better grounding asset than the AI module needs on day one. It is
the thing I said mattered more than the model, and you already have it.
