# LAB-02 clause 2 — support preliminary, final, corrected and entered-in-error reports

## Clause

Support preliminary, final, corrected and entered-in-error reports.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.lab.clauses.SupportPreliminaryFinalCorrectedEnteredErrorReportsTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/lab/clauses/SupportPreliminaryFinalCorrectedEnteredErrorReportsTest.java`),
a plain JUnit test with a fixed clock and synthetic fixtures that drives the in-memory
laboratory report kernel directly. Surefire: `tests="7" errors="0" skipped="0" failures="0"`.

- `preliminaryFinalCorrectedAndEnteredInErrorRevisionsAreAppendedInOrder` (rule met) —
  captures a `PRELIMINARY` v1, verifies it to `FINAL` v2 with the verifier stamped and
  `supersedesVersion=1`, corrects it to `CORRECTED` v3 with a reason while preserving the
  final revision, and withdraws it to the terminal `ENTERED_IN_ERROR` v4 while preserving
  all three earlier revisions. It asserts the four states and versions in append order,
  the reason and supersession links, and one distinct event per issued revision in
  emission order (`RESULT_ISSUED`, `RESULT_CORRECTED`, `RESULT_WITHDRAWN`), with no event
  identity repeated or reordered.
- `collectorRoleCannotVerifyCorrectOrWithdraw` (negative, 403) — a `lab_collector` may
  capture but cannot verify, correct or withdraw, leaving the report preliminary and
  emitting no event.
- `anotherTenantCannotReadOrMutateTheReport` (negative, 404) — `org-b` can neither read
  nor verify, correct or withdraw the `org-a` report and receives no events.
- `staleVersionCannotIssueCorrectOrWithdraw` (negative, 412) — a stale expected version
  refuses verify, correct and enter-in-error without appending a revision or event.
- `enteredInErrorForbidsEveryFurtherTransition` (negative, 409) — no verify, correction
  or second withdrawal is possible after the terminal revision.
- `missingReasonVerifierOrMismatchedLabelsAreRefused` (negative, 422) — blank reasons,
  blank verifier, owner and queue, and patient/specimen/order mismatches are refused.
- `correctBeforeVerificationIsRejected` (negative, 409) — a preliminary report cannot be
  corrected before it is issued.

Production classes and files exercised by this kernel path:

- `com.aurevia.smartclinic.lab.result.LabResultService` — capture, verify, correct and
  enter-in-error, append-only revision ledger, version preconditions, label matching,
  evaluation and trigger emission.
- `com.aurevia.smartclinic.lab.result.LabCommands` — the `CaptureCommand`,
  `VerifyCommand`, `CorrectCommand` and `ErrorCommand` records.
- `com.aurevia.smartclinic.lab.result.LabTypes.VerificationState` and
  `com.aurevia.smartclinic.lab.result.LabTypes.TriggerType` — the legal transition matrix,
  issued/terminal/reason predicates and wire forms.
- `com.aurevia.smartclinic.lab.result.LabRules` — the `COLLECTOR` and `VERIFIER` role
  boundary used by the negative role test.
- `com.aurevia.smartclinic.lab.result.ReportRevision`, `LabObservation`,
  `LabPolicyCatalog`, `ReferenceRange`, `TriggerEvent` and
  `com.aurevia.smartclinic.api.ApiException` — the immutable revision, observation,
  approved range, emitted event and structured error types asserted by the tests.

The reachable production path also delegates to this kernel:
`labflow.results.ResultEntryService` injects `LabResultService` and routes verify, correct
and enter-in-error through it; `labflow.results.LabResultEntryController` exposes
`POST /api/v1/lab/results/{id}`, `/verify`, `/correct`, `/error` and `GET /{id}`. This
story does not drive that HTTP path, so the status is Partly proven rather than Proven
by tests.

## Gap

The four states and their refusals are implemented and proven at the domain-kernel level;
no behaviour is missing, so no test is marked `@Disabled`. Two separate pieces of evidence
remain outside this test-only story:

1. **HTTP-level proof.** No test in this story reaches `LabResultEntryController` over
   HTTP with real PostgreSQL and two synthetic tenants. One follow-up test task is needed
   in `apps/api/src/test/java/com/aurevia/smartclinic/lab/clauses` (or the `it/lab` fixture
   area) to assert the same states, versions, events and 403/404/412/409/422 refusals
   through `POST /api/v1/lab/results/{id}`, `/verify`, `/correct`, `/error` and
   `GET /{id}`. It can reuse the existing synthetic-auth and PostgreSQL fixtures; no
   production code or migration needs to change to prove the behaviour.
2. **OpenAPI contract entries.** The four mutating result routes (`POST /{id}`,
   `/verify`, `/correct`, `/error`) are listed in
   `contracts/openapi/missing-routes.txt:237-240` but are absent from
   `contracts/openapi.yaml`, so the generated client does not expose them. One separate
   contracts task must add the tagged operations with `Idempotency-Key` on each POST,
   document the `GET /{id}` route as well, and rerun `gen:api`.

Total: two separate tasks. Neither is in this story's scope, which is limited to one new
test file and this document.
