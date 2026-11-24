# RESP-DECISION — fix pass notes

Task: Decision record: the staff web works on phones and tablets, not only 768 px and wider
Story requirements: WEB-07, UX-01
Scope: `docs/decisions/0004-responsive-staff-web.md` (documentation only)
Fix pass on branch `swarm/RESP-DECISION-447`.

## Summary

This is a continuation of the earlier run recorded in
`.agent/pr-notes/RESP-DECISION-20261121-152816.md` (commit `05196ed6`). That run had already
written the decision record `docs/decisions/0004-responsive-staff-web.md` (Status **Proposed**,
Owner **Product**) and committed it on this branch. Re-reading the record against the story's
acceptance criteria and the authoritative sources confirmed that it already satisfies every
element, so **no document or code edit was needed in this fix pass**; the deliverable is this
re-verification and the captured gate evidence. Nothing was pushed, tagged or sent to `gh`.

Verified acceptance mapping (each item present in the record):

- Phones 320–767 px, tablets 768–1279 px in both orientations, desktop 1280 px+ (Decision, width bands).
- Breakpoints compact 0 / medium 768 / expanded 1280, `min-width`, layout-only and not an authorization change (Decision, breakpoints paragraph).
- Navigation: off-canvas drawer on phones, icon rail on tablets, full labelled rail on desktop, mirrored under RTL (Decision, Navigation bullet).
- Tables on phones: labelled scroll region (`tabindex="0"`, `role="region"`) preserving `<table>`/`<caption>`/`<th scope>` as the default; labelled cards only if they keep those semantics and the full column set; no clinical column hidden with `display: none` (Decision, Tables bullet).
- Touch targets ≥ 44 × 44 CSS px for coarse pointers, superseding the 24 × 24 `size.target.min` web floor (Decision, Touch targets bullet).
- Text at 200% zoom and 320 px reflow with no two-dimensional scrolling except the table region (WCAG 1.4.10) (Decision, Text and zoom bullet).
- No offline clinical editing and no service-worker cache of clinical data; static assets only, authenticated responses always `no-store` (PRD-06, PAT-02 spirit, WEB-02) (Decision, Offline and caching bullet).
- Consequences: WEB-07 still states the 1280/768 floors and the sub-768 notice; a person must amend the spec (the swarm may not); until then code follows the 320 px floor (Consequences and evidence).
- Browser matrix: phones = latest two majors of iOS Safari and Chrome on Android; Samsung Internet out of scope; desktop/tablet unchanged from WEB-07 (Decision, browser matrix table).
- Status Proposed, Owner Product, Requirement IDs WEB-07 and UX-01 (frontmatter).

## Affected requirements

- Primary (task): WEB-07 (`PROJECT_V1_SPEC.md:155`) and UX-01 (`:90`) — both defined in the specification.
- Referenced in the record and validated by `check-docs.py`: PRD-06, PAT-02, WEB-02, WEB-10, IAM-01, IAM-02 — all defined in `PROJECT_V1_SPEC.md`.
- Authoritative source cross-check performed in this pass (tool reads): WEB-07 at `:155` confirms the 1280/768 floors and plain unsupported-browser message; `docs/design/screens-and-navigation.md:21` confirms the sub-768 "use a larger screen" notice with no data and `:16` confirms the 768–1279 icon rail; `docs/design/accessibility-patterns.md:27` (1.4.10) and `:111` confirm 320 px reflow, the labelled scroll region and the `display: none` prohibition; `docs/design/token-contract.md:492` confirms `size.target.min` 24 × 24 px and `size.target.comfortable` 44 × 44 px. The record is accurate against all of them.

## Risk class

Routine — documentation only (`docs/WORKFLOW.md`). No code, configuration, API, schema, dependency or delivery change. Implementing the decision later is cross-boundary (staff-web shell, navigation, tables, browser-support guard, design contract, end-to-end matrix) and needs its own story and reviews.

## Review owner

Product (record owner per the story; status Proposed). The earlier run recorded a simulated `orevia-ux-reviewer` review that found no blocking defect. This pass re-checked the record directly against the TODO/task acceptance items (tool/file-read evidence, not a new independent reviewer). No human, clinical or patient evidence.

## Data changes

none — no table, migration, backfill, lock or volume impact; nothing to roll back. Synthetic data only; no patient data or credentials in the record.

## Authorization boundaries and audit events

none added. The record explicitly preserves existing boundaries: breakpoints are layout-only and do not change what the server authorizes (IAM-01/IAM-02). No audit event is introduced.

## Migration and rollout considerations

none — documentation record only. It is the prerequisite for the later cross-boundary implementation story. `docs/decisions/README.md` keeps no numbered index, so no index entry was required.

## Test evidence

Commands run from the repository root unless stated, with their final lines:

- `python3 -I scripts/check-docs.py` → `check-docs EXIT=0`, no findings printed (all relative Markdown links resolve; every requirement ID used outside the spec is defined). This is the canonical gate for a Markdown edit per the story's step 4 and TODO "Instructions for every agent" step 6.
- `python3 -I scripts/check-secrets.py` → `check-secrets EXIT=0`, no credential or PHI-shaped findings.
- `cd apps/staff-web && timeout 280 bash check.sh` → `staff-web check EXIT=0`. This is the mandated staff-web definition-of-done gate. Final lines:
  `Test Files  66 passed (66)` / `Tests  840 passed (840)` / `No files with missing coverage.` / `102 files fully covered.` followed by the coverage summary (Statements 100% 2041/2041, Branches 100% 1090/1090, Functions 100% 671/671, Lines 100% 1954/1954). The `BoomFailure: MARKER-THROW-MESSAGE`, `MARKER-NON-ERROR-VALUE`, jsdom navigation and provider-required console stack traces are expected output from tests that deliberately throw/cover error boundaries and provider guards; every test passed and the gate exited 0.
- Explicit typecheck final lines, as requested in the task prompt:
  `cd apps/staff-web && npm run typecheck` →
  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json

  typecheck EXIT=0
  ```
  No `error TS…` was produced.

Entry-point-to-test trace: not applicable to the changed file — the change adds only a Markdown decision record with no executable entry point. The authoritative verification for the record's content is the read-back against `docs/decisions/template.md`, WEB-07, the design/accessibility/token docs and the requirement IDs of `PROJECT_V1_SPEC.md`, plus the passing `check-docs.py` link/ID gate. The staff-web gate was run as the prompt's mandatory whole-repository health check and to capture typecheck evidence; no staff-web file was changed by this story.

## Coverage

Documentation-only change: no production source file, method or branch was added or changed, so no line/branch/method/class coverage measurement applies to this story and no new branch needs a test. No threshold was lowered, no exclusion added, and no test was skipped or disabled. This matches the precedent in `docs/decisions/0003-persistence-of-in-memory-stores.md`, which records the same determination for a documentation-only change. As an extra health check the staff-web canonical gate reported 100% coverage on every executable source file (66 files / 840 tests green); that result is for unchanged code and is not presented as coverage of this story.

## Clinical and architecture review

Not required: a documentation record does not change signed data, cross-organization sharing, patient association, prescribing or critical results. Simulated UX review (earlier run) plus this direct acceptance re-check only; no clinical or real-patient acceptance is implied. Real-patient approval remains a G5 gate.

## Findings

- The story work was already complete on this branch when this fix pass began: `git diff --name-status origin/main...HEAD` showed only the two added files (the decision record and the earlier run's notes file). No document or code edit was therefore required; re-verification and this hand-off are the deliverable.
- Deliberate non-execution (recorded, not a failure): `scripts/check.sh` was not run. It chains `apps/api/check.sh`, the full `mvn verify` Java/database/integration gate, which the task prompt explicitly forbids workers from running (it takes over ten minutes and ends the run as a timeout). The applicable, story-scoped gate is `python3 -I scripts/check-docs.py`, which passed.
- `bash scripts/quick-check.sh` exits 1 on a pre-existing shellcheck **SC2015** info in protected `scripts/agent-run.sh:51` (`[[ -f ... ]] && python3 ... || true`). The same line is present on `origin/main` (`git show origin/main:scripts/agent-run.sh`), so this is unrelated to this documentation-only change and outside the story's scope; it was not edited. The worker/integrator gate judges pre-existing failures separately.
- Spec drift (hand-off, not fixed here): `PROJECT_V1_SPEC.md` WEB-07 (`:155`) and `docs/design/screens-and-navigation.md:21` still state the 768 px floor and the sub-768 "use a larger screen" notice with no data. Per the story and AGENTS.md the swarm may not edit the specification; a person must amend WEB-07 and the design doc. Until then the compact layout floor in code follows this decision (down to 320 px; width alone must not trigger the unsupported-browser page). The concrete code point a later implementation story must change is `isSupportedBrowser(userAgent, width)` in `apps/staff-web/src/features/system/browserSupport.ts` (READY-32), which currently returns false below 768 px.
- No defects found in the record and no decision or evidence missing, so work was not stopped.

## Lessons for workers

- A documentation-only story's canonical gate is `python3 -I scripts/check-docs.py` (plus `check-secrets.py`). Do not run `scripts/check.sh` from a worker: it chains `apps/api/check.sh` (`mvn verify`, the full integration gate) that the prompt forbids. `scripts/quick-check.sh` may fail on pre-existing protected-script shellcheck findings — check whether the same line exists on `origin/main` before treating it as caused by your change.
- When a fix-pass branch already contains the complete story work, verify it against the acceptance items and record the evidence rather than rewriting the file; one notes file per run keeps parallel workers from colliding.
- `check-docs.py` validates every requirement ID mentioned in Markdown outside the spec against the IDs in `PROJECT_V1_SPEC.md`; only use defined IDs and verify every relative link target exists before claiming the gate passes.
- The decision-record house style (records 0001–0003) uses frontmatter Status / Date / Requirement IDs / Owner and the template headings Context / Decision / Alternatives considered / Consequences and evidence; follow it rather than inventing a new shape.
- For the mandated frontend definition of done on a docs-only story, run `bash apps/staff-web/check.sh` and paste the explicit `npm run typecheck` final lines; the console stack traces from deliberate throw/guard tests do not indicate a failure — the test summary and exit code do.
