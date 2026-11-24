# RESP-APP-DECISION — fix pass notes

Task: Decision record: the patient app supports tablets and landscape
Story requirements: PAT-01, PAT-02, UX-01
Scope: `docs/decisions/0005-patient-app-tablets.md` (documentation only)
Fix pass on branch `swarm/RESP-APP-DECISION-447`, HEAD `63204179`.

## Summary

Continuation of the runs recorded in
`.agent/pr-notes/RESP-APP-DECISION-20261121-152813.md` and
`.agent/pr-notes/RESP-APP-DECISION-fix-20261121-170237.md`. The decision record
`docs/decisions/0005-patient-app-tablets.md` was already written and committed on
this branch by the first run; this pass did not need to change it. I re-read the
record against the TODO.md acceptance criteria and `docs/decisions/template.md`,
re-ran every gate applicable to a documentation-only change, and recorded the
mandatory patient-mobile typecheck/lint/coverage evidence below.

Verified acceptance mapping (each item is present in the committed record):

- Runs on iPhone, iPad, Android phones and Android tablets, in portrait and
  landscape — Decision, "Devices and orientation".
- Layout classes `compact`/`regular`/`large` from window width at the 600 dp and
  840 dp breakpoints, never from device type — Decision, "Layout classes come
  from the window, not the device".
- Two-pane inbox and thread on `large` widths, pushed single pane otherwise, with
  the same list-plus-detail treatment for other screens — Decision, "Two-pane on
  large widths".
- System font scaling honoured to 200%, no clipping, truncation or capped
  clinical values — Decision, "Text scales to 200%".
- iPad split-screen and Slide Over keep working — Decision, "iPad multitasking
  keeps working".
- UX-01 screen-reader flows (VoiceOver/TalkBack) tested on phone and tablet, both
  orientations, English and Arabic RTL, unchanged roles/labels/focus order —
  Decision, "Screen-reader flows on phone and tablet (UX-01)".
- Boundaries unchanged: no persistent clinical cache (PAT-02), no API change, no
  weakening of server-side authorization — Decision, "Boundaries unchanged".
- Frontmatter: Status `proposed`, Date `2026-11-21`, Requirement IDs
  `PAT-01, PAT-02, UX-01`, Owner `Product`. House style
  Context / Decision / Alternatives considered / Consequences and evidence
  matches `docs/decisions/template.md`.

This is a documentation-only story. No production code, configuration, API,
schema, dependency, migration or test was added or changed in this pass.

## Affected requirements

- Primary (task): PAT-01, PAT-02, UX-01 — defined in `PROJECT_V1_SPEC.md`
  (lines 85, 86 and 90).
- Referenced in the record and validated by `check-docs.py`: WEB-07 (line 155),
  MSG-02 (line 99), the section 7 "Web and mobile" row (line 110) and the
  accessibility contract (UX-01). All IDs exist in the specification.

## Risk class

Routine — documentation only (docs/WORKFLOW.md). No code, configuration, API,
schema, dependency or delivery change.

## Review owner

Product (record owner per the story). The first run recorded a simulated
`orevia-senior-mobile` and `orevia-ux-reviewer` review with no missing
decision; this pass re-checked the record directly against the TODO.md acceptance
criteria (tool/file-read evidence, not a new independent reviewer). No human,
clinical or patient reviewer evidence.

## Data changes

none — no table, migration, backfill, lock or volume impact; nothing to roll back.

## Authorization boundaries and audit events

none added. The record explicitly preserves existing boundaries: responsive
layout adds no persistent clinical cache (PAT-02), changes no API call, and keeps
server-side authorization as the control (section 7 mobile row). No audit event
is introduced.

## Migration and rollout considerations

none — documentation record only. It remains the prerequisite for the follow-on
stories RESP-APP-LAYOUT-HOOK, RESP-APP-CONFIG, RESP-APP-PATIENT-RESOURCE,
RESP-APP-ROOT-NAVIGATOR and downstream screen stories. `docs/decisions/README.md`
holds no numbered index, so no index entry was required.

## Test evidence

Commands run from the repository root unless stated, with their final lines:

- `python3 -I scripts/check-docs.py` → `check-docs exit=0`, no output. Canonical
  gate for a Markdown edit (TODO.md "Instructions for every agent" step 6 and the
  story's step 3): all Markdown links resolve and every requirement ID used
  outside the spec is defined.
- `python3 -I scripts/check-secrets.py` → `check-secrets exit=0`, no output: no
  credential or PHI-shaped string introduced.
- `cd apps/patient-mobile && timeout 270 bash check.sh` → `PATIENT_CHECK_EXIT=0`.
  Runs shellcheck, pinned `npm ci`, typecheck, lint and Jest with enforced
  coverage. Final lines:
  ```
  Test Suites: 48 passed, 48 total
  Tests:       518 passed, 518 total
  Snapshots:   0 total
  Time:        10.511 s
  Ran all test suites.
  ```
  Coverage table reports `All files | 100 | 100 | 100 | 100`; the three 0% rows
  are type-only `types.ts` declaration files with no executable statements.
- Explicit typecheck final lines, as the task prompt requires:
  `cd apps/patient-mobile && npm run typecheck` →
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit

  TYPECHECK_EXIT=0
  ```
  No `error TS…` was produced.

Entry-point-to-test trace: not applicable to the changed file — the record adds
no executable entry point. Its authoritative verification is read-back against
`docs/decisions/template.md`, `apps/patient-mobile/app.json`, the section 7 Web
and mobile row and the requirement IDs of `PROJECT_V1_SPEC.md`, plus the
passing `check-docs.py` link/ID gate. The patient-mobile gate was run as the
task prompt's mandatory frontend definition of done; no patient-mobile file was
changed by this story.

Deliberate non-execution (recorded, not a failure): `scripts/check.sh` was not
run. It shells out to `apps/api/check.sh` (`mvn verify` — the full Java,
database and integration gate, scripts/check.sh lines 25-27) and `db/check.sh`
(line 35-41), both explicitly reserved for the integrator/Delivery, and the task
prompt forbids a full `mvn verify` or any command that runs every integration
test. In addition its shellcheck pass currently fails on an out-of-scope
protected script (see Findings). The applicable story-scoped gate is
`python3 -I scripts/check-docs.py`, which passed.

## Coverage

Documentation-only change: no production source file, method or branch was added
or changed, so no line/branch/method/class coverage measurement applies to this
story and no new branch needs a test. No threshold was lowered, no exclusion
added, and no test was skipped or disabled. This matches the precedent in
`docs/decisions/0003-persistence-of-in-memory-stores.md`. As the mandatory
frontend check, `apps/patient-mobile/check.sh` reported 100% statements,
branches, functions and lines on all executable source files (48 suites / 518
tests green); that result covers unchanged code and is not presented as coverage
of this story. No Java or `scripts/*.py` file was touched, so no JaCoCo or
`scripts/python-coverage.sh` run is applicable.

## Clinical and architecture review

Not required: a documentation record does not change signed data,
cross-organization sharing, patient association, prescribing or critical results.
Simulated UX and senior-mobile review (first run) plus this direct acceptance
re-check only; no clinical or real-patient acceptance is implied. Real-patient
approval remains a G5 gate.

## Findings

- The story work was already complete on this branch when this pass began.
  `git diff --name-status origin/main...HEAD` shows only three added files: the
  decision record and the two earlier notes files. `git status --short` is empty;
  the decision file is tracked. No document or code edit was required, so this
  pass adds only this notes file and re-verification evidence.
- The first finding (issue #363) was a Maven resolution error inside the
  forbidden full gate: `org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6
  (absent) ... not found ... This failure was cached in the local repository`.
  It is a stale-artifact/cache condition in `apps/api/check.sh` (`mvn verify`),
  unrelated to the decision record and outside this story's scope; re-running it
  would add no evidence for a documentation change and is forbidden by the task
  prompt.
- The second finding (run 44927742630) was reproduced: `shellcheck
  scripts/agent-run.sh` exits 1 with
  `SC2015 (info): Note that A && B || C is not if-then-else. C may run when A is
  true.` at line 51,
  `[[ -f scripts/taskstore.py ]] && python3 -I scripts/taskstore.py view || true`.
  This file is unchanged by this story (`git diff origin/main...HEAD -- scripts/agent-run.sh`
  is empty) and the same warning is present on `origin/main`, so it is a
  pre-existing defect in a maintainer-owned protected script, not caused by this
  story. Owner: maintainer/DevSecOps. Recommended fix (not applied — out of
  scope and protected): rewrite line 51 as an explicit `if [[ -f ... ]]; then
  python3 -I scripts/taskstore.py view; fi`. No task in `TODO.md` covers this
  (`grep` for SC2015/agent-run.sh/shellcheck found none), and the task-request
  mechanism cannot request a scope that is a script file, so it is recorded here
  for the maintainer rather than as a `task-requests` block.
- `apps/patient-mobile/app.json` still has `orientation: portrait` and
  `ios.supportsTablet: false`. Enabling tablets/rotation is the separate
  RESP-APP-CONFIG story; no configuration was changed here (out of scope).
- No defects in the decision record and no decision or evidence missing, so the
  story itself was not stopped.

## Lessons for workers

- A documentation-only story's canonical gate is `python3 -I scripts/check-docs.py`
  (plus `check-secrets.py`). Do not run `scripts/check.sh` from a worker: it chains
  `apps/api/check.sh` (`mvn verify`) and `db/check.sh`, which are integrator-owned
  full gates, and its shellcheck pass currently fails on an out-of-scope
  protected script.
- The mandatory frontend definition of done for the patient app is
  `bash apps/patient-mobile/check.sh` and an explicit `npm run typecheck`; paste
  the typecheck final lines into the notes file even when the story changed no
  frontend file. `error TS…` means unfinished; there were none here.
- When a fix-pass branch already contains complete story work, verify it against
  the TODO.md acceptance items and record the evidence rather than rewriting the
  file; one new notes file per run keeps parallel workers from colliding.
- Distinguish out-of-scope pipeline failures from story defects with evidence:
  confirm the failing file is unchanged by the story and present on `origin/main`
  before declaring it pre-existing, and name the protected owner instead of
  editing the file.
- The decision-record house style (records 0002, 0003) uses frontmatter
  Status / Date / Requirement IDs / Owner and the template headings
  Context / Decision / Alternatives considered / Consequences and evidence.
