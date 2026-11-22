# PAT-03 clause 3 — patient support channel

## Clause

Provide a support channel for identity disputes, accessibility and record access requests.

## Status

Partly proven

## Evidence

Test file: `apps/patient-mobile/src/spec/patient-access/provide-support-channel-identity-disputes-accessibility-record.test.ts`
(Jest, TypeScript `.test.ts`, no JSX — every render goes through
`React.createElement`; synthetic fixtures only).

Test methods:

- `offersAllThreeSupportPurposesAndAcceptsAValidGenericRequest` — positive proof of
  the rule. `SUPPORT_CATEGORIES` is exactly
  `['identity_dispute', 'accessibility', 'record_access']`;
  `validateSupportRequest({ category: 'record_access', message: 'Please help me access my record' })`
  is `{ ok: true }`; `buildSubmissionNotice()` is the fixed, content-free
  `SUBMISSION_NOTICE`. The screen renders all three purpose radios and leaves
  submit enabled for the valid draft.
- `refusesRequestsWithNoCategoryEmptyOverlongOrPhiMessage` — negative proof. A
  table over all four `SupportInvalidReason` values asserts the exact
  `{ ok: false, reason }` for a null category, a blank message, a message over
  500 characters, a phone-number-like message and an email-like message, so an
  incomplete or privacy-unsafe request cannot pass local validation.
- `GAP: installed Account support screen is a placeholder and no patient-app
  client posts a case to POST /api/v1/patient/access/cases` — present as a
  complete `it.skip(...)` test and intentionally not executed. It wires a
  synthetic `Transport` as the case client, renders the screen with real
  stateful callbacks, drives a purpose selection, a generic message and submit,
  and asserts exactly one correctly shaped case request to
  `/api/v1/patient/access/cases` plus a refusal path where a phone-number-like
  message disables submit and posts nothing. It stays skipped because the
  installed route supplies none of those callbacks and no such client exists.

Production symbols exercised by the active tests (source survey result, none
changed by this proof-only task):

- `apps/patient-mobile/src/features/enrollment/model.ts` — `SUPPORT_CATEGORIES`,
  `SupportCategory`, `SupportDraft`, `SupportInvalidReason`,
  `validateSupportRequest` (which uses `containsPotentialPhi`),
  `SUBMISSION_NOTICE` and `buildSubmissionNotice`. Validation is pure and
  performs no network or local storage access; the server remains authoritative
  for the stored case.
- `apps/patient-mobile/src/features/enrollment/screens.tsx` —
  `SupportRequestScreen` and `SupportStrings`: three labelled purpose radios, a
  generic message input, an accessibility error alert and a submit button whose
  disabled state follows validation.
- `apps/patient-mobile/src/app/providers.ts` — `placeholderAction` and
  `placeholderSupportStrings`, the synthetic callbacks and labels the installed
  route passes to the screen (named in the survey and in the GAP description;
  not imported by the two active tests).

Where the clause is implemented today (source survey result):

- The three-purpose screen and its pure validation and refusal rules are built
  and already tested by `apps/patient-mobile/src/features/enrollment/model.test.ts`
  and `screens.test.tsx`. This file restates that proof for this clause with a
  synthetic, JSX-free fixture.
- `apps/patient-mobile/src/app/RootNavigator.tsx:201-217` installs the Account →
  support route. It renders `SupportRequestScreen` with `category={null}`,
  `message=""`, `noticeVisible={false}` and `placeholderAction` for all three
  callbacks, so no draft is retained and no case is ever submitted.
- No patient-app client posts a support case. The server route
  `POST /api/v1/patient/access/cases` exists in `PatientAccessController.openCase`
  and is backed by `db/migrations/V83__patient_access_store.sql`; it is the
  missing client half only and was neither edited nor exercised here.

## Gap

The patient-facing half — choosing a purpose, writing a generic request and
seeing the generic notice — is built and proven. The end-to-end channel is not:
the installed Account → support route passes `placeholderAction` for every
callback and there is no patient-app client that posts a case to
`POST /api/v1/patient/access/cases`, so no request reaches the care unit.

Closing this gap needs **one** bounded follow-up task (registered as a
`task-requests` entry in the worker notes, not created here). It would add a
small synthetic-fixture-backed case client under
`apps/patient-mobile/src/api/`, replace the three `placeholderAction` callbacks
in `app/RootNavigator.tsx` with real stateful handlers that call it, enable the
skipped test above, publish the POST route in `contracts/openapi.yaml` with a
tag and an `Idempotency-Key` header and regenerate the client. The server route
and its migration already exist and need no change. No production file,
contract, migration or existing test was modified by this proof-only task.
