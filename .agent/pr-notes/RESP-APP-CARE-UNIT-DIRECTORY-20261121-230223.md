# RESP-APP-CARE-UNIT-DIRECTORY — responsive CareUnitDirectory (units)

## Summary

Made the patient `CareUnitDirectory` presentation responsive to the current
window (decision 0005) without changing its data, text, connect action or public
API. Large windows get a centred, bounded reading column; every window wraps so
200% text reflows; controls keep a 44x44 touch floor; each unit card is capped
at the window width so a long identifier cannot push a row wider than a compact
viewport; safe-area insets pad all four edges and mirror with RTL. Roles, labels
and reading order are unchanged.
Added a dedicated tablet/landscape/large-text test that renders the component at
each window class (compact, regular, large) and font scale (1.0, 1.5, 2.0),
plus landscape and RTL cases. Only the two in-scope files changed; no dependency,
no new persistent clinical cache (PAT-02) and no exported-name change.

Two-pane list+detail rendering does not apply to this component: it is a plain
directory list, not one of the list+detail screens named in the story (inbox and
thread, appointments and detail, reports and detail). It keeps one list at every
class, which is the correct reading of the window-width rule for a list-only
surface.

## Affected requirements

- PAT-01 — patient-facing presentation; no behaviour or retrieval change
- PAT-02 — no persistent clinical cache added; layout is presentation only
- UX-01 — 44pt targets, 200% text without clipping, safe areas, unchanged
  screen-reader roles/labels/order, RTL mirroring

## Risk class

Routine. Presentation-only change inside one presentational component plus a
new test. No data, authorization, API, contract or migration change; no clinical
decision encoded.

## Review owner

Simulated `orevia-senior-mobile` review (bounded, read-only). No human,
clinical or real-patient evidence is claimed; that remains a G5 gate.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.
No storage of any kind was added.

## Authorization boundaries and audit events

Unchanged. The component still renders only the units supplied by its caller
(already scoped by verified relationships) and still calls `onConnect` only on
explicit press. No tenant, role or patient-link logic changed; no audit event
added or removed.

## Migration and rollout considerations

None. Pure client presentation change; no release-tag or deployment action was
taken (separately authorized).

## Test evidence

All commands run from the repository root unless noted. All data is synthetic.

Red phase (before implementation, tool evidence):

```
$ cd apps/patient-mobile && npx jest src/features/units/CareUnitDirectory.tablet.test.tsx --runInBand --silent
Test Suites: 1 failed, 1 total
Tests:       14 failed, 14 total
```

Green targeted run (tool evidence):

```
$ cd apps/patient-mobile && npx jest src/features/units/CareUnitDirectory.tablet.test.tsx src/features/units/index.test.tsx --runInBand --silent
Test Suites: 2 passed, 2 total
Tests:       31 passed, 31 total
```

Canonical module gate (tool evidence), `EXIT=0`:

```
$ bash apps/patient-mobile/check.sh
> patient-mobile@0.1.0 typecheck
> tsc --noEmit
> patient-mobile@0.1.0 lint
> eslint .
> patient-mobile@0.1.0 test:coverage
> jest --coverage --ci
All files          |     100 |      100 |     100 |     100 |
Test Suites: 63 passed, 63 total
Tests:       665 passed, 665 total
Snapshots:   0 total
Time:        14.288 s
```

Typecheck final lines (no `error TS…`):

```
> patient-mobile@0.1.0 typecheck
> tsc --noEmit
```

Entry-point-to-test trace: `CareUnitDirectory` is a presentational React Native
component; there is no HTTP entry point. It is rendered directly by
`src/features/units/index.test.tsx` (existing behavior tests) and by the new
`src/features/units/CareUnitDirectory.tablet.test.tsx` (responsive contract).
Assertions go through rendered output: `getByTestId('unit-directory')` for the
list container style, `getByLabelText('Connect to <name>')` for the connect
actions, `getByTestId('unit-<id>')` for each row and a JSON-tree walk for Text
truncation props. `onConnect` behaviour is proven by the existing
`index.test.tsx` "renders organization, facility, name and topics with
accessible connect action" test.

Lightweight repository policy checks relevant to the change (tool evidence):

```
$ python3 -I scripts/check-docs.py       -> EXIT 0
$ python3 -I scripts/check-secrets.py    -> EXIT 0
$ python3 -I scripts/check-names.py      -> EXIT 0
$ python3 -I scripts/check-wiring.py     -> EXIT 0
```

`scripts/check.sh` was intentionally not run: it invokes the full Java/Database
integration gate (`apps/api/check.sh`, `db/check.sh`), which the story forbids
for a worker. Those are the integrator's and Delivery's gates; only the patient
module gate was run here.

Fix-pass verification after the simulated reviewer's two Medium findings
(exact safe-area assertions and window-width-capped cards), tool evidence:

```
$ cd apps/patient-mobile && npx jest src/features/units/CareUnitDirectory.tablet.test.tsx src/features/units/index.test.tsx --runInBand --silent
Test Suites: 2 passed, 2 total
Tests:       31 passed, 31 total

$ bash apps/patient-mobile/check.sh
EXIT=0
All files          |     100 |      100 |     100 |     100 |
...features/units |     100 |      100 |     100 |     100 |
...Directory.tsx |     100 |      100 |     100 |     100 |
Test Suites: 63 passed, 63 total
Tests:       665 passed, 665 total
```

Branch sync: `git merge --no-edit origin/main` was a no-op — HEAD and
`origin/main` were both `fb8e2174` (0/0). No conflicts in the touched files
(`git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/patient-mobile/src/features/units`
returned none).

## Coverage

Command: `bash apps/patient-mobile/check.sh` (runs `jest --coverage --ci` with
module-wide 100% thresholds). Result: `All files 100/100/100/100`; touched file
`features/units/CareUnitDirectory.tsx 100/100/100/100` (lines, branches,
functions, statements). No threshold, exclusion or skip was added or lowered.
The new test file is excluded from source coverage by the module's existing
config, as are all test files.

Each new or changed branch and the test that takes it:

| Branch | Test taking true side | Test taking false side |
|---|---|---|
| `isRTL ? 'row-reverse' : 'row'` (list flexDirection) | tablet `mirrors the list for RTL…` and existing `uses a forward row layout…` | existing `uses a forward row layout for LTR locales` and the LTR matrix |
| `isLarge ? MAX_READING_WIDTH : undefined` | tablet large-window matrix case (width 1024) | tablet compact (390) and regular (700) cases |
| `isLarge ? 'center' : undefined` (alignSelf) | tablet large-window matrix case | tablet compact/regular matrix cases (`not.toBe('center')`) |
| `isRTL ? insets.right : insets.left` (paddingStart) | tablet RTL test (`>= mockInsets.right`) | LTR matrix (`>= mockInsets.left`) |
| `isRTL ? insets.left : insets.right` (paddingEnd) | tablet RTL test (`>= mockInsets.left`) | LTR matrix (`>= mockInsets.right`) |
| `useSafeAreaInsets ?? default.useSafeAreaInsets` (mock-shape fallback) | new tablet test (top-level named mock) | existing `index.test.tsx` (global `default`-only mock) |
| `units.length === 0` (empty state) | existing and new empty-state tests | matrix tests with two units |
| `windowClassFor` 600/840 breakpoints | reused from `src/app/layout/useWindowClass.test.ts` plus tablet large case | tablet compact and regular cases |

No unreachable guard was added: every branch above is exercised by a test that
asserts an outcome (style value, label, count or presence), not merely a call.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical-result logic changed. A bounded simulated senior-mobile
review was requested; its findings (if any) are recorded under Findings.

## Findings

- **Pre-existing committed conflict markers, out of scope.** `git grep -nE
  '^(<<<<<<<|>>>>>>>) '` finds markers committed at HEAD in
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`
  and siblings (e.g. `origin/swarm/BUILD-37-469`). They exist in HEAD
  (`git grep … HEAD` confirms), are unrelated to this story and are outside its
  scope, so they were left untouched. They are not merge conflicts in progress.
- **Safe-area Jest mock shape.** `apps/patient-mobile/jest.setup.ts` mocks
  `react-native-safe-area-context` via `jest.requireActual(.../jest/mock)`, which
  exposes `useSafeAreaInsets` only on `default`. A named `import {
  useSafeAreaInsets }` therefore is `undefined` in the existing `index.test.tsx`,
  so calling the stock `useWindowClass()` hook there crashes. Because that test
  file and the setup file are out of scope, the component resolves the hook from
  the module namespace with a `default` fallback (documented in source) and
  reuses `windowClassFor` for the breakpoint. Production uses the named export;
  both shapes are covered by tests. A cleaner repo-level fix is to make the
  shared mock expose named exports too — tracked as a finding for the owner,
  not changed here.
- **Simulated senior-mobile review (no blocking findings).** Two Medium
  findings were addressed in a fix pass: (a) the safe-area assertions used
  `toBeGreaterThanOrEqual`, which would not catch a swapped left/right or
  top/bottom mapping — they now assert exact `inset + GUTTER` values in both
  LTR and RTL; (b) a headless renderer cannot prove real 200% no-clip, and long
  unbreakable identifiers could make a card wider than a compact window — each
  unit card is now capped at `maxWidth: '100%'` and that cap is asserted.
  Residual limitation, recorded honestly: a real 200% text/no-clip device pass
  (UX-01) is still required; the test's font-scale matrix is a proxy that proves
  no scaling-suppressing props and stable structure, not painted layout. The
  reviewer also noted the shim's branch-coverage coupling, now documented in
  the test header.
- **Two-pane not applicable** to a list-only directory (see Summary).

## Lessons for workers

- In this repo, a component that calls `useWindowClass()` crashes under the
  global safe-area Jest mock when its test does not override that mock; resolve
  `useSafeAreaInsets` from the module namespace (named export with a `default`
  fallback) if you cannot edit `jest.setup.ts` or the existing tests.
- `@jest/globals` Jest 30 `jest.fn` takes one function-type argument
  (`jest.fn<(u: U) => void>()`), and an awaited `@testing-library/react-native`
  render result is typed `Awaited<ReturnType<typeof render>>`.
- Do not render several React Native trees inside one `it` without awaiting
  cleanup — overlapping `act()` scopes make later renders return `null`. Use
  `it.each` for per-window renders.
- `UNSAFE_getAllByType` takes a component type, not a string; walking
  `view.toJSON()` is a stable way to assert props across every rendered Text.
- Verify committed conflict markers against `HEAD` before treating them as a
  merge you caused; do not fix out-of-scope files in a scoped swarm task.

## Why this is not finished

Not applicable — the task is complete. All required gates passed locally:
`bash apps/patient-mobile/check.sh` exit 0, 665/665 tests green, 100% coverage
on the touched component. The worker's coverage/typecheck re-run and the
integrator's full gate remain the next verification steps for the merged result.
