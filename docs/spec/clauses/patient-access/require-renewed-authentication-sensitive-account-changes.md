# PAT-03 clause 2 — patient app test evidence

## Clause

Require renewed authentication for sensitive account changes.

## Status

Partly proven

## Evidence

Test file: `apps/patient-mobile/src/spec/patient-access/require-renewed-authentication-sensitive-account-changes.test.ts`.
All four tests are active (none skipped) and use synthetic fixtures with an injected mutable clock; no wall-clock time, credential or patient data appears in them. The file matches the Jest `testMatch` pattern `src/**/*.test.ts` and is excluded from coverage sources as a test file.

- `allows a sensitive change while the sign-in is fresh` — positive proof. After `recordAuthentication()` at T0, authorizing at T0 + freshnessMs - 1 returns `{ allowed: true, reason: 'fresh' }`.
- `refuses a sensitive change when no sign-in has been recorded` — negative proof of the absent-sign-in path: authorization returns `{ allowed: false, reason: 'reauth_required' }`.
- `refuses a sensitive change once the sign-in is stale` — negative proof of the expiry path: with the clock at T0 + freshnessMs + 1, authorization returns `reauth_required`.
- `forces renewed authentication again after a sign-out reset` — negative proof of the revocation path: after `reset()` from a recorded sign-in, authorization returns `reauth_required`.

Production classes exercised:

- `createSensitiveActionGuard` and its `SensitiveActionGuard` interface in `apps/patient-mobile/src/api/reauthentication.ts` — the decision point that compares the recorded sign-in time with the injected clock and freshness window and returns `fresh` or `reauth_required`. It stores only a timestamp, never a credential or clinical data.
- `AuthenticationClock`, `SensitiveActionDecision` and `SensitiveActionGuardOptions` in the same module — injected-clock and decision types that keep the rule deterministic.

This suite re-proves branches already covered at 100% by `apps/patient-mobile/src/api/reauthentication.test.ts`; it adds clause-level evidence, not new production coverage, and changes neither that suite nor any production file. The UI prompt `StepUpPrompt` (`apps/patient-mobile/src/features/enrollment/screens.tsx`, definition at line 279) is tested separately as a prompt with no credential field in `apps/patient-mobile/src/features/enrollment/screens.test.tsx:208`, `apps/patient-mobile/src/features/enrollment/screens.tablet.test.tsx:146` and `apps/patient-mobile/src/features/enrollment/privacy.test.tsx:161`. Those tests cover the prompt only, not the decision to allow or refuse a change.

## Gap

The rule exists as a decision point and a UI prompt, but neither is connected to a sensitive account change in the running app. A source-wide search of `apps/patient-mobile/src` finds no production caller of `createSensitiveActionGuard`, `authorizeSensitiveAction` or `StepUpPrompt` outside their own definitions and tests: the guard is never instantiated by the application root and `StepUpPrompt` is never shown before a change. `docs/security/threat-model.md:31` records the same debt: "Wire the idle guard and reauthentication gate into the running app with an end-to-end test — the guards exist as tested units; no wiring into the application root was found in this scope."

This gap is already owned by the open task `tasks/00050000-AUD-20261122-02.md` ("Wire patient enrollment, recovery and support screens to PatientAccessController"), whose scope is `apps/patient-mobile/src/app` and `apps/patient-mobile/src/features/enrollment` and whose acceptance explicitly includes "reauth before a sensitive change". Closing it will change the application root and session wiring to instantiate one guard, record sign-in, reset on sign-out or idle timeout, and call `authorizeSensitiveAction()` before each sensitive change, rendering `StepUpPrompt` when it returns `reauth_required`. The existing server-side fresh-auth assertion in `apps/api/src/main/java/com/aurevia/smartclinic/messaging/enrollment/PatientAccessController.java:81-218` should be verified by that end-to-end test rather than added anew. Task count: 0 new tasks — the wiring and its end-to-end test are already covered by `AUD-20261122-02`. This test-only task does not close the gap.
