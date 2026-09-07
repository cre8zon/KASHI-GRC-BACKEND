# India framework seeds — corrected

## Run order

```
19-collation-fix.sql      PREREQUISITE — read it, then run the ALTERs
21a-undo-failed-v1.sql    clear the partial rows the failed v1 left behind
20-cleanup-debris.sql     remove testing debris, keep tpl 28 + 12
21-seed-dpdpa.sql         v2 — DPDP Act 2023 + Rules 2025
22-seed-cert-in.sql       scaffold, pending your customer mix
```

`09-section-path-integrity.sql` from the data-quality pack still runs before `20`.

## What went wrong in v1 — all three were mine

**Error 1265 — `Data truncated for column 'test_type'`.** I invented
`DOCUMENT` / `INSPECTION` / `AUTOMATED`. Your enum is `DOCUMENT_REVIEW`,
`TECHNICAL_TEST`, `OBSERVATION`, `WALKTHROUGH`, `INTERVIEW`. Every
`audit_controls` INSERT was rejected.

**Error 1048 — `Column 'control_id' cannot be null`.** A cascade, not a separate
fault. The control INSERT above each one failed, so the subselect resolving
`control_id` returned NULL. Fixing the enum removes all of these.

**Error 1048 — `Column 'template_id' cannot be null`.** `audit_type` is
`INTERNAL` or `EXTERNAL`; I wrote `COMPLIANCE`. The template INSERT failed, `@tpl`
stayed NULL, and everything downstream failed on a null FK — silently, because a
failed `INSERT … SELECT … WHERE NOT EXISTS` doesn't stop the script.

v2 fixes the values and adds a **STOP check** after each variable assignment, so
a null `@tpl` is reported immediately instead of producing 200 confusing errors.
There's also a section 0 that dumps the actual enum definitions from
`information_schema` — run it first and confirm my values match before the
inserts.

## Error 1267 is the important one

```
Illegal mix of collations (utf8mb4_0900_ai_ci) and (utf8mb4_unicode_ci)
```

Your `audit_*` tables are `utf8mb4_unicode_ci`; `common_controls` is
`utf8mb4_0900_ai_ci`. Every join between the audit module and the UCF is on a
VARCHAR — `audit_controls.common_control_code = common_controls.code`,
`audit_tests.control_tag = common_controls.code`. Those are the joins the entire
UCF design rests on.

Measured, from your run:

| | |
|---|---|
| `utf8mb4_unicode_ci` | 123 tables (older majority) |
| `utf8mb4_0900_ai_ci` | 44 tables (newer minority) |
| database default | `utf8mb4_0900_ai_ci` |

**`19` v4 converges on `utf8mb4_0900_ai_ci`** — the better collation, and the one
your database default already points at, so nothing new drifts once the last
table converts and there is no ongoing discipline requirement.

**No code changes.** Collation is a storage-layer property. Hibernate never
emits or reads `COLLATE`, no `@Column` specifies one, and the JDBC driver
negotiates connection charset separately. Every entity, repository and query is
untouched. The explicit `COLLATE` clauses in `20` and `21` simply become no-ops.

The one behavioural difference is padding: `unicode_ci` is PAD SPACE,
`0900_ai_ci` is NO PAD, so `'IAM-02.3 '` stops equalling `'IAM-02.3'`. That
fails silently — fewer rows, no error. I checked every join column in your
exports: **0 padded values, 0 non-ASCII**. Step 1 generates the check for the
remaining 158 tables; run it and confirm empty before proceeding.

## Undo

`21a` clears v1's partial state. `21` v2 ends with a full rollback block — the
seed is keyed entirely on `framework_ref='DPDPA'`, so it comes out as cleanly as
it goes in. Your 24 curated DPDPA mappings survive both; only rows tagged
`notes LIKE 'Seeded from the DPDPA%'` are removed.

Every delete in every script carries `AND automation_key IS NULL`. The count of
automated tests must read **19** at the end of each script, and each one checks.
