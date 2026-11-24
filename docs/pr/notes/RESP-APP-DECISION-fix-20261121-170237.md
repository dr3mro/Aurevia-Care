# RESP-APP-DECISION — fix pass notes

Task: Decision record: the patient app supports tablets and landscape
Story requirements: PAT-01, PAT-02, UX-01
Scope: `docs/decisions/0005-patient-app-tablets.md` (documentation only)
Fix pass on branch `swarm/RESP-APP-DECISION-447`.

## Summary

This is a continuation of the earlier run recorded in
`.agent/pr-notes/RESP-APP-DECISION-20261121-152813.md`. The earlier worker had
already written the decision record `docs/decisions/0005-patient-app-tablets.md`
(Status **proposed**, Owner **Product**) and committed it on this branch. That
record already satisfies every element of the story's acceptance criteria, so no
document or code change was needed in this fix pass; the work was re-verified
against the acceptance matrix and the applicable gates were re-run and captured
here.

Verified acceptance mapping (each item present in the record):
- Runs on iPhone, iPad, Android phones and Android tablets, portrait and landscape
  (Decision, "Devices and orientation").
- Layout classes compact / regular / large from window width at 600 dp and 840 dp,
  never from device type (Decision, "Layout classes come from the window").
- Two-pane inbox and thread on large widths; one-pane pushed thread otherwise, with
  the same treatment for other list-plus-detail screens (Decision, "Two-pane on
  large widths").
- Text scales with the system font up to 200% with no clipping or truncation
  (Decision, "Text scales to 200%").
- iPad split-screen and Slide Over keep working (Decision, "iPad multitasking").
- Screen-reader flows (UX-01) exercised on phone and tablet, both orientations,
  English and Arabic RTL, with unchanged roles/labels/focus order
  (Decision, "Screen-reader flows").
- Boundaries unchanged: no persistent clinical cache (PAT-02), no API or
  authorization change (Decision, "Boundaries unchanged").
- Status Proposed, Owner Product, Requirement IDs PAT-01/PAT-02/UX-01 (frontmatter).

## Affected requirements

- Primary (task): PAT-01, PAT-02, UX-01 — all defined in `PROJECT_V1_SPEC.md`
  (lines 85, 86 and 90 respectively).
- Referenced in the record and validated by `check-docs.py`: WEB-07 (line 155),
  MSG-02 (line 99), plus the section 7 "Web and mobile" row and the accessibility
  contract.

## Risk class

Routine — documentation only (docs/WORKFLOW.md). No code, configuration, API,
schema, dependency or delivery change.

## Review owner

Product (record owner per the story). The earlier run recorded a simulated
`aurevia-senior-mobile` and `aurevia-ux-reviewer` review of the record that
found no missing decision; this fix pass re-checked the record directly against
the TODO.md acceptance criteria (tool/file-read evidence, not a new independent
reviewer). No human, clinical or patient reviewer evidence.

## Data changes

none — no table, migration, backfill, lock or volume impact; nothing to roll back.

## Authorization boundaries and audit events

none added. The record explicitly preserves existing boundaries: responsive layout
adds no persistent clinical cache (PAT-02), changes no API call, and leaves
server-side authorization as the control (section 7 mobile row). No audit event is
introduced.

## Migration and rollout considerations

none — documentation record only. It is the prerequisite for the follow-on stories
RESP-APP-CONFIG, RESP-APP-LAYOUT-HOOK, RESP-APP-PATIENT-RESOURCE,
RESP-APP-ROOT-NAVIGATOR and RESP-APP-INBOX (names per the orchestrator's TODO.md
entries). `docs/decisions/README.md` does not maintain a numbered index, so no
index entry was required.

## Test evidence

Commands run from the repository root unless stated, with their final lines:

- `python3 -I scripts/check-docs.py` → exit 0, no output (all Markdown links
  resolve; every requirement ID used outside the spec is defined). Canonical gate
  for a Markdown edit per TODO.md "Instructions for every agent" step 6 and the
  story's step 3.
- `python3 -I scripts/check-secrets.py` → exit 0, no output (no credential or
  PHI-shaped string introduced).
- `bash apps/patient-mobile/check.sh` (run as `cd apps/patient-mobile && timeout 270 bash check.sh`) → exit 0.
  This runs shellcheck, pinned `npm ci`, typecheck, lint and Jest with enforced
  coverage. Final lines:
  `Test Suites: 48 passed, 48 total` / `Tests: 518 passed, 518 total` /
  `Time: 13.825 s` / `Ran all test suites.` Every production source file reports
  100% statements/branches/functions/lines (the 0% rows are type-only `types.ts`
  declaration files with no executable statements).
- Explicit typecheck final lines, as requested in the task prompt:
  `cd apps/patient-mobile && npm run typecheck` →
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit

  typecheck exit=0
  ```
  No `error TS…` was produced.

Entry-point-to-test trace: not applicable to the changed file — the change adds
only a Markdown decision record with no executable entry point. The authoritative
verification for the record's content is read-back against
`docs/decisions/template.md`, `apps/patient-mobile/app.json`, the section 7 mobile
row and the requirement IDs of `PROJECT_V1_SPEC.md`, plus the passing
`check-docs.py` link/ID gate. The patient-mobile gate was run as an additional
whole-repository health check at the task prompt's request; no patient-mobile file
was changed by this story.

Deliberate non-execution (recorded, not a failure): `scripts/check.sh` was not run.
It chains `apps/api/check.sh`, which is the full `mvn verify` Java/database/
integration gate (scripts/check.sh lines 25-27) that the task prompt explicitly
forbids workers from running. The previous run's check log ended in exactly that
gate failing on a stale Maven artifact resolution
(`org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6 ... not found ...
This failure was cached in the local repository`) — an infrastructure/cache issue
in the forbidden full gate, unrelated to this documentation-only change. The
applicable, story-scoped gate is `python3 -I scripts/check-docs.py`, which passed.

## Coverage

Documentation-only change: no production source file, method or branch was added
or changed, so no line/branch/method/class coverage measurement applies to this
story and no new branch needs a test. No threshold was lowered, no exclusion
added, and no test was skipped or disabled. This matches the precedent in
`docs/decisions/0003-persistence-of-in-memory-stores.md`, which records the same
determination for a documentation-only change. As an extra health check, the
patient-mobile canonical gate reported 100% coverage on every executable source
file (48 suites / 518 tests green); that result is for unchanged code and is not
presented as coverage of this story.

## Clinical and architecture review

Not required: a documentation record does not change signed data,
cross-organization sharing, patient association, prescribing or critical results.
Simulated UX and senior-mobile review (earlier run) plus this direct acceptance
re-check only; no clinical or real-patient acceptance is implied. Real-patient
approval remains a G5 gate.

## Findings

- The story work was already complete on this branch when this fix pass began:
  `git diff --name-status origin/main...HEAD` showed only the two added files
  (the decision record and the earlier run's notes file). No document or code
  edit was therefore required; re-verification and this hand-off are the deliverable.
- The earlier failure (issue #363 / check log) was a Maven surefire artifact
  resolution error inside `apps/api/check.sh` (`mvn verify`), a cached resolution
  failure in the forbidden full gate — not a defect in the decision record and not
  caused by any file in this story's scope. Re-running that command would not add
  evidence for a documentation-only change and was skipped for that reason.
- `apps/patient-mobile/app.json` still has `orientation: portrait` and
  `ios.supportsTablet: false`. Enabling tablets/rotation is the separate
  RESP-APP-CONFIG story; no configuration was changed here (out of scope).
- No defects found and no decision or evidence missing, so work was not stopped.

## Lessons for workers

- A documentation-only story's canonical gate is `python3 -I scripts/check-docs.py`
  (plus `check-secrets.py`). Do not run `scripts/check.sh` from a worker: it chains
  `apps/api/check.sh` (`mvn verify`, the full integration gate) and is strictly an
  integrator/Delivery action — and a stale Maven cache there can fail for reasons
  unrelated to the doc change.
- When a fix-pass branch already contains the complete story work, verify it
  against the TODO.md acceptance items and record the evidence rather than
  rewriting the file; one notes file per run keeps parallel workers from colliding.
- `check-docs.py` validates every requirement ID mentioned in Markdown outside the
  spec against the IDs in `PROJECT_V1_SPEC.md`; only use defined IDs and verify
  every relative link target exists before claiming the gate passes.
- The decision-record house style (see records 0002 and 0003) uses frontmatter
  Status / Date / Requirement IDs / Owner and the template headings Context /
  Decision / Alternatives considered / Consequences and evidence; follow it rather
  than inventing a new shape.
