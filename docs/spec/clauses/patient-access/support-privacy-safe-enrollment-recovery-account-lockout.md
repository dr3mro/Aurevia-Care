# PAT-03 clause 1 — support privacy-safe enrollment, recovery and account lockout

## Clause

Support privacy-safe enrollment, recovery and account lockout.

## Status

Partly proven

## Evidence

Test class: `support-privacy-safe-enrollment-recovery-account-lockout.test.ts`
(path: `apps/patient-mobile/src/spec/patient-access/support-privacy-safe-enrollment-recovery-account-lockout.test.ts`),
a patient-app Jest proof that mirrors the synthetic clock, invitation-code and
enum patterns of the neighbouring `api` and `features/enrollment` tests and imports
the same production helpers. All tests
are active (none skipped) and pass under `bash apps/patient-mobile/check.sh`.

Test methods (one positive rule proof and negative refusals):

- `allowsASensitiveAccountChangeAfterAFreshSignIn` — positive proof. A recorded
  sign-in within the freshness window authorizes a sensitive change with
  `{ allowed: true, reason: 'fresh' }`.
- `requiresReauthenticationWhenNoSignInHasBeenRecorded` — negative refusal when
  no sign-in exists (`authenticatedAt === null`) returns `reauth_required`.
- `requiresReauthenticationOnceTheSignInIsOlderThanTheFreshnessWindow` — negative
  refusal past `freshnessMs` returns `reauth_required`.
- `normalizesAndValidatesAnInvitationCodeBeforeSubmission` — enrollment accepts a
  normalized single-use code and refuses empty and malformed codes.
- `mapsEveryInvitationErrorToOneGenericFailureWithoutEchoingTheInput` — every
  server error code maps to the same `InvitationFailure`; a patient-shaped and
  code-shaped argument is passed in, so the no-echo assertion is non-vacuous and
  no code or account state can be enumerated.
- `refusesAnUnsupportedOrPHIBearingSupportDraft` — null category, empty text,
  text over 500 characters, a phone number and an email address are all refused
  with a stable reason (five cases).
- `acceptsAValidSupportDraftForEveryPurposeAndReturnsAGenericNotice` — each of
  the three support purposes passes validation and the notice carries no draft
  content.
- `showsOnlyTheGenericEnrollmentMessageFor%s` and
  `showsOnlyTheGenericRecoveryMessageFor%s` — `expired`, `reused`, `locked` and
  `error` each render only the generic message and never the state-specific label.
- `showsOnlyALockoutCountdownAndASupportRouteWithNoRecordField` — the lockout is
  active at 125 seconds and clear at 0, and the screen shows the countdown and a
  support route with no patient record field.

Production functions and screens exercised through their public entry points:

- `createSensitiveActionGuard` in `apps/patient-mobile/src/api/reauthentication.ts`
  (the fresh-sign-in decision and both refusal guards).
- Pure helpers in `apps/patient-mobile/src/features/enrollment/model.ts`:
  `normalizeInvitationCode`, `validateInvitationCode`, `mapInvitationError`,
  `isFailedStatus`, `validateSupportRequest`, `SUPPORT_CATEGORIES`,
  `buildSubmissionNotice`, `isLocked` and `formatLockoutCountdown`.
- Screens in `apps/patient-mobile/src/features/enrollment/screens.tsx`:
  `EnrollmentScreen`, `RecoveryScreen` and `LockoutScreen`.

Where the clause is implemented today (source survey result):

- `apps/patient-mobile/src/features/enrollment/model.ts` defines the enrollment
  state model (`EnrollmentStatus`), single-use code normalization and validation,
  one generic `InvitationFailure`, the lockout countdown helpers, the three
  support purposes and the no-PHI support-draft validator. Its header states that
  the server is authoritative for expiry, reuse and lockout.
- `apps/patient-mobile/src/features/enrollment/screens.tsx` provides the
  presentational `EnrollmentScreen`, `RecoveryScreen`, `LockoutScreen`,
  `StepUpPrompt` and `SupportRequestScreen`. Failed states deliberately render a
  single generic message, so the screen cannot disclose which state occurred.
- `apps/patient-mobile/src/api/reauthentication.ts` provides the injected-clock
  `SensitiveActionGuard`. `apps/patient-mobile/src/api/tokenStore.ts` and
  `idleSession.ts` clear the OS credential and transient clinical memory on
  sign-out and idle timeout, supporting the privacy posture.
- `apps/patient-mobile/src/app/RootNavigator.tsx` exposes enrollment, recovery,
  support and lockout as account-tab routes (lines 172–229).

## Gap

The client-side rules above are proven, but the clause is only partly proven end
to end. `RootNavigator.tsx:172-229` renders the four account screens with
`placeholderAction` callbacks, so no enrollment or recovery submission is
actually sent; `createSensitiveActionGuard` has no production caller (it is
imported only by tests), so `StepUpPrompt` is not wired to a renewed-authentication
decision; and no HTTP-boundary test proves server-enforced expiry, reuse or
lockout. Closing the gap needs three separate tasks: (1) wire real state and
handlers into the account flows in `apps/patient-mobile/src/app/RootNavigator.tsx`
and `apps/patient-mobile/src/features/enrollment/`; (2) add a typed patient-app
client and call the guard from the sensitive-change flow under
`apps/patient-mobile/src/api/`; (3) add the server-authoritative invitation and
lockout endpoints with an HTTP-level proof in `apps/api` and
`contracts/openapi.yaml`. This proof-only task changed no production code,
existing test, contract or migration.
