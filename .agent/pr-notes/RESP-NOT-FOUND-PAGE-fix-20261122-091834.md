# RESP-NOT-FOUND-PAGE — fix pass 2 (staff web: responsive `NotFoundPage`, app catch-all)

## Summary

Continuation of fix attempt 2 of 3 for PR #720 / issue #721. I started from the
committed candidate on branch `swarm/RESP-NOT-FOUND-PAGE-861` (implementation
`1edb0f68`, first fix-pass hand-off `b1424b80`) rather than starting over. The
two scoped files were already changed by the original worker: `NotFoundPage`
uses the existing `page`/`stack` utilities, the shared `useBreakpoint()` hook
and logical inline styles, and its dedicated responsive test covers all three
bands, the seven required widths in `en` and `ar`, roles, labels, keyboard order
and RTL.

I re-ran every gate that this worker and the integrator own for the declared
scope at the current branch head. All pass, including 100% coverage, with no
`error TS…`. I found **no in-scope code defect**, so I changed no production or
test file; this pass adds only this hand-off note. Verdict: verified complete,
ready for the integrator to merge. The earlier "local checks: failed" report was
not reproducible here — the failure mode reported by the integrator is the
whole-repository Java integration/setup gate, outside this staff-web-only scope.

Evidence provenance: executed tool commands in this worker (module check,
explicit typecheck, coverage and frontend-typecheck gates, policy greps and Git
merge), two bounded **simulated** specialist reviews launched in this pass
(senior React and UX), and earlier workers' executed tool evidence with its
red-first run. No simulated-user or human-participant evidence is claimed.
Synthetic data only: locale codes `en`/`ar` and existing static shell/state
labels; no patient identifier, credential or real domain name appears in code
or notes.

## Affected requirements

- WEB-07 — supported clients and compact staff-web layout down to 320 px.
- UX-01 — English/Arabic, RTL, WCAG-2.2-AA presentation, flexible styling.

Acceptance mapping: band resolution and stacking -> `data-breakpoint`, `.page`
and `.stack` assertions; wrap and zoom readability -> `overflow-wrap: anywhere`
with no fixed block size or `nowrap`; RTL -> `dir=rtl`, `lang=ar`, isolated
Arabic heading text and no local direction override; keyboard -> no tab stops
and focus left on `document.body`; safe areas and reading cap -> the untouched
`.page` utility in `src/theme/responsive.css`; logical-only -> dash-case CSSOM
checks over the elements this component styles. Measured 44 px targets, 320 px
painted reflow and 200 percent zoom remain in the Playwright tasks because jsdom
cannot paint, as the task directs.

## Risk class

Routine — contained presentation change to one data-free component plus one new
test file (docs/WORKFLOW.md). No API, database, contract, authorization,
migration or clinical-policy surface was touched.

## Review owner

Senior React and Vite (`smartclinic-senior-react`) and UX reviewer
(`smartclinic-ux-reviewer`). Both run as bounded **simulated** reviews; earlier
workers' review findings were addressed in the original notes and no new
finding was raised in this pass. No human or real-clinical evidence is claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.
No reserved migration version was used.

## Authorization boundaries and audit events

None. Tenant, role and patient-link scope are unchanged; no audit event added or
changed. The catch-all page is data-free and fetches no endpoint.

## Migration and rollout considerations

None. CSS-class and logical-inline-style presentation only; no data, endpoint
or authorization change.

## Test evidence

Entry point to test trace: `src/app/routes.tsx` lazy-registers `NotFoundPage`
for the `*` catch-all; the rendered component reads `useI18n()` ->
`shellText(locale, 'notFound')` for the `h2` with id `not-found-title` and renders
`EmptyState` -> `StatePanel` with `role="status"` and `aria-live="polite"`. The
new test renders `NotFoundPage` directly under `ThemeProvider` and `I18nProvider`
with a stubbed `matchMedia` and asserts the DOM at each synthetic band.

Commands run in this pass, with final lines (all from the repository root unless
stated):

- `git merge --no-edit origin/main` -> `Already up to date.` (run at the start;
  working tree clean before and after).
- `bash apps/staff-web/check.sh` -> `EXIT=0`; `gen:api: generated client matches
  the contract`; typecheck `> tsc --noEmit -p tsconfig.json` (exit 0, no
  `error TS…`); lint `> eslint . --max-warnings 0` (exit 0); build
  `✓ built in 135ms`; every bundle-chunk budget `ok` (including
  `NotFoundPage-9qWJactF.js 370 ok` and `NotFoundPage-C8m0ifMn.js 205 ok`);
  `Test Files  159 passed (159)` / `Tests  1923 passed (1923)`.
- Explicit `npm run typecheck` in `apps/staff-web` -> final lines
  `> smartclinic-staff-web@0.1.0 typecheck` and
  `> tsc --noEmit -p tsconfig.json`, exit 0, no `error TS…`.
- `bash scripts/coverage-check.sh` -> final line `coverage check: passed`,
  `COV_EXIT=0`.
- `bash scripts/frontend-typecheck-check.sh` -> final line
  `frontend typecheck: passed (or no frontend files changed)`, `FTC_EXIT=0`.
- A repository-wide grep for committed conflict-marker lines at column one
  reports markers only in three pre-existing HL7 Java files on `origin/main`
  (see Findings); none under `apps/staff-web/`.

The `useTheme must be used inside ThemeProvider` stack traces in the full test
log come from the pre-existing negative-path test
`tests/theme/ThemeProvider.test.tsx`; they are not failures (159/159 files and
1923/1923 tests passed).

Earlier worker's red-first evidence (executed tool, from its notes): before the
component change
`./node_modules/.bin/vitest run tests/responsive/app/NotFoundPage.responsive.test.tsx --reporter=dot`
-> `Test Files  1 failed (1)` / `Tests  23 failed (23)`; after implementation the
same command -> `Test Files  1 passed (1)` / `Tests  32 passed (32)`.

`scripts/check.sh` was not run as a whole: it invokes `apps/api/check.sh` ->
`mvn -q -B verify`, which the worker prompt forbids (over ten minutes / timeout).
Its frontend and policy steps were run individually and are green above; the
integrator and Delivery own the full gate.

## Coverage

Commands: `bash apps/staff-web/check.sh` (its `npm run test:coverage` step) and
`bash scripts/coverage-check.sh` (staff-web branch: `npm run -s typecheck` then
`npm run -s test:coverage`, v8 with `perFile: true` and 100 percent thresholds).

Result for both: `No files with missing coverage.` / `207 files fully covered.` /
Statements `100% (3722/3722)`, Branches `100% (1892/1892)`, Functions
`100% (1258/1258)`, Lines `100% (3556/3556)`. The touched production file
`src/app/NotFoundPage.tsx` is 100 percent on all four metrics with no missed
line or branch; open its entry in the generated report to confirm.

New or changed branches in production code: none. The component adds no `if`,
ternary, `&&`, `||`, `switch` or `catch`; `data-breakpoint` is a value read from
the unchanged shared `useBreakpoint()` hook. Each value that hook can report is
nevertheless asserted through mocked media queries:

- `compact` -> the three `%s width` band tests at `compact`, plus Arabic widths
  320/360/390.
- `medium` -> the three band tests at `medium`, plus widths 768/820/1024.
- `expanded` -> the three band tests at `expanded`, plus width 1280.

The hook's own branch logic lives in untouched `src/theme/useBreakpoint.ts` and
`src/theme/breakpoints.ts`, already covered by `tests/theme/breakpoints.test.ts`
and exercised end to end here. No threshold, exclusion, `@ts-ignore`, `any`,
ignore comment or skipped test was added or changed; no test was weakened or
deleted.

## Clinical and architecture review

Not required: no clinical decision, signed data, cross-organization sharing,
patient association, prescribing or critical-result behaviour is involved. The
change is presentation only and touched no domain contract or state machine.

## Findings

- **Two bounded simulated specialist reviews — no finding.** Senior React and
  UX reviewers independently examined the two scoped files read-only at the
  current branch head. Both returned `No finding` and modified no file. The
  non-blocking caveats they raised (wrap and zoom are proven by CSS structure
  rather than a painted Playwright view; keyboard order is proven in component
  isolation; two `h2` elements with no `h1` and an inert `EmptyState` action are
  pre-existing and out of scope) require no change for this story.
- **No in-scope code defect.** Every worker- and integrator-owned gate for the
  declared scope passes at 100 percent at the current branch head (commands
  above). The fix-pass trigger is a process artifact, not a failing in-scope
  check: the integrator's message
  `integration run failed without fresh, usable test results (compile or setup error)`
  comes from the whole-repository Java integration run and is unrelated to a
  staff-web-only change. I did not run that gate, per the worker "no full
  verify" rule.
- **Pre-existing, out of scope — committed conflict markers.** `git grep` shows
  marker lines already committed on `origin/main` inside
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java` (a BUILD-37 merge artifact). They sit
  inside Javadoc comments, so they do not break Java compilation, but they are
  real residue worth a separate cleanup task. No file was edited.
- **Pre-existing/environmental — `check-pack.py`.** Earlier workers recorded
  `python3 -I scripts/check-pack.py` reporting `opencode.json is stale`: the
  documented cloud-worker CI profile under `skip-worktree`. No `.opencode/`,
  `opencode.json`, workflow or script file was touched in this pass.
- **Pre-existing tracker noise.** The `{"violations": [{"file": "TODO.md",
  "rule": "tracker"}]}` lines in the hand-off findings come from other workers'
  candidates; this candidate touches only the two scoped files and notes files,
  and as a swarm worker I do not edit `TODO.md`.

## Lessons for workers

- A fix pass over an already-green candidate is a verification and hand-off job:
  re-run the module `check.sh`, the explicit typecheck,
  `scripts/coverage-check.sh` and `scripts/frontend-typecheck-check.sh`, confirm
  100 percent, and stop. Do not churn a correct implementation to look busy.
- CSSOM `style.getPropertyValue()` takes dash-case names; camelCase keys always
  read back as an empty string and prove nothing when enforcing logical-only
  properties.
- Scope a physical-property assertion to the elements the component styles; a
  nested shared component may carry a pre-existing physical shorthand owned by
  another task (here `StateViews.tsx` / RESP-STATEVIEWS).
- In jsdom an inline `minInlineSize: 0` reads back as `'0'`, not `'0px'`.
- `I18nProvider` writes `document.documentElement.dir/lang` in an effect; reset
  both in the test's `afterEach` and call `unmount()` between two renders in one
  test.
- `scripts/check.sh` runs the full `mvn verify`; under the worker "no full
  verify" rule, run the module check and policy scripts individually and record
  each command and final line instead.
