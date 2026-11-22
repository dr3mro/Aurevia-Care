# RESP-DESIGN-PREVIEW — fix pass (RESP-DESIGN-PREVIEW-fix-20261122-090117)

## Summary

Fix pass on the already-committed responsive `DesignPreview` slice (implementation
commit `68556991` on `swarm/RESP-DESIGN-PREVIEW-859`). The earlier worker's
production change and new jsdom test were found complete and green: I re-ran the
targeted tests, the mandatory full staff-web gate, the explicit typecheck, lint,
format and naming checks, and confirmed the acceptance mapping and 100% coverage.
**No production defect was found and no production code was changed in this fix
pass.** This file is the hand-off at the path named in the execution request and
is the only file added by this pass. The earlier PR description captured by the
integrator at 09:00 was truncated mid-sentence ("measurement.ta"); the committed
notes file `.agent/pr-notes/RESP-DESIGN-PREVIEW-20261122-083943.md` is complete on
disk, which is the most likely reason the run was judged "not finished cleanly".

## Affected requirements

- **WEB-07** — supported clients; staff web compact layout and browser matrix.
- **UX-01** — English/Arabic, RTL, mixed-script clinical text and WCAG 2.2 AA layout.

No other requirement IDs touched. Risk class: **routine** (contained staff-web
presentation change to one component plus one new test file), with a simulated UX
review because RTL/WCAG is in scope (`docs/WORKFLOW.md`).

## Acceptance mapping

| Story step | Implementation (commit `68556991`) | Test evidence |
|---|---|---|
| 320/360/390/768/820/1024/1280 px → compact/medium/expanded | root `useBreakpoint()` → `data-breakpoint` | `viewport matrix` `it.each` (7 widths) |
| LTR and RTL at every band | document `dir`/`lang`; no subtree `direction` override | `responsive DesignPreview right-to-left at %s width` (3 bands) |
| No horizontal page scroll; columns stack; safe-area insets | existing `.page` class (single `minmax(0,1fr)` column, logical `max()` safe-area padding, 1200 px reading cap) | asserts `classList.contains('page')`, no physical inline styles |
| Long English/Arabic text wraps; 200 % zoom readable | existing `MixedScriptText` `<bdi dir="auto">` (overlaps the bare measurement run) + wrapping cells/buttons | asserts `bdi` dir/lang/`data-breakpoint`, `overflowWrap:anywhere`, no `nowrap`/fixed block size |
| Coarse pointers 44×44; inputs 16 px | existing `.touch` + `@media (pointer: coarse)`, `.field` 16 px | asserts `touch`/`field` classes and `data-coarse-pointer` per band (computed pixels are Playwright evidence) |
| Logical properties only; no hidden clinical column | no new CSS; `page` class + logical inline styles only | authored inline-style regex; full `<th scope="col">` set; no `.hide-compact`/`.only-compact` |
| Preview shows every component at three width classes | all children render at each band; each exposes its own band structure | compact labelled `role=region` vs plain table; form/input/dialog/`bdi` `data-breakpoint`; dialog `sheet` vs `centered` |
| Labels, roles, keyboard order unchanged | no text/API/export change | `getByRole`/`getByLabelText` assertions + focusable-order array per band |
| Existing tests still pass | minimal production diff | `tests/app/AppShell.test.tsx` + full suite green |

Entry-point-to-test trace: dev-only route `/design-preview` → lazy
`createRoutes(includeDesignPreview)` in `src/app/routes.tsx` → `DesignPreview`
→ `tests/app/AppShell.test.tsx` ("keeps the development preview interactive for
visual review": click/submit/echo) and the new
`tests/responsive/app/DesignPreview.responsive.test.tsx` (structure, roles, RTL,
keyboard order at all bands). Pixel, 44 px, 16 px, safe-area and 200 %-zoom
paint checks remain Playwright E2E evidence (separate RESP-E2E tasks), as the
story directs — no painted-layout claim is made here.

## Review owner

`smartclinic-senior-react` and `smartclinic-ux-reviewer` — earlier advisory
subagent reviews, simulated/synthetic evidence only; no human or clinical
participant. Senior React verdict: no blocking defects. UX verdict: no blocking
defect after the measurement-isolation fix. This fix pass re-verified by executed
tool commands; no new reviewer was required because no production code changed.

## Data changes

None. No Flyway migration, backfill, lock, volume impact, rollback or forward-fix.
No reserved migration version used. No synthetic identifiers beyond the existing
`fixtures/synthetic.ts` values.

## Authorization boundaries and audit events

None. `DesignPreview` is a development-only visual page: no endpoint, tenant,
role, patient link or audit event was added or changed.

## Migration and rollout considerations

None. No build script, contract, workflow or tracker file edited.

## Test evidence

Evidence source for every line below: **executed tool commands in this fix pass**
(no human, clinical or simulated-user evidence). Versions observed: Node v22.23.5,
npm 10.9.9, Vitest 5.1.1 (installed from the pinned lockfile via `npm ci`).

1. Environment setup (node_modules were absent in this fresh worker):
   `cd apps/staff-web && npm ci` — `added 486 packages, and audited 487 packages in 7s`, exit 0.
2. Targeted tests (new file + existing entry-point test):
   `cd apps/staff-web && npx vitest run tests/responsive/app/DesignPreview.responsive.test.tsx tests/app/AppShell.test.tsx --reporter=dot`
   → final lines: `Test Files  2 passed (2)` / `Tests  37 passed (37)`, exit 0.
3. Mandatory frontend gate:
   `bash apps/staff-web/check.sh` — exit 0; all of `gen:api:check`, `npm run typecheck`,
   `npm run lint`, `npm run build`, `node scripts/check-bundle-size.mjs`,
   `npm run test:coverage` passed. Final lines:
   `Test Files  159 passed (159)` / `Tests  1916 passed (1916)` / `No files with missing coverage.` /
   `207 files fully covered.` / `4 empty files skipped.` The React "useI18n must be used
   inside I18nProvider" / "useTheme must be used inside ThemeProvider" stack traces in the
   log are the expected output of the negative provider tests in
   `tests/i18n/I18nProvider.test.tsx` and `tests/theme/ThemeProvider.test.tsx`; the run is green.
4. Typecheck final lines (explicit re-run, required by the frontend definition of done):
   `cd apps/staff-web && npm run typecheck` →
   `> smartclinic-staff-web@0.1.0 typecheck` /
   `> tsc --noEmit -p tsconfig.json`, exit 0, **no `error TS…`**.
5. Lint: `cd apps/staff-web && npx eslint . --max-warnings=0` → exit 0, no output.
6. Formatting: `cd apps/staff-web && npx prettier --check src/app/DesignPreview.tsx tests/responsive/app/DesignPreview.responsive.test.tsx`
   → `Checking formatting...` / `All matched files use Prettier code style!`, exit 0.
7. Naming: `python3 -I scripts/check-names.py` → exit 0, no output.
8. Branch reconciliation: `git merge --no-edit origin/main` → `Already up to date.` (run at
   the start of this pass). Conflict-marker check in the story scope
   (`git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/staff-web/`) → no matches.
9. Repository check: `bash scripts/check.sh` → stops at
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`, exit 1. This is the
   documented cloud-worker artifact, not a change defect: `git ls-files -v opencode.json`
   → `S opencode.json`; `cmp` confirms the worktree file is byte-identical to
   `.github/opencode.ci.json` (CI profile) and differs from `git show HEAD:opencode.json`
   (local profile). The protected file was **not** edited or regenerated. Integrator/Delivery
   judge this gate at HEAD, where the profile is correct.

From the earlier worker's notes (tool evidence recorded there, not re-run in this pass):
the red phase before the production change was
`npx vitest run tests/responsive/app/DesignPreview.responsive.test.tsx` →
`Test Files 1 failed (1)`, `Tests 17 failed | 6 passed (23)`, all failures the missing
`data-breakpoint`/`page` root.

## Coverage

- Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (Vitest 5.1.1 / v8,
  `include: ['src/**/*.{ts,tsx}']`, `perFile: true`, 100 % thresholds).
- Result (`check.sh` exit 0):
  `No files with missing coverage.` / `207 files fully covered.` /
  `Statements 100% (3722/3722)` / `Branches 100% (1892/1892)` /
  `Functions 100% (1258/1258)` / `Lines 100% (3556/3556)`.
- Changed production file `src/app/DesignPreview.tsx`: 100 % statements, branches,
  functions and lines. No threshold, exclusion or skip was changed; no test disabled or
  ignored.
- New branches introduced by this fix pass: **none** (only this notes file was added).
- Branches introduced by the earlier implementation and their named tests (unchanged,
  re-verified here): the change adds one hook call, one `className`, one `data-*`
  attribute and one existing-primitive wrapper — no `if`, `?:`, `&&`, `||`, `switch` or
  `catch` in the changed code.
  - root `page` class + `data-breakpoint=band` → "renders the page with responsive
    classes and exposes the active band" (3 cases) + 7-width viewport matrix.
  - compact table region vs medium/expanded plain table → "shows every component with
    the table, fields and dialog for the band".
  - dialog `sheet`/`centered` and sticky header → "opens the dialog with the
    band-appropriate view and restores focus" (3 cases).
  - measurement `<bdi dir="auto">` under Arabic → RTL block (3 cases) with
    `measurement.tagName === 'BDI'`.
  - dialog-open, close and note-submit callbacks → `AppShell.test.tsx`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical results. The UX/WCAG-01 surface was reviewed by a simulated
subagent only — synthetic review, not clinical or human acceptance.

## Findings

1. **Fix-pass outcome:** the earlier worker's change is already complete, scoped and
   green. No production defect was found; no production file was changed in this pass.
   The likely reason for "did not finish cleanly" is that the integrator captured the
   earlier notes file mid-write (its PR-description copy ends at "measurement.ta"),
   while the committed file is complete.
2. **Environment artifact (tool evidence):** `bash scripts/check.sh` fails only at the
   stale-`opencode.json` pack check because cloud workers mount the CI profile under
   `skip-worktree` (`S`). Verified with `git ls-files -v` and `cmp` against both
   `.github/opencode.ci.json` and `git show HEAD:opencode.json`. Do not regenerate or
   commit that protected file; HEAD truth is correct.
3. **Pre-existing and out of scope (tool evidence):** committed conflict markers remain
   at HEAD in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
   `Hl7Parser.java` and `Hl7Segment.java`. Confirmed present at HEAD via
   `git grep ... HEAD -- apps/api/.../hl7/`; none in `apps/staff-web/`. Unrelated to this
   story and left untouched.
4. **Deliberate, documented decision (earlier UX reviewer, simulated):** the root keeps
   its pre-existing inline `style={{ display:'grid', gap: tokens.spacing.lg }}` alongside
   the new `page` class. It duplicates `.page`'s display/gap only, does not override
   safe-area padding or breakpoint rules, and preserves the theme subscription; removing
   it would be a larger-than-needed behaviour change. Kept as-is.
5. jsdom cannot paint pixels, so 44×44 / 16 px / safe-area / 200 %-zoom claims are
   asserted through classes, attributes and structure as the story directs; computed
   values remain Playwright evidence (separate RESP-E2E tasks).

## Lessons for workers

- A fix pass on a green, already-committed slice should first re-run the full module gate
  and read the earlier notes on disk: a truncated PR-description snapshot is not proof
  of unfinished production work. Record the verification honestly instead of inventing a
  change.
- In a cloud worker, a red `check-pack` / `scripts/check.sh` is almost always the CI
  `opencode.json` under `skip-worktree`; confirm with `git ls-files -v opencode.json` and
  `cmp` against `.github/opencode.ci.json` and `git show HEAD:opencode.json`, and never
  regenerate or commit that file (it would block the PR).
- `npm ci` is required in a fresh worker before `npx vitest` or `npm run typecheck`:
  without `node_modules`, `npx` silently installs a different Vitest and fails on an
  unresolved `vitest/config` import — an environment failure, not a test failure.
- Expected error-path tests (provider "must be used inside" assertions) print React/jsdom
  stack traces in a green run; judge the run by the final `Test Files` / `Tests` lines,
  not by trace text.
