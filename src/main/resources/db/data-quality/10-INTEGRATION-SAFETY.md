# Integration & automated evidence — safety audit

You asked me to be careful with this. Rather than reassure you, here is what I
verified in your code and what each thing I've handed you actually touches.

## How automated evidence works in your codebase

From `AuditTestEvidenceMatcher`:

- **AUTOMATED tests are excluded from tag matching entirely.** They receive
  results through `EngagementIntegrationSnapshotService.recordResult()` using the
  precise `checkKey → testInstanceId` mapping established at engagement snapshot
  time.
- **MANUAL / HYBRID tests** match on `controlTagSnapshot` against the frozen
  expanded tag set, with a legacy exact-match fallback.

So the automated path depends on exactly three things:

1. `audit_tests.automation_key` — the `checkKey`
2. `automation_type = 'AUTOMATED'`
3. the `checkKey → testInstanceId` map created at engagement instantiation

**Nothing I have given you writes to any of the three.**

## Your architecture already protects you

`AuditControlInstance` freezes `controlTagSnapshot`, `controlCodeSnapshot`,
`descriptionSnapshot`, `evidenceGuidanceSnapshot`, `sectionBreadcrumbSnapshot`
and `testTypeSnapshot` at instantiation — with the comment "editing the library
must not silently change a running engagement."

That is the correct design and it means **library edits cannot reach a running
engagement at all.** Everything below only affects engagements instantiated
*after* it runs.

## The AI module: zero risk

Writes to seven tables, all new, all `ai_`-prefixed:

```
ai_interactions   ai_prompt_templates   ai_document_chunks   ai_org_profiles
ai_usage_counters ai_suggestion_feedback ai_ingestion_jobs
```

Verified by grep across the module: **0 writes** to `AuditControl`, `AuditTest`,
`AuditPolicy`, `CommonControl`, `CommonControlMapping`, `AuditSection`,
`AuditTemplate`, `EvidenceRecord`, `Integration`, `AuditControlInstance`.

The module does not import the `integration` package at all. `AuditPolicy` and
the UCF types are read-only (`@Transactional(readOnly = true)`), and every
generation returns a suggestion — the write goes through your existing
controller and lifecycle.

## The SQL pack: risk-rated

| Script | Touches | Risk to automated evidence |
|---|---|---|
| `01` control descriptions | `common_controls.description` NULL→text | **None.** Not read by the matcher, and `descriptionSnapshot` is frozen anyway. |
| `06` crosswalk backfill | inserts `common_control_mappings` | **None.** `TagExpansionService` reads `CommonControlRepository` — the `parent_code` tree — and never touches the mappings table. I checked specifically because adding ~200 rows *would* have been dangerous if expansion read them. It doesn't. |
| `04` quarantine junk | `framework_ref` on `gfjih`/`bzxbz` rows | **None.** No automation keys on those rows. |
| `03` ISO normalise | `framework_ref`, `control_code` on `audit_controls` | **Low, verified.** Template 28 and SOC 2 lose 0 controls; only templates 16 and 25 are affected and both are UNPUBLISHED with `is_active = 0`. `automation_key` lives on `audit_tests`, which this does not touch. |
| `09` section paths | `audit_sections.path` | **Low.** `sectionBreadcrumbSnapshot` is frozen. Affects new instantiation only. |
| `07 F` empty policies | `audit_policies.status` APPROVED→DRAFT | **Behavioural — read this one.** `AuditTestPolicySnapshotService` skips policies that are not APPROVED. These 14 are `"content pending"` placeholders, so excluding them is the intent — but it is a real behaviour change and only you can confirm it's wanted. |

## Suggested order

Run the zero-risk ones first, confirm automated checks still pass, then proceed.

```
01  →  06  →  04     verify a full integration run  →  09  →  03  →  07
```

## Pre-flight and post-flight verification

Run before, and again after, and diff:

```sql
-- 1. Automation surface — must be IDENTICAL before and after
SELECT automation_type, COUNT(*), COUNT(DISTINCT automation_key)
  FROM audit_tests GROUP BY automation_type;

SELECT automation_key, control_tag, framework_ref
  FROM audit_tests WHERE automation_key IS NOT NULL ORDER BY automation_key;

-- 2. Every automated test's tag must still resolve to a live UCF leaf
SELECT t.automation_key, t.control_tag
  FROM audit_tests t
  LEFT JOIN common_controls c
    ON c.code = t.control_tag AND c.node_level = 'CONTROL' AND c.is_active = 1
 WHERE t.automation_key IS NOT NULL AND c.code IS NULL;
-- Expect zero rows. Any row here means an automated check lost its anchor.

-- 3. Running engagements — snapshots must be untouched
SELECT COUNT(*) AS instances,
       COUNT(DISTINCT control_tag_snapshot) AS distinct_tags
  FROM audit_control_instances;

-- 4. The checkKey routing map
SELECT COUNT(*) FROM audit_control_instance_test_mappings;
```

If (2) returns rows or (3)/(4) change, stop and tell me — that would mean I got
something wrong, and I'd rather find out from a query than from a broken check.
