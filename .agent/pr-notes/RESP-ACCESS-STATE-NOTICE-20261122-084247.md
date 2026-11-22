# RESP-ACCESS-STATE-NOTICE — Staff web: responsive `AccessStateNotice` (features-admin)

## Summary

Made the synthetic administration access-state notice responsive without changing its behaviour, text, API calls or exported names. The notice now reads the breakpoint hook, exposes `data-breakpoint`, stacks its badge and reviewed label on compact screens (`stack`) and lays them inline from the medium band up (`cluster`), and uses safe-area-aware **logical** inline padding plus `min-inline-size: 0` / `overflow-wrap: anywhere` so long English and Arabic values wrap instead of forcing a horizontal page scroll. Added a jsdom responsive test that mocks `matchMedia` for compact, medium and expanded and checks structure, roles, labels, DOM/keyboard order, continuity across a viewport change and the right-to-left case. Existing tests remain green.

Scope respected: only `apps/staff-web/src/features/admin/AccessStateNotice.tsx` and `apps/staff-web/tests/responsive/features-admin/AccessStateNotice.responsive.test.tsx`. No dependency, stylesheet, config, tracker or workflow file was changed.

## Affected requirements

- **WEB-07** — responsive staff web from 320 px; no horizontal page scroll, long values wrap, columns stack on phones.
- **UX-01** — logical properties, safe-area insets, RTL mirroring, keyboard/DOM order, no hidden clinical value, text readable at 200% zoom.

## Risk class

Routine (docs/WORKFLOW.md): one contained staff-web presentation change with an added view-level test; no data, contract, authorization or workflow boundary crossed. Working tree kept current with `origin/main` (merge at start reported "Already up to date").

## Review owner

`smartclinic-senior-react` (staff React/Vite), `smartclinic-senior-typescript` (strict TS), `smartclinic-ux-reviewer` (RTL/keyboard). Definition of done from the senior-react skill applied: full `apps/staff-web/check.sh`, typecheck final lines recorded below, every `error TS…` fixed.

## Data changes

None. No migration, table, backfill, lock or rollout impact. Synthetic fixture labels only (`Synthetic probe SYN-PROBE-01`); no patient data, credential or real identifier.

## Authorization boundaries and audit events

None. The component only displays a pre-existing synthetic `AccessState` (`permitted`/`denied`/`concealed`); its `role="alert"|"status"`, `data-access-state`, glyphs and message text are unchanged. No tenant, role or patient-link logic and no audit event was added or changed.

## Migration and rollout considerations

None. No production deployment action; presentation-only change to a display component already reachable from `AdminScreen.tsx`.

## Test evidence

Evidence source: executed tool commands (Vitest, tsc, ESLint, Vite build, bundle-size gate, V8 coverage) plus one simulated UX reviewer; no human, clinical or simulated-user evidence.

- RED — `cd apps/staff-web && node_modules/.bin/vitest run tests/responsive/features-admin/AccessStateNotice.responsive.test.tsx`
  → `Test Files 1 failed (1)`, `Tests 11 failed | 3 passed (14)`; failures were exactly the missing `data-breakpoint`, `stack`/`cluster` class and logical safe-area padding (e.g. `expected null to be 'compact'`, style was `display: grid; gap: 4px; border: 1px …; padding: 4px;`).
- GREEN (targeted + regression) — `node_modules/.bin/vitest run tests/responsive/features-admin/AccessStateNotice.responsive.test.tsx src/features/admin/admin-components.test.tsx`
  Final lines:
  ```
  Test Files  2 passed (2)
       Tests  40 passed (40)
  ```
- MANDATORY FRONTEND GATE — `bash apps/staff-web/check.sh` (from repo root). All stages passed; final lines:
  ```
  gen:api: generated client matches the contract
  Test Files  159 passed (159)
       Tests  1905 passed (1905)
  No files with missing coverage.
  207 files fully covered.
  4 empty files skipped.

  =============================== Coverage summary ===============================
  Statements   : 100% ( 3722/3722 )
  Branches     : 100% ( 1894/1894 )
  Functions    : 100% ( 1258/1258 )
  Lines        : 100% ( 3556/3556 )
  ================================================================================
  ```
  (The `useI18n/useTheme must be used inside …Provider` stack traces printed during the run come from the existing intentional fail-closed tests in `tests/i18n/I18nProvider.test.tsx` and `tests/theme/ThemeProvider.test.tsx`; the suite and coverage gate passed.)
- TYPECHECK — `npm run typecheck` in `apps/staff-web`. Final lines (no `error TS…`):
  ```
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json

  EXIT:0
  ```
  (An earlier check.sh run caught `TS6133: 'screen'/'ReactElement' is declared but its value is never read` in the new test; both unused imports were removed and the gate re-run green.)
- Markdown check — `python3 -I scripts/check-docs.py` → `check-docs exit: 0`, no output (run after editing this notes file).
- Independent bounded UX review — `smartclinic-ux-reviewer` (source: **reviewer, simulated**): no blocking findings; structural requirements, no-hidden-text/no-physical-sides/no-behaviour-change and test coverage all judged satisfied. Its advisory "record the missing-class Lesson" is already covered under Lessons for workers below.
- Commit (local branch only, not pushed): `327693f3 Make AccessStateNotice responsive with breakpoint-aware layout`.
- Entry-point-to-test trace: `AdminScreen.tsx` renders `<AccessStateNotice state label />` in the access-review section → the new test renders the same component under the real `ThemeProvider` + `I18nProvider` with a deterministic `matchMedia` stub → asserts `data-breakpoint`, `data-access-state`, role, glyph, both `<bdi>` runs, logical style string and RTL behaviour.

## Coverage

Command: `bash apps/staff-web/check.sh` (runs `npm run test:coverage`, V8 provider, per-file thresholds of 100% lines/branches/functions/statements; no threshold, exclusion or skip was changed). Result: 100% overall — `3722/3722` statements, `1894/1894` branches, `1258/1258` functions, `3556/3556` lines; `No files with missing coverage. 207 files fully covered.`

Touched production file, from `coverage/coverage-summary.json`:
`src/features/admin/AccessStateNotice.tsx` — lines 6/6 (100%), branches 4/4 (100%), functions 1/1 (100%), statements 6/6 (100%).

New/changed branches and the test that takes each side:
- **NEW** `className={breakpoint === 'compact' ? 'stack' : 'cluster'}` — compact side taken by `responsive AccessStateNotice at compact width … > exposes the band …` (asserts `stack`); non-compact side taken by the same test at `medium` and `expanded` (asserts `cluster`), and by `follows a viewport change without remounting …` (compact `stack` → medium `cluster` → expanded `cluster`).
- Pre-existing `role={state === 'denied' ? 'alert' : 'status'}` — `denied` side taken by `admin-components.test.tsx` and the new responsive test (asserts `alert`); `permitted`/`concealed` side taken by both (asserts `status`). Still 100%.
- New logical inline style lines and `data-breakpoint`/hook call are unconditional and executed by every responsive test; the long-English/Arabic, safe-area, no-hidden-value, no-tab-stop, RTL and viewport-change tests assert their outcomes.

## Clinical and architecture review

Not required: display-only change to a synthetic access-state notice. No signed data, cross-organization sharing, patient association, prescribing or critical result is involved. No migration, state machine, event contract or system boundary changed. Risk class is routine.

## Findings

- **Missing class (handled as directed, no stylesheet edit):** `responsive.css` has `.page`, `.stack`, `.cluster`, `.grid-2`, `.card-list`, `.touch`, `.field` and `.sheet`, but no class for an embedded notice/card surface that supplies safe-area-aware logical padding without the page-level reading-max centring of `.page`. Per the story, the stylesheet was not edited; the notice uses the existing `stack`/`cluster` classes plus logical inline `padding-block-*/padding-inline-*` with `max(<token>, var(--safe-*))`. Recorded under Lessons for workers.
- **Pre-existing behaviour retained, not a defect introduced here:** the reviewed `label` run is rendered by `MixedScriptText` with `lang="en"` hard-coded in the component, independent of the active locale; only the translated message run uses `lang={locale}`. An Arabic string passed as `label` is still isolated (`dir="auto"`) and wraps, but carries `lang="en"`. The test asserts the actual authored value rather than the active locale. Left unchanged because the story forbids changing behaviour/text; a localization follow-up could type the label's language.
- **Non-applicable acceptance items:** `AccessStateNotice` contains no input and no interactive control, so the 16 px input-text rule and the 44×44 coarse-pointer target rule do not apply to this component (they are owned by the responsive Form/FormField/Button stories). The component introduces no tab stop.
- **Pixel checks deferred:** per the story, jsdom asserts classes, attributes and structure only; pixel-level no-scroll/no-overlap/zoom checks remain with the Playwright responsive tasks.
- **Raw `1px` border (advisory, reviewer, simulated):** the pre-existing `border: 1px solid ${tokens.border}` uses a literal pixel rather than `var(--sc-border-width-thin, 1px)` that `docs/design/responsive-layout.md` §1 prefers for non-breakpoint values. It is functionally harmless and was intentionally left unchanged because the story forbids changing behaviour/styling beyond responsiveness; a future dedicated change could adopt the token variable.
- **Out-of-scope pre-existing defect (observed, not touched):** `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds committed conflict markers in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java`. Verified present in both `HEAD` and `origin/main` via `git show`, so they predate this task; my clean `git merge --no-edit origin/main` introduced nothing and my scoped files contain no markers. These Java files are outside this task's scope and were left untouched; the integrator/owner should route a repair (likely to break the Java build).

## Lessons for workers

- Run the full `bash apps/staff-web/check.sh`, not just Vitest: it caught two unused imports (`TS6133`) in the new test that Vitest ignored. Fix every `error TS…` before stopping.
- When a component hard-codes `lang` on a label, assert the authored value in the test rather than the active locale; the translated message run carries the locale.
- For an embedded banner/notice there is no safe-area notice class in `responsive.css`; use the hook's `stack`/`cluster` for stacking and logical inline `padding-block-*/padding-inline-*` with `max(space, var(--safe-*))` (mapped RTL-safe in `:root[dir='rtl']`), never physical `padding-left/right`.
- A deterministic `matchMedia` stub must reuse one state object per query string so the hook's `useState` initializer and `useEffect` listener share it; otherwise a `fire()` change never reaches the component.
