# `audit_tests` tagging — correction

I earlier called `control_tag` and `common_control_code` *"two columns for one
relationship, disagreeing"* and shipped backfill SQL. That was wrong on the
facts, and you were right to push back.

## What the data actually shows

| | |
|---|---|
| `control_tag` populated | **157 / 159** |
| ...resolving to a valid UCF **leaf** control | **157 / 157 (100%)** |
| ...resolving to a DOMAIN or FAMILY node | 0 |
| ...not in the UCF at all | 0 |
| `common_control_code` populated | 41 / 159 |
| Rows where both are populated | 41 |
| Rows where they **disagree** | **0** |

They never conflict. `control_tag` is complete and clean; `common_control_code`
is a partial denormalised copy of it. My "58 valid leaf codes" figure was
**distinct codes**, not row coverage — I compared a distinct-value count against
a row count and drew a conclusion from the mismatch. That's a straightforward
analysis error on my part.

**Do not run the backfill I suggested.** Nothing needs backfilling.

## Why the separation is right

Tag-based matching is what makes evidence reuse work, and it needs the tag on
the *test*, not a code on a join row:

- `TagExpansionService` expands `IAM-02.3` to `IAM-02.3, IAM-02, IAM`, so
  evidence gathered against a coarse control satisfies finer ones — ancestors
  only, never descendants. That expansion needs a leaf tag as its input, which
  is exactly what `control_tag` guarantees.
- `AuditControlInstance.controlTagSnapshot` freezes the tag at instantiation, so
  a catalogue edit cannot retroactively change what a completed engagement
  matched against.
- 19 tests carry an `automation_key` (`kashiguard.edr_coverage`,
  `kashiguard.tls_enforcement`, `ENCRYPTION_AT_REST`, ...). 13 distinct leaf
  controls have automated evidence.

## Where this matters for the AI module

`automation_key` is a signal I was not using and should be. A control with a
live automated check has **machine-verified** evidence; one without has
document-review evidence at best. Those are different epistemic positions and
the AI should not present them identically.

Two concrete uses, neither built yet — flagging rather than assuming:

1. **Gap analysis** should rank a control with a failing automated check above
   one with no evidence at all. The first is a known deficiency; the second is
   an unknown.
2. **Control mapping** could surface "this control has continuous monitoring"
   beside a suggestion. A reviewer accepting a mapping backed by
   `kashiguard.edr_coverage` is accepting something checkable.

Tell me if that framing matches how you intend `kashiguard.*` to be consumed and
I'll wire it into `PolicyContextAssembler` and the gap prompt.

## Still useful from the earlier pass

The one real defect stands: a single row has a test description sitting in
`framework_ref`.

```sql
SELECT id, framework_ref, name FROM audit_tests WHERE CHAR_LENGTH(framework_ref) > 20;
```
