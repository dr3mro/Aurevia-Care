# GATE-DECISION-PARTNER-INTERFACES — fix-pass worker notes

## Summary

Second-pass fix of the documentation-only decision pack `docs/launch/partner-interfaces.md`
(the pack itself was created by the first pass; see
`docs/pr/notes/GATE-DECISION-PARTNER-INTERFACES-20261121-152809.md`). No option was
chosen, no jurisdiction/region/partner/person/date was invented, no approval cell was
filled, and the status line remains `Not approved`. This pass applied three minimal
accuracy corrections found by a fresh bounded specialist review; no other file changed,
no dependency, migration, credential or patient data was added.

## Affected requirements

- Primary: **ARC-05** (durable delivery), **XCH-04** (result routing and reconciliation).
- Cited in the pack: XCH-01, XCH-02, XCH-03, PRD-02, LAB-02, RAD-01, RX-02, OPS-01,
  OPS-05 and the G5 row of section 9; accountable roles from section 10
  ("Integration lead and partner owner").
- Acceptance mapping: all eight required headings remain present and ordered; per-partner
  interface contract/channel, conformance, credentials, reconciliation and timed outage
  drill remain in the Decision-needed table; 19 checklist boxes remain unchecked; the
  approval record still has the two section 10 roles with Name/Date/Decision empty.

## Risk class

Cross-boundary / safety-critical hand-off (docs/WORKFLOW.md) because the pack frames
partner interfaces, scoped credentials and critical-result reconciliation. This change
is documentation only and enables no traffic.

## Review owner

Integration lead and partner owner (spec section 10) are the accountable decision makers.
This fix was reviewed by `aurevia-senior-integrations` as a bounded, simulated read-only
advisory review (subagent session `ses_e00e07183ffevIQUSdQVRn0Wbz`). No human, clinical,
partner or simulated-user evidence was used or claimed.

## Data changes

None. No migration, backfill, schema, lock or volume impact; no reserved migration version
was used. No credential value, real domain, patient data or PHI appears in the change.

## Authorization boundaries and audit events

None changed. The pack continues to state that live partner traffic stays disabled until a
person accepts the evidence and records the approval, and points to the tenant-isolation
decision for any future adapter. No audit event was added or altered.

## Migration and rollout considerations

None in this change. Enabling a partner interface and its roll-forward/rollback criteria
belong to the human decision in `S-G5-04`, not to this pack.

## Test evidence

Evidence sources: **tool** (executed shell/Python commands below), **reviewer** (simulated
`aurevia-senior-integrations` subagent). No simulated-user or human-participant evidence.

Red/green, test-first trace on branch `swarm/GATE-DECISION-PARTNER-INTERFACES-447` at base
SHA `a68aadbec9809f5c260dbbf1739e2e64c9c6eede`:

1. Wrote a synthetic acceptance/regression checker outside the repo at
   `/tmp/opencode/verify_partner_pack.py` (not committed; no scope violation). It asserts
   the eight headings, status line, per-partner five artifacts, four-column options table,
   requirement-ID citations, unchecked evidence checklist, empty approval record, absence
   of years, `check-docs.py` green, and three new fix regressions.
2. RED before edits: `python3 /tmp/opencode/verify_partner_pack.py` → exit 1 with exactly
   `FIX-1`, `FIX-2`, `FIX-3` (the three reviewer findings). Final line: `FAIL`.
3. Applied the three minimal edits to `docs/launch/partner-interfaces.md`.
4. GREEN after edits: `python3 /tmp/opencode/verify_partner_pack.py` → exit 0, final line
   `PASS: acceptance criteria + 3 fix regressions; 19 unchecked boxes; check-docs green`.

Repository gates actually executed (tool evidence):

| Command | Final line / result |
|---|---|
| `python3 -I scripts/check-docs.py` | exit 0, no output (link + requirement-ID integrity) |
| `python3 -I scripts/check-secrets.py` | `check-secrets exit: 0` |
| `python3 -I scripts/check-names.py` | `check-names exit: 0` |
| `git diff --check` | `diff-check exit: 0` |
| `python3 /tmp/opencode/verify_partner_pack.py` | `PASS: ... check-docs green` (exit 0) |

Entry point to evidence: `docs/launch/partner-interfaces.md` → sections 9 (G5 row) and 10
of `PROJECT_V1_SPEC.md` → the linked synthetic fixtures and evidence templates under
"What the repository already supports".

`scripts/check.sh` was **not** run: it invokes `apps/api/check.sh` (full `mvn verify` with
JaCoCo/OWASP/SpotBugs) and `db/check.sh` (every PostgreSQL integration test), which the
worker instructions forbid running locally. The integrator and Delivery run that full
gate; the applicable worker gate for a Markdown-only change (`check-docs.py`) passed.
No frontend area was touched, so `apps/staff-web/check.sh` and
`apps/patient-mobile/check.sh` (and their `tsc --noEmit` steps) are not applicable and
were not run; there is no `error TS` to fix.

## Coverage

Not applicable. No production line, branch, method or class, no script line or branch, and
no test was added, changed, skipped, weakened or excluded; no threshold or exclusion was
altered. The applicable documentation gate is `python3 -I scripts/check-docs.py`, which
passed (exit 0). No new code branch exists, so there is no branch/test pairing to list.
The three corrected statements are covered by the three `FIX-*` assertions in the synthetic
checker (RED before, GREEN after), recorded under Test evidence.

## Clinical and architecture review

No clinical or architecture approval is recorded: this pack is the input to such a
decision. Real partner conformance, credential approval, reconciliation evidence and drill
results must be supplied by the site and accepted by the accountable roles. The bounded
simulated integrations review found no remaining structural defect after the fixes.

## Findings

Three defects were found by the fresh simulated review and all three were fixed:

- **FIX-1 (MEDIUM)** — `SPEC-PRD-02-C02` was labeled `(open)`, but `TODO.md:4217` marks it
  `[-]` (taken). Corrected to `(taken)` and clarified that the note records a governance
  boundary, not an approval. The other ~16 cited task states were verified unchanged.
- **FIX-2 (LOW)** — "both are empty templates" was false: `drill-evidence.md` is an empty
  template, but `operational-runbooks.md` is a fully specified declared envelope. Corrected
  to state each accurately while keeping the "neither is a completed drill" conclusion.
- **FIX-3 (LOW)** — "the clause note directory `docs/spec/clauses` does not exist at the
  time of writing" is literal truth in this branch but misleading after merge, because
  `origin/main` now carries `docs/spec/clauses/product/partner-specific-interfaces-require-approval.md`
  (the `SPEC-PRD-02-C02` note). Replaced with a branch-scoped statement that names the
  tracked clause note without linking to a file absent from this tree, plus a reminder that
  the task states are a snapshot to re-check before the approval meeting.

Decisions made: keep all corrections minimal and within the single-file scope; do not add
a Markdown link to the clause note because the target is not in this branch and
`check-docs.py` would fail; do not run the full integration gate locally. No remaining
defect or blocker; the missing partner evidence is the subject of the pack and is
intentionally listed as unchecked or `NO TASK`.

## Lessons for workers

- A decision pack that must decide nothing needs exact, verified task states in "What is
  missing": a stale `(open)` is a factual defect even when the pack is otherwise neutral.
- Describe linked evidence files by what they actually are; two related templates in one
  folder need not have the same status (an empty template vs a declared envelope).
- Avoid "directory X does not exist" claims in a worker branch: the delivery branch may
  have moved on. Branch-scope the statement or name the tracked artifact without linking
  to a path that is absent locally (`check-docs.py` fails on broken relative links).
- When a fix pass arrives without explicit findings, a fresh bounded specialist review
  plus a synthetic acceptance checker is an efficient way to locate and prove the fixes;
  assert the fixes RED before editing and GREEN after so the hand-off carries a real trace.
