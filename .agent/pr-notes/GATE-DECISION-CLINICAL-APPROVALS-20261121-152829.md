# GATE-DECISION-CLINICAL-APPROVALS — decision pack for clinical configuration approvals

## Summary

Added `docs/launch/clinical-approvals.md`: a decision pack that prepares the
material a person needs for TODO task `S-G5-02`. It covers approved clinical
templates and field dictionaries, diagnosis and procedure code sets, imaging
interpretation privileges, critical-result escalation rules and coverage rosters.
Each item is framed as needing a named clinical approver and effective dates.
The pack chooses no option, invents no jurisdiction, region, partner, name or
date, leaves the approval record empty, and marks status `Not approved`.
Documentation-only change; no production code, migration or threshold touched.

## Affected requirements

- ADM-01 — named approval, effective dates, audit and version history for
  clinical configuration (templates, catalogs, coverage rosters, critical-result
  escalation rules).
- CLN-04 — clinical governance approves field dictionaries and requiredness;
  each saved version keeps its historical field meanings.
- Referenced but not decided: RAD-01 (imaging privileges), XCH-05
  (critical-result escalation and human acknowledgment).

Source: section 9 (G5 row) and section 10 of `PROJECT_V1_SPEC.md`, plus the
ADM-01 and CLN-04 requirement text. Requirements ADM-01/CLN-04 come from story
`GATE-DECISION-CLINICAL-APPROVALS` in `TODO.md`.

## Risk class

Safety-critical (decision evidence for patient-safety-relevant clinical
configuration). No configuration value was set; no capability was enabled.
Human clinical and site sign-off remains a G5 gate and is explicitly not
satisfied by this document.

## Review owner

Clinical safety lead and site medical director (section 10 accountable roles),
recorded in the document's empty approval record. A bounded simulated clinical
review was requested from the `aurevia-physician-reviewer` specialist and
returned no actionable findings (see `## Clinical and architecture review`).
No human clinical or participant evidence was available or claimed.

## Data changes

None. No migration, backfill, lock/volume impact, rollback or forward-fix.
The document links to existing migrations (V5, V6, V15, V22, V23) as evidence
only; none was edited.

## Authorization boundaries and audit events

Unchanged. The document states that approval is data recorded by a named person
with effective dates and that the platform's controller, policy and audit
layers enforce authorization. No audit event was added or changed.

## Migration and rollout considerations

None in this change. The document records that durable approval evidence does
not yet exist for the in-memory template/dictionary and diagnosis/procedure
stores (`BUILD-26`, NO TASK items) and that a site must hold signed approvals
before enabling the related capability.

## Test evidence

Executed from the repository root on branch `swarm/GATE-DECISION-CLINICAL-APPROVALS-447`:

- `python3 -I scripts/check-docs.py`
  Result: exit code `0`, no diagnostics (clean Markdown links and requirement IDs).
- Structural self-check (tool evidence):
  `grep -n '^Status:' docs/launch/clinical-approvals.md` → `3:Status: Not approved`.
  `grep -n '^## ' docs/launch/clinical-approvals.md` → exactly the eight required
  headings, in order (lines 15, 42, 64, 78, 130, 156, 175, 192).
  `grep -c '^- \[ \]' docs/launch/clinical-approvals.md` → `12` unchecked evidence
  checkboxes.
  `sed -n '/^## Approval record/,$p' docs/launch/clinical-approvals.md` →
  `Clinical safety lead` and `Site medical director` rows with all Name, Date
  and Decision cells empty.
- No entry-point-to-test trace applies: this is a human decision document, not
  a runtime path. It was validated by the repository Markdown gate above.

Evidence source: executed tool commands. No CI run, simulator or human
participant evidence for the decision itself.

## Coverage

Not applicable. Documentation-only change: no production line, branch, method
or class was added or changed, so no coverage command applies and no threshold,
exclusion or skip was touched. `python3 -I scripts/check-docs.py` is the
applicable gate and is green (exit 0). Frontend and Java check scripts were not
run because no frontend or Java file changed.

## Clinical and architecture review

Simulated clinical review requested from `aurevia-physician-reviewer`
(specialist evidence, not human clinical sign-off): **no actionable findings**.
It independently verified all five approval categories, the eight exact
headings, the four-column options table, that every link resolves to an existing
file, that the task IDs in `## What is missing` exist in `TODO.md`, that no
jurisdiction/region/partner/name/date was invented, that status is exactly
`Not approved`, that all 12 checkboxes are unchecked and the approval cells are
empty. It also confirmed the pack keeps delivery acknowledgment distinct from
clinical/human receipt (XCH-05) and states a fail-closed posture for unapproved
or future-dated configuration. The document deliberately frames delivery
acknowledgment and clinical/human acknowledgment as separate facts and does not
assert that a delivered escalation equals clinical receipt.

## Acceptance mapping

| Story requirement | Where the pack addresses it |
|---|---|
| Approved templates and field dictionaries, each with a named approver and effective dates | `## Decision needed` item 1; `## Options`; `## Evidence checklist` |
| Diagnosis and procedure code sets, each with a named approver and effective dates | `## Decision needed` item 2; `## Options`; `## Evidence checklist` |
| Imaging interpretation privileges | `## Decision needed` item 3; `## Options`; `## Evidence checklist` |
| Critical-result escalation rules | `## Decision needed` item 4; `## Options`; `## Evidence checklist` |
| Coverage rosters | `## Decision needed` item 5; `## Options`; `## Evidence checklist` |
| Cite requirement IDs | `## Why it matters` (ADM-01, CLN-04; RAD-01, XCH-05 referenced) |
| Options table with option / commitment / risks / evidence | `## Options` (four columns, five option rows) |
| Links to files that exist | `## What the repository already supports` (all relative links resolve; proven by `check-docs.py` exit 0) |
| Task IDs or NO TASK | `## What is missing` (S-G5-02, S-G5-12, BUILD-26, BUILD-17, BUILD-14 and three NO TASK entries) |
| Questions for the owner | `## Questions for the owner` |
| Unchecked checkbox per artifact | `## Evidence checklist` (12 unchecked boxes) |
| Section 10 accountable roles, empty Name/Date/Decision | `## Approval record` |
| Choose no option, invent nothing, no filled approval record, "Not approved" | Status line and throughout; verified by structural self-check |

## Findings

- `docs/spec/clauses/` does not exist in this checkout. The story asked for
  clause notes under that path relating to this decision. They are produced by
  separate `SPEC-*` evidence-note tasks (for example `SPEC-DECISIONS`) and are
  not present. This was not a blocker: the authoritative clause text is in
  sections 9 and 10 of `PROJECT_V1_SPEC.md`, which were read in full, and
  the pack depends on no clause note. Recorded under `## What is missing`.
- The template/dictionary store and the versioned diagnosis/procedure catalogue
  are in memory in this checkout (`docs/decisions/0003-persistence-of-in-memory-stores.md`);
  an approved configuration in those stores would not survive a restart. The
  pack records this honestly under `## What is missing` instead of claiming
  durable support.
- Simulated physician review (`aurevia-physician-reviewer`) returned no
  actionable findings; no change was made as a result.
- No defect was found in the repository; no BUGS.md entry is warranted.

## Lessons for workers

- A decision pack decides nothing: present options and evidence, leave the
  approval record cells empty, and never supply a name, date, jurisdiction or
  partner even when the surrounding docs use placeholders.
- `scripts/check-docs.py` resolves every relative Markdown link against the
  file's own directory. From `docs/launch/`, repository-root files need `../../`,
  and `docs/operations/` files need `../operations/`. It validates links and
  requirement IDs, not headings, so verify the required headings separately.
- Section 10 accountable roles are the approval-record rows; G5 evidence is a
  human signature, so simulated review is evidence of review activity only.
