# RESP-APP-DECISION — notes

Task: Decision record: the patient app supports tablets and landscape
Story requirements: PAT-01, PAT-02, UX-01
Scope: `docs/decisions/0005-patient-app-tablets.md` (documentation only)

## Summary

Added `docs/decisions/0005-patient-app-tablets.md` (Status **proposed**, Owner
**Product**), copied from `docs/decisions/template.md`. It records that the
patient app supports iPhone, iPad, Android phones and Android tablets in portrait
and landscape; that layout classes `compact`/`regular`/`large` are chosen by
window width at the 600 dp and 840 dp breakpoints rather than by device type;
that the inbox and thread become two panes on `large` windows; that text scales
with the system font up to 200% without clipping; that iPad split-screen and
Slide Over keep working; and that UX-01 screen-reader flows are tested on phone
and tablet. It states that layout is presentational only: no persistent clinical
cache (PAT-02), no change to API calls or server authorization (section 7 mobile
row). Alternatives (portrait/phone-only status quo, device-type layout, fixed
fonts, forced full-screen, two-panes-everywhere) are rejected with reasons.

## Affected requirements

- Primary (task): PAT-01, PAT-02, UX-01.
- Referenced in the record (all defined in `PROJECT_V1_SPEC.md`): WEB-07
  (supported client versions), MSG-02 (patient inbox and thread), plus the
  section 7 "Web and mobile" row and the accessibility contract.

## Risk class

Routine — documentation only. No code, configuration, API, schema or delivery
change (docs/WORKFLOW.md).

## Review owner

Product (record owner per the story). Simulated review using the
`orevia-senior-mobile` and `orevia-ux-reviewer` perspectives found no
missing decision. No human, clinical or patient reviewer evidence.

## Data changes

none — no table, migration, backfill, lock or volume impact; nothing to roll back.

## Authorization boundaries and audit events

none added. The record explicitly preserves existing boundaries: responsive
layout must not add a persistent clinical cache (PAT-02), must not change API
calls, and must keep server-side authorization as the control (section 7 mobile
row). No audit event is introduced.

## Migration and rollout considerations

none — documentation record only. It is the prerequisite for the follow-on
stories RESP-APP-CONFIG, RESP-APP-LAYOUT-HOOK, RESP-APP-PATIENT-RESOURCE,
RESP-APP-ROOT-NAVIGATOR and RESP-APP-INBOX (the latter names depend on the
orchestrator's TODO entries).

## Test evidence

Commands run from the repository root, with their final lines:

- `python3 -I scripts/check-docs.py` → exit 0, no output (all Markdown links
  resolve and every requirement ID mentioned outside the spec is defined). This
  is the canonical gate for a Markdown edit per TODO.md "Instructions for every
  agent" step 6 and the story's step 3.
- `python3 -I scripts/check-secrets.py` → exit 0, no output (no credential or
  PHI-shaped string introduced).

Entry-point-to-test trace: not applicable — this change adds only a Markdown
decision record with no executable entry point. The authoritative verification
for the record's content is read-back against
`docs/decisions/template.md`, `apps/patient-mobile/app.json`, the section 7
mobile row and UX-01 of `PROJECT_V1_SPEC.md`, plus the passing
`check-docs.py` link/ID gate.

Deliberate non-execution (recorded, not a failure): `scripts/check.sh` was not
run. It chains `apps/api/check.sh`, which is `mvn -q -B verify` — the full Java,
database and integration gate that the task prompt explicitly forbids workers
from running. For a documentation-only change the applicable check is
`python3 -I scripts/check-docs.py`, which passed. `bash apps/patient-mobile/check.sh`
is likewise not applicable: no file under `apps/patient-mobile` was changed
(this story's scope is a single file under `docs/decisions/`), so there is no
frontend typecheck, Jest or coverage surface to prove here.

## Coverage

Documentation-only change: no production source file, method or branch was
added or changed, so no line/branch/method/class coverage measurement applies
and no coverage command is run. No threshold was lowered, no exclusion added,
and no test was skipped or disabled. This matches the precedent in
`docs/decisions/0003-persistence-of-in-memory-stores.md`, which records the same
determination for a documentation-only change.

## Clinical and architecture review

Not required: a documentation record does not change signed data,
cross-organization sharing, patient association, prescribing or critical
results. Simulated UX and senior-mobile review only; no clinical or real-patient
acceptance is implied. Real-patient approval remains a G5 gate.

## Findings

- Confirmed from `apps/patient-mobile/app.json`: `orientation` is `portrait` and
  `ios.supportsTablet` is `false`. The record is the decision prerequisite for a
  separate follow-up story to change that configuration; no configuration was
  changed here (out of scope).
- No defects found. No decision or evidence was missing, so work was not stopped.
- `scripts/check.sh` would trigger the forbidden `mvn -q -B verify`; see Test
  evidence for why the doc-specific gate was used instead.

## Lessons for workers

- For a documentation-only story, the canonical gate is
  `python3 -I scripts/check-docs.py` (plus `check-secrets.py`); do not run
  `scripts/check.sh`, which chains `apps/api/check.sh` (`mvn verify`, the full
  integration gate) and the frontend checks — that is strictly an
  integrator/Delivery action for a worker.
- `scripts/check-docs.py` validates every requirement ID mentioned in Markdown
  outside the spec against the IDs defined in `PROJECT_V1_SPEC.md`. Use only
  defined IDs (PAT-01, PAT-02, UX-01, WEB-07, MSG-02 are all defined) and verify
  every relative link target exists before claiming the gate passes.
- The decision-record house style (see records 0002 and 0003) uses a frontmatter
  block of `Status`, `Date`, `Requirement IDs` and the template headings
  Context / Decision / Alternatives considered / Consequences and evidence;
  follow it rather than inventing a new shape.
