# RESP-APP-MESSAGING-AUDIT-PANEL — responsive MessagingAuditPanel

## Summary

Made the patient messaging governance panel (`MessagingAuditPanel`) responsive to
the shared window contract without changing any text, fact, accessibility label,
focus order, RTL mirroring, copy behaviour or exported name. On `large` windows
the panel is capped to a maximum readable width (720 pt) and centred; safe-area
insets pad all four edges; the explicit copy control keeps a full 48 pt touch
target (shared `SCREEN_TOKENS.size.target`, the 44 pt / 48 dp platform pair);
facts have no fixed line count, so they reflow at 200% font scale. Added a new
tablet test that drives the component through every window class, font scale and
orientation and asserts structure, labels, safe-area padding, touch targets and
the RTL case. No storage, cache, API call or dependency was added (PAT-02).

Changed files (scope only):
- `apps/patient-mobile/src/features/governance/MessagingAuditPanel.tsx`
- `apps/patient-mobile/src/features/governance/MessagingAuditPanel.tablet.test.tsx`

## Affected requirements

PAT-01, PAT-02, UX-01 (requirements named in the story); MSG-07 is the panel's
own domain requirement, unchanged by this presentation-only change.

Acceptance mapping (story step 2):
- Compact / regular / large, portrait / landscape, font scale 1.0 / 1.5 / 2.0 —
  nine-case `it.each` matrix plus both orientations (18 renders) in
  `MessagingAuditPanel.tablet.test.tsx`; window class comes from the shared
  `useWindowClass` contract (600 / 840 dp breakpoints).
- Maximum readable width and centred on large — `maxWidth: 720`, `width: '100%'`,
  `alignSelf: 'center'` applied only when `windowClass === 'large'`; asserted for
  large and asserted absent for compact/regular.
- Two panes on large where the screen is list + detail — not applicable to this
  component. This is a single governance facts panel, not a list-plus-detail
  screen; inbox/thread, appointments/detail and reports/detail are separate
  responsive stories. No artificial pane split was introduced (would change
  structure and break existing accessibility assertions).
- Touch targets >= 44 x 44 — `minHeight`/`minWidth` use the shared
  `SCREEN_TOKENS.size.target` (48), asserted on the copy control.
- Nothing clipped at 200% — no `numberOfLines`, no fixed height, no
  `adjustsFontSizeToFit`; `numberOfLines` asserted undefined on a fact at every
  scale including 2.0.
- Safe-area insets respected — root padding maps `insets.top/right/bottom/left`
  from the shared hook; asserted with non-zero synthetic insets.
- Focus order and screen-reader labels unchanged — only style props were added;
  all existing `accessibilityRole`, `accessibilityLabel` and `testID` values are
  byte-for-byte unchanged; labels re-asserted in the new test.
- RTL mirrors — `isRTL` still drives `column-reverse`; asserted for a large RTL
  window with font scale 2, with labels and the single explicit press unchanged.
- No persistent clinical cache (PAT-02) — no storage, persistence, cache or
  network call was added; layout is derived per render from the window hook.

## Risk class

Routine (contained presentation change to one patient-app component, per
`docs/WORKFLOW.md`). No boundary, contract, migration or authorization change.

## Review owner

aurevia-senior-mobile (bounded review returned **no blocking defects** and
no unmet acceptance criterion); aurevia-ux-reviewer skill loaded for the
UX-01 accessibility/RTL criteria. The reviewer verified the production safe-area
path is valid: React Navigation's `SafeAreaProviderCompat` wraps screens, so
`useWindowClass` has insets when the panel is mounted in a real screen. One
non-blocking hardening item (weak font-scale evidence) was addressed in the
follow-up commit; the other (normalise the global mock / prop seam) needs an
out-of-scope tooling change and is retained under Findings. No human, clinical
or patient participant evidence — simulated reviewer plus tool evidence only.

## Data changes

None. No migration, backfill, lock/volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope is read or written by this
presentation change; no audit event added or removed. All facts remain supplied
by the caller after server-side authorization.

## Migration and rollout considerations

None. Presentation-only, no deployment or configuration change.

## Test evidence

Evidence source: executed tool commands (typecheck, lint, Jest, coverage,
policy checks). No reviewer, simulated-user or human-participant evidence is
claimed in this section.

Commands and final lines (run from the repository root unless noted):

1. Red (new test before production change), from `apps/patient-mobile`:
   `npx jest src/features/governance/MessagingAuditPanel.tablet.test.tsx --no-coverage --runInBand`
   Result: 19 tests failed as expected (e.g. `Expected: 12 Received: undefined`
   for `paddingTop`, `Expected: 720 Received: undefined` for `maxWidth`).

2. Green, scoped:
   `npx jest src/features/governance/MessagingAuditPanel.tablet.test.tsx src/features/governance/index.test.tsx --no-coverage --runInBand`
   Result: `Test Suites: 2 passed, 2 total`, `Tests: 35 passed, 35 total`.

3. Targeted coverage:
   `npx jest src/features/governance --coverage --runInBand --collectCoverageFrom='src/features/governance/MessagingAuditPanel.tsx'`
   Result: `MessagingAuditPanel.tsx | 100 | 100 | 100 | 100 |` (statements,
   branches, functions, lines).

4. Mandatory frontend gate (exit 0, re-run after the reviewer-driven test
   hardening):
   `bash apps/patient-mobile/check.sh`
   Result: `Test Suites: 63 passed, 63 total`, `Tests: 668 passed, 668 total`,
   `Snapshots: 0 total`; `CHECK_EXIT=0`. The coverage table shows
   `...ures/governance | 100 | 100 | 100 | 100 |` and
   `...uditPanel.tsx | 100 | 100 | 100 | 100 |`.

   Typecheck final lines (pasted as required):
   ```
   > patient-mobile@0.1.0 typecheck
   > tsc --noEmit
   ```
   No `error TS…` was emitted. Lint: `✖ 2 problems (0 errors, 2 warnings)` —
   both pre-existing `import/no-duplicates` warnings in
   `src/features/tracker/presentation.test.ts`, a file outside this scope.

5. Fast policy checks (all exit 0):
   `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`,
   `python3 -I scripts/check-names.py`, `python3 -I scripts/check-wiring.py`,
   `bash scripts/frontend-check.sh` → `frontend standards: policy and tool
   commands passed`.

Entry-point-to-test trace: `MessagingAuditPanel` → `usePanelLayout()` → shared
`useWindowClass()` (mocked to a controlled `WindowLayout` in the new test) →
`containerStyle` / `Pressable.style` → assertions on
`getByTestId('messaging-audit-panel').props.style`,
`getByTestId('request-copy')`, `getByLabelText('Request a copy')` and the audit
fact text nodes.

Note on `scripts/check.sh`: it was NOT run in full because it invokes
`apps/api/check.sh` (the 10+ minute Java `verify`/integration gate), which the
task forbids for a worker. Its fast, relevant checks were run individually above
with exit 0; the full gate belongs to the integrator and Delivery.

## Coverage

Command (targeted): `npx jest src/features/governance --coverage --runInBand
--collectCoverageFrom='src/features/governance/MessagingAuditPanel.tsx'`
Result: 100% statements / branches / functions / lines on the only changed
production file. Full gate: 63 suites / 668 tests, all module coverage at 100%
where measured; no threshold, exclusion or skip was changed, and no test was
skipped.

New or changed branches and the test that takes each side:
- `typeof SafeArea.useSafeAreaInsets === 'function'` (module-level selector):
  true side — `MessagingAuditPanel.tablet.test.tsx`, which mocks
  `react-native-safe-area-context` with a named `useSafeAreaInsets`, so the
  component uses the shared `useWindowClass` mock; false side — existing
  `index.test.tsx`, whose global Jest mock exposes a different export shape, so
  the neutral fallback layout is used. Both sides are exercised in the full
  gate run.
- `windowClass === 'large'` (wide-style selection): true side — the `large`
  cases of the `it.each` matrix and the RTL large test assert `maxWidth: 720`,
  `width: '100%'`, `alignSelf: 'center'`; false side — the `compact` and
  `regular` cases assert `maxWidth`/`alignSelf` are undefined.
- `isRTL ? 'column-reverse' : 'column'` (unchanged from baseline): true side —
  existing RTL test in `index.test.tsx` and the new RTL large test; false side —
  existing LTR test and all 18 matrix renders.
- `events.length === 0`, `revocationEffect !== undefined` and the three
  revocation booleans are unchanged from baseline; their existing coverage in
  `index.test.tsx` is retained, and the new test also asserts the defined-effect
  facts.

No uncovered production line or branch was added. Unreachable code was not
introduced.

## Clinical and architecture review

Not required for this presentation-only change (no signed data,
cross-organization sharing, patient association change, prescribing or critical
results). UX-01 accessibility/RTL criteria are covered by the synthetic test
matrix and the senior-mobile review; real patient and clinical acceptance stay
a G5 gate. No persistent clinical cache is introduced (PAT-02).

## Findings

- Defect / environment surprise: the repository-wide
  `apps/patient-mobile/jest.setup.ts` mocks `react-native-safe-area-context`
  with `jest.requireActual('react-native-safe-area-context/jest/mock')`, which
  exports `useSafeAreaInsets` only on its `default` object. A named import of
  `useSafeAreaInsets` is therefore `undefined`, so the first production consumer
  of the shared `useWindowClass` hook throws under the global mock. This file is
  outside this task's scope and was not changed. The component handles it by
  selecting the shared hook only when the named export is callable and using a
  flat neutral layout otherwise; production (real library) always takes the
  shared-hook path. Proper fix for a future tooling story: in `jest.setup.ts`,
  return `mock.default ?? mock` from the factory so named imports resolve.
- Scope decision: the story's "two panes on large windows" requirement applies
  only to list-plus-detail screens. `MessagingAuditPanel` is a single governance
  facts panel, so no pane split was added; the other large-window rules were
  implemented. The required companion screens (inbox/thread,
  appointments/detail, reports/detail) are separate RESP-APP stories.
- `fontScale` is consumed by the shared window hook and set to 1.0/1.5/2.0 in
  the new test, but the panel has no font-scale-dependent branch: it avoids all
  clipping mechanisms unconditionally (no `numberOfLines`, fixed height or
  `adjustsFontSizeToFit`). This is deliberate — a scale branch would add an
  uncoverable behaviour difference without changing reflow.
- Observed, out-of-scope defect in the base branch (tool evidence): committed
  Git conflict markers exist on `origin/main` at
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java:6,10`,
  `Hl7Parser.java:14,18` and `Hl7Segment.java:10,14` (confirmed with
  `git grep -nE '^(<<<<<<<|>>>>>>>) ' HEAD -- .../hl7/`). They are present in HEAD
  and unchanged by this worker; they are not from my merge (`git merge --no-edit
  origin/main` → `Already up to date`). These would break Java compilation and
  need an owner/BUILD-story repair; I did not touch Java files (outside scope).
- No patient data, credentials or raw logs are stored in these notes or tests;
  all identifiers are synthetic UUID-shaped fixtures.

## Lessons for workers

- Before adding a hook call to a patient-app component, check whether any test
  renders it without a local `react-native-safe-area-context` mock. The global
  `jest.setup.ts` mock exposes hooks on `default`, so a named `useSafeAreaInsets`
  import is undefined there; tests that touch safe area must either mock the
  module with named exports (as `useWindowClass.test.ts` does) or the component
  must tolerate the missing hook.
- `render()` from `@testing-library/react-native` is asynchronous in this Expo /
  Jest 30 setup (`await render(...)`); a synchronous helper returns a Promise
  and `view.getByTestId` is not a function.
- `ViewStyle` fields are readonly here: build style objects immutably (spread a
  conditional `wideStyle`) instead of assigning `style.width = ...` after
  creation. Keep `flexDirection` a direct property so existing tests that read
  `props.style.flexDirection` keep working.
- In a cloud worker, reconcile with `origin/main` before editing; `.agent/pr-notes`
  files are per-task and must never collide with the shared day note.
