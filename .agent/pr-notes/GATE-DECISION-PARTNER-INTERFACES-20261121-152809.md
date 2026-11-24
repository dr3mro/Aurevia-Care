# GATE-DECISION-PARTNER-INTERFACES — worker notes

## Summary

Created `docs/launch/partner-interfaces.md`, a decision pack for a person to
approve laboratory, PACS/imaging and pharmacy interface contracts, credentials
and outage drills. The document prepares evidence and questions; it chooses no
option, names no partner, jurisdiction, region, person or date, and leaves the
approval record empty. Status line: `Not approved`. No code, migration,
dependency, credential or patient data was added.

## Affected requirements

- Primary: **ARC-05** (Delivery), **XCH-04** (result routing and reconciliation).
- Referenced: XCH-01, XCH-02, XCH-03, PRD-02, LAB-02, RAD-01, RX-02, OPS-01,
  OPS-05 and the G5 row of section 9.
- Source of accountable roles: section 10, "Lab, PACS and pharmacy interface
  contracts" → Integration lead and partner owner.

## Risk class

Cross-boundary / safety-critical hand-off (docs/WORKFLOW.md) because it frames
partner interfaces, scoped credentials and critical-result reconciliation. This
change itself is documentation only and enables no traffic.

## Review owner

Integration lead and partner owner (section 10) are the accountable decision
makers. `orevia-senior-integrations` provided a bounded, simulated advisory
review; no human, clinical or partner evidence was used or claimed.

## Data changes

None. No migration, no backfill, no schema or lock/volume impact. No credential
value, real domain, patient data or PHI appears in the document or these notes.

## Authorization boundaries and audit events

No authorization rule or audit event was changed. The pack states that live
partner traffic must stay disabled until a person accepts the evidence and
records the approval, and it points to the existing tenant-isolation decision
for any future adapter.

## Migration and rollout considerations

None in this change. Enabling a partner interface, and the roll-forward or
rollback criteria, belong to the human decision in `S-G5-04`, not to this pack.

## Test evidence

Exact commands run in the repository root on branch
`swarm/GATE-DECISION-PARTNER-INTERFACES-447` at HEAD `393b42b` (tool evidence):

1. `python3 -I scripts/check-docs.py` → exit 0, no output. Final line from the
   wrapper: `check-docs exit: 0`. This validates that every relative link in the
   new file resolves and every requirement ID mentioned is defined in
   `PROJECT_V1_SPEC.md`.
2. `git diff --check` → exit 0. Final line: `diff-check exit: 0`.
3. Heading check: `grep -n '^## ' docs/launch/partner-interfaces.md` confirmed
   all eight required headings at lines 12, 25, 61, 72, 108, 130, 153 and 189.
4. Status check: `grep -n 'Not approved' docs/launch/partner-interfaces.md`
   returned line 3, `Status: **Not approved**.`.
5. Checkbox check: `grep -c '^- \[ \]' docs/launch/partner-interfaces.md`
   returned 19 unchecked artifacts; none is checked.

No unit or integration tests apply: no production code, SQL or script changed.
No frontend area was touched, so `apps/patient-mobile/check.sh` and
`apps/staff-web/check.sh` (and their typecheck steps) were not run for this
task. Entry point to evidence: `docs/launch/partner-interfaces.md` → section 10
and G5 of `PROJECT_V1_SPEC.md` → the linked synthetic contract fixtures and
evidence templates listed under "What the repository already supports".

## Coverage

Not applicable. No production line, branch, method or class was added or
changed, and no test was added, skipped, weakened or excluded. No coverage
threshold or exclusion was altered. `python3 -I scripts/check-docs.py` is the
applicable documentation gate and passed (see Test evidence).

## Clinical and architecture review

No clinical or architecture approval is recorded: this pack is the input to
such a decision. A bounded simulated `orevia-senior-integrations` review
(tool: subagent, session `ses_e014483c1ffe7e4GA1zUF0qcoP`, read-only) returned
**MET** for all four acceptance criteria (per-partner five artifacts, exact
headings and structures, chooses/invents nothing with status `Not approved`,
and no patient data or credentials), with **no defects found**. It verified the
14 linked files exist, the section 10 roles match the blank approval record,
and the cited task IDs and states against `TODO.md`. No build, coverage or CI
check was run by the reviewer; real partner conformance, credential approval and
drill results must be supplied by the site and accepted by the accountable roles.

## Findings

- Specialist review outcome: no defects; all four acceptance criteria MET. No
  change to the document was needed after review.
- `docs/spec/clauses` does not exist in this checkout. The story asked for
  clause notes under that directory; the related `SPEC-*` clause-note tasks are
  still open, so there were no clause notes to read. The authoritative
  specification sections (7, 9 G5 row, 10) and the operations register were
  used instead. This is recorded in the document's "What is missing" section.
- The missing partner evidence (signed contracts, conformance results, approved
  credentials, reconciliation evidence and timed outage drills) is the subject
  of the pack, not a blocker to writing it; all of it is intentionally listed as
  unchecked or `NO TASK`.
- Open/taken tasks that must supply real evidence are named with their IDs and
  states at the time of writing: BUILD-35, BUILD-36, BUILD-37, BUILD-38 (open),
  BUILD-08, BUILD-10, START-04, START-16 (taken), READY-27, BUILD-11 (done),
  SPEC-LAYER-IDENTITY-4, SPEC-LAYER-IDENTITY-5,
  SPEC-LAYER-INTEROPERABILITY-3, SPEC-PRD-02-C02, SPEC-PRD-06-C02,
  SPEC-XCH-04-C01/C02/C03 (open) and S-G5-04 (open, the human decision).
- `apps/staff-web/src/features/results` does not exist in this tree, so it was
  not linked; the result surface was described by its API contract and service
  files instead.

## Lessons for workers

- For a decision pack that must decide nothing, keep the options table neutral
  and put every site-supplied artifact in an unchecked checklist; a completed
  row or a named example is easily mistaken for an approval.
- `python3 -I scripts/check-docs.py` scans all Markdown outside `.agent/` and
  `docs/archive/`; relative links from a new `docs/launch/` file must climb two
  levels (`../../`) and every requirement ID must exist in the specification.
- Verify candidate link targets with `test -e` before writing them; a feature
  directory named in `TODO.md` may not exist in the current tree.
