# BUILD-21 · Patient app: enrollment, recovery and account lockout screens

PR #298, tested branch tip 17c298ed6873a5a47617481e935a60b9d47cca0b.

Task: BUILD-21

Swarm worker 4. Local checks: passed. Agent result: ok.

# BUILD-21 — Patient app: enrollment, recovery and account lockout screens

## Summary

Completes PAT-03 acceptance for the patient app's pre-authentication account screens.
The enrollment (invitation entry), recovery, lockout, step-up re-authentication and
support-request screens already existed under
`apps/patient-mobile/src/features/enrollment`; the missing acceptance artifact was a
proof that **no screen reveals whether a record exists**.

The concrete defect found and fixed: `EnrollmentScreen` and `RecoveryScreen` rendered
distinct messages for `expired`, `reused`, `locked` and `error`, so an observer could
infer a code/account's state (enumeration). Both screens now render one generic
failure line for every failed status; the detailed reason stays server-side.
`mapInvitationError` was reduced to a single generic failure for the same reason.
All work is inside the declared scope; no dependency, tracker, protected file or
out-of-scope source was changed. All identifiers in code and tests are synthetic.

## Affected requirements

| Acceptance | Where implemented | Where proven |
|---|---|---|
| PAT-03: privacy-safe invitation/enrollment entry | `enrollment/screens.tsx` `EnrollmentScreen` — one generic failure via `isFailedStatus` + `strings.invalid` | `enrollment/privacy.test.tsx` `shows only the generic enrollment message for %s`; `screens.test.tsx` `shows only the generic failure for %s` |
| PAT-03: privacy-safe recovery | `enrollment/screens.tsx` `RecoveryScreen` — same generic failure | `privacy.test.tsx` `shows only the generic recovery message for %s`; `screens.test.tsx` recovery state cases |
| PAT-03: privacy-safe account lockout | `enrollment/screens.tsx` `LockoutScreen` — fixed title, timer and support route, no record field | `privacy.test.tsx` `renders the lockout timer and support route without any record field` |
| PAT-03: renewed authentication for sensitive changes | `enrollment/screens.tsx` `StepUpPrompt` — sign-in-again gate, no credential field; server remains authoritative (Javadoc) | `privacy.test.tsx` `gates a sensitive change on a sign-in prompt with no credential field` |
| PAT-03: support channel for disputes, accessibility and record access | `enrollment/screens.tsx` `SupportRequestScreen`; `model.ts` `validateSupportRequest` / `buildSubmissionNotice` | `privacy.test.tsx` `never echoes record-shaped text in the support notice or alerts`; existing `model.test.ts` / `screens.test.tsx` |
| PAT-03: no screen reveals whether a record exists | `model.ts` `mapInvitationError` now branch-free/generic; new `isFailedStatus`; screens render no status-specific reason | New `enrollment/privacy.test.tsx` (15 cases) + rewritten state tests in `screens.test.tsx` / `model.test.ts` |

## Risk class

Routine (docs/WORKFLOW.md): a contained patient-app UI change. No clinical decision,
no tenant/role change, no prescribing or critical-result logic, no API or data
contract change. Clinical and human-patient acceptance are not implied.

## Review owner

`aurevia-senior-mobile` (bounded, read-only) with `aurevia-patient-user` and
`aurevia-senior-typescript` skills loaded. The specialist returned a static review
and **ran no commands** (reviewer/simulated evidence, not tool evidence); it found no
blocking issue. Its RTL observation was applied (the generic failure text now uses
`getTextAlign(isRTL)`). No simulated user, clinician or human participant ran the app.

## Data changes

None. No migration, table, backfill, lock or volume impact. Synthetic fixtures only
(for example `SYN-PATIENT-0001`, `SYN-MRN-778899`); no patient data, credential, token
or real identifier in code, tests, logs or this note.

## Authorization boundaries and audit events

None changed. These screens are pre-authentication and receive no record, tenant or
role data; the server remains authoritative for invitation expiry/reuse, lockout and
the fresh-auth assertion that a step-up change requires. No audit event added or
changed.

## Migration and rollout considerations

None. No feature flag, configuration input, deployment ordering or infrastructure
change.

## Test evidence

Entry point to test trace: `EnrollmentScreen`/`RecoveryScreen` (presentational,
mounted directly through React Native Testing Library) → status prop →
`isFailedStatus` → single generic `strings.invalid` alert → `privacy.test.tsx`
asserts one indistinguishable message and no record markers. Support notice and
lockout/step-up screens are mounted directly and asserted for absence of record data.

Red (before the production change), tool evidence:

```text
$ cd apps/patient-mobile && npx jest src/features/enrollment/privacy.test.tsx --runInBand --no-coverage
Test Suites: 1 failed, 1 total
Tests:       7 failed, 8 passed, 15 total
# failures: expired/reused/locked rendered distinct enrollment/recovery messages;
# mapInvitationError returned distinct keys per code.
EXIT=1
```

Green (canonical patient-app gate), tool evidence:

```text
$ bash apps/patient-mobile/check.sh
> patient-mobile@0.1.0 typecheck
> tsc --noEmit
> patient-mobile@0.1.0 lint
> eslint .
> patient-mobile@0.1.0 test:coverage
> jest --coverage --ci
All files          |     100 |      100 |     100 |     100 |
Test Suites: 42 passed, 42 total
Tests:       477 passed, 477 total
Snapshots:   0 total
Time:        5.936 s
EXIT=0
```

Typecheck final lines (mandatory): `> tsc --noEmit` completed with no `error TS…`;
`npm run typecheck` exit 0 and `npm run lint` (`eslint .`) exit 0 were also observed
individually before the full gate. No test was skipped.

Repo-wide `bash scripts/check.sh` was run once: exit 1 with only
`check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
documented cloud-worker skip-worktree artifact (BUGS OPS-CW-003-01), not a change
defect. Verified by tool: `git ls-files -v opencode.json` → `S opencode.json`; the
worktree file equals `.github/opencode.ci.json` and differs from
`git show HEAD:opencode.json`. The protected `opencode.json` was **not** edited or
regenerated. The canonical module gate above is green and authoritative for this
story.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (includes `jest --coverage --ci`).
Result: `All files` 100% statements / branches / functions / lines; changed files
`enrollment/model.ts` 100/100/100/100 and `enrollment/screens.tsx` 100/100/100/100.
No threshold, exclusion or skip was changed.

| New or changed branch / method | Covering test |
|---|---|
| `isFailedStatus` (new method): `status !== 'idle'` false | `model.test.ts` `treats %s as failure=%s` (idle); `privacy.test.tsx` `renders no alert in the %s state` (idle) |
| `isFailedStatus`: `&& status !== 'submitting'` false | `model.test.ts` helper table (submitting); `privacy.test.tsx` non-failure state case |
| `isFailedStatus`: `&& status !== 'success'` false | `model.test.ts` helper table (success); `privacy.test.tsx` non-failure state case |
| `isFailedStatus`: all three true (expired / reused / locked / error) | `model.test.ts` helper table plus the state cases in `screens.test.tsx` and `privacy.test.tsx` |
| `mapInvitationError` (changed): no branches remaining — one constant failure for all six codes | `model.test.ts` `maps %s to one generic failure without patient data`; `privacy.test.tsx` `maps every server error code to a single generic failure` |
| `EnrollmentScreen` `failed ? alert : null` true | `screens.test.tsx` `shows only the generic failure for %s`; `privacy.test.tsx` `shows only the generic enrollment message for %s` |
| `EnrollmentScreen` `failed ? alert : null` false | `screens.test.tsx` `collects a code and submits it normalized`; `privacy.test.tsx` `renders no alert in the %s state` |
| `RecoveryScreen` `failed ? alert : null` true | `screens.test.tsx` recovery state cases; `privacy.test.tsx` `shows only the generic recovery message for %s` |
| `RecoveryScreen` `failed ? alert : null` fa
