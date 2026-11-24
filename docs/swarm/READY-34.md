# READY-34 · Patient app: four-tab navigation (Appointments, Reports, Messages, Account)

PR #234, tested branch tip c8d581762a9f5584b463c8f6166bb6363069112d.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (end of the local check log):

```text
===================================================
FAIL: test_resolve_merges_main_into_a_conflicted_pr_branch (test_agent_run.AgentRunTests.test_resolve_merges_main_into_a_conflicted_pr_branch)
----------------------------------------------------------------------
Traceback (most recent call last):
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_agent_run.py", line 152, in test_resolve_merges_main_into_a_conflicted_pr_branch
    self.assertEqual((self.repo / ".agent/resolve/5").read_text(), "merged")
AssertionError: 'merged but checks failed' != 'merged'
- merged but checks failed
+ merged


----------------------------------------------------------------------
Ran 400 tests in 14.864s

FAILED (failures=5, errors=12)
Added 1 audit task(s).
Added 1 audit task(s).
Estimated monthly swarm minutes 9900 of 10000: pausing waves.
Public repository: runner minutes are free; the budget gate is off.
publish: swarm/CW-001-1 moved while CW-001 worked; keeping the newer branch
publish: PR #193 is already merged; nothing to push for CW-001
publish: PR #193 is already closed; nothing to push for CW-001
slot images are missing from the registry
slot images are present in the registry
ok
A repair task is already open.
ok
ok
no
ok
The run is not a failed run on main; nothing to repair.
No failing pipeline run on main needs a repair task.
No failing pipeline run on main needs a repair task.
ok
ok
A repair task is already open.
Healthy. 0 wave(s) running or queued; the last wave started 10 minutes ago. Ready now: nothing. Main red: no. Last runs: completed/failure.
Closed finished issues: [6]
Healthy. 0 wave(s) running or queued; the last wave started 20 minutes ago. Ready now: CW-001. Main red: no. Last runs: completed/failure.
Stalled; issue 42. 0 wave(s) running or queued; the last wave started 300 minutes ago. Ready now: nothing. Main red: yes. Last runs: completed/failure.
Healthy. 1 wave(s) running or queued; the last wave started 200 minutes ago. Ready now: CW-001. Main red: no. Last runs: in_progress/failure. Closed stall issue [3].
Healthy. 0 wave(s) running or queued; the last wave started 10 minutes ago. Ready now: CW-001. Main red: no. Last runs: completed/failure.
Closed finished issues: [5]
Healthy. 0 wave(s) running or queued; the last wave started 10 minutes ago. Ready now: CW-001. Main red: no. Last runs: completed/failure.
Healthy. 0 wave(s) running or queued; the last wave started 10 minutes ago. Ready now: CW-001. Main red: no. Last runs: completed/failure.
Closed finished issues: [8]
Healthy. 0 wave(s) running or queued; the last wave started 300 minutes ago. Ready now: nothing. Main red: no. Last runs: completed/failure.
Healthy. 0 wave(s) running or queued; the last wave started 10 minutes ago. Ready now: CW-001. Main red: no. Last runs: completed/failure.
Closed finished issues: [5, 6]
Stalled; issue 42. 0 wave(s) running or queued; the last wave started 90 minutes ago. Ready now: CW-001. Main red: no. Last runs: completed/failure.
```

Task: READY-34

Swarm worker 6. Local checks: failed. Agent result: ok.

# READY-34 — Patient app four-tab navigation

## Summary

Replaced the READY-33 placeholder navigator with the four-tab patient shell from
`docs/design/screens-and-navigation.md` section 3: **Appointments, Reports,
Messages, Account**, in that order. Appointments and Reports mount their existing
presentational screens directly. Messages and Account are small local push/pop
stacks (no stack-navigator dependency exists and none was added) that expose the
conversation list, attachment review and instruction acknowledgment screens, and
the enrollment, recovery, support and lockout screens. All screen props come from
synthetic `placeholder...` providers; no authentication, network client or push
notification code was added. The single native-direction boundary
`applyDirection(locale)` calls `I18nManager.forceRTL` and is unit tested for both
locales.

## Affected requirements

- **PAT-01** — Appointments tab renders `AppointmentListScreen` with a generic notice.
- **PAT-02** — Reports tab renders `ReleasedReportsScreen`.
- **PAT-03** — Account stack reaches enrollment, recovery, support and lockout screens.
- **UX-01** — English/Arabic tab labels and accessibility labels from `app/messages.ts`;
  one tested `applyDirection(locale)` helper; accessible control roles/labels; tab order
  mirrors with native RTL.

Scope: `apps/patient-mobile/src/app/**` and `apps/patient-mobile/App.tsx` only. No file
outside that scope was changed. No dependency, migration or generated file was added.

## Risk class

Routine (presentational navigation shell). No clinical decision, patient data, signed
record, tenant policy or critical-result logic is introduced. A bounded advisory plan
was requested from the `orevia-senior-mobile` specialist before implementation.

## Review owner

Senior React Native and Expo (primary), Senior TypeScript (strict typing and coverage),
UX reviewer (accessibility labels and RTL). Simulated reviewer evidence only; no human
clinical or patient review was available or claimed.

## Data changes

None. No migration, schema, backfill, lock or volume change. All fixtures are empty
synthetic projections or generic non-clinical strings in `providers.ts`.

## Authorization boundaries and audit events

None. The shell adds no command, no protected action and no audit event. No token,
endpoint, patient identifier or clinical payload is present in source, logs, URLs or
storage. The pre-existing `x-synthetic-*` style placeholders are not carried into any
message.

## Migration and rollout considerations

None. The change is additive UI code behind the existing Expo entry point; there is no
feature flag, data migration or rollout dependency. `App.tsx` still renders
`RootNavigator`, so the registered root component is unchanged.

## Test evidence

Commands run (final lines shown):

- `npx jest --ci --runInBand src/app` → `Test Suites: 6 passed, 6 total; Tests: 29 passed, 29 total` (red before implementation: 5 suites failed on missing modules; the new tests were written first).
- `npm run typecheck` → exit 0, no output.
- `npm run lint` → exit 0, no output.
- `bash check.sh` (canonical patient-mobile gate: `npm ci`, `tsc --noEmit`, `eslint .`, `jest --coverage --ci`) →
  `All files | 100 | 100 | 100 | 100`, `Test Suites: 33 passed, 33 total`, `Tests: 312 passed, 312 total`.
- `python3 -I scripts/check-names.py`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py` → all exit 0 with no output.
- `npx jest --coverage --ci --runInBand` → per-file table confirms 100% on every file touched.

Entry-point-to-test trace: `App` → `RootNavigator` (`App.test` path is exercised by
`RootNavigator.test.tsx`, which renders `App` and asserts the registered root component);
`RootNavigator` → `Tab.Navigator` (four `tab-button-*` testIDs) → `TabContent` (all four
switch arms pressed in `renders each tab screen when that tab is pressed`) → feature
screens → `ScreenStack` → pushed screens. `applyDirection` is exercised directly by
`direction.test.ts`. `providers.ts` and `tabs.ts` are exercised by their own unit tests
and by every navigator render.

Database and integration tests were not run by this worker, per the dispatch
instruction; the integrator runs them. No CI run/SHA is quoted because this worker did
not push or call `gh`.

## Coverage

Coverage command: `bash apps/patient-mobile/check.sh` and
`npx jest --coverage --ci --runInBand` from `apps/patient-mobile`.
Result: global 100% statements, branches, functions and lines; every touched file
(`App.tsx`, `RootNavigator.tsx`, `ScreenStack.tsx`, `direction.ts`, `messages.ts`,
`providers.ts`, `tabs.ts`) reports 100/100/100/100.

New branches and t
