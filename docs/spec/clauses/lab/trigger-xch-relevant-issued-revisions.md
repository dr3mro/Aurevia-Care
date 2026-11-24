# LAB-02 clause 6 — Trigger XCH-04/05 on relevant issued revisions

## Clause

Trigger XCH-04/05 on relevant issued revisions.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.lab.clauses.TriggerXchRelevantIssuedRevisionsTest`
(`apps/api/src/test/java/com/aurevia/lab/clauses/TriggerXchRelevantIssuedRevisionsTest.java`),
an HTTP-level integration test under the `synthetic-auth` profile against a fresh real-PostgreSQL
database created from the production Flyway migrations, with synthetic subjects, accessions and
patients only. The test drives the real routes `POST /api/v1/patients`,
`POST /api/v1/lab/accessions`, `POST /api/v1/lab/specimens/{id}/collect` and
`POST /api/v1/lab/results/{id}`, `/verify`, `/correct` and reads two kinds of fact:

- **XCH-04 routing facts** — the immutable `TriggerEvent`s held by the injected report kernel,
  read through `LabResultService.events(ORG_A)` and filtered by report identifier (the kernel's
  event list is cumulative for the Spring context, so each test uses its own accession/id).
  This is an HTTP-driven assertion on in-memory kernel facts, not a claim that a route is
  HTTP-delivered or persisted; no controller or outbox consumes these events today.
- **XCH-05 alert** — the durable `public.critical_result` row, counted with
  `SELECT count(*) FROM public.critical_result WHERE source_id = ?` with `app.current_org`
  bound to ORG_A, because the migration owner is itself subject to FORCE RLS.

Active test methods:

- `verifyingACriticalRevisionEmitsIssueAndCriticalTriggers` — a preliminary critical report
  routes nothing and has no alert; verification by a different subject returns `final` and emits
  exactly one `RESULT_ISSUED` fact with owner `clinician-a`, queue `critical-queue`, report
  version 2 and the matched patient; the durable XCH-05 alert count becomes 1.
- `correctingAnIssuedRevisionEmitsCorrectedTriggerWithoutDuplicateAlert` — correction returns
  `corrected` at version 3 with a `RESULT_CORRECTED` fact whose `eventId()` differs from the
  issued one, and the XCH-05 alert count stays 1 (the workflow is started on first issuance).
- `preliminaryRevisionEmitsNoTrigger` — a captured-only revision emits no fact and starts no
  alert, proving that only issued revisions trigger XCH-04/05.
- `receptionCannotIssue` — a `reception` actor is refused verification (403) and creates no fact
  or alert.
- `otherTenantCannotTriggerOnThisReport` — an ORG_B verifier cannot verify an ORG_A report
  (404); neither tenant records a fact for it and no alert is created.
- `staleVersionCannotReissue` — replaying verification at an old version is refused (412,
  `STALE_VERSION`) and adds neither a fact nor an alert.
- `entererCannotSelfVerify` — the enterer cannot verify their own report (422,
  `SELF_VERIFICATION`) and creates no fact or alert.

Production classes exercised through those routes: `LabResultEntryController` (thin HTTP
adapter), `ResultEntryService` (validates labels, delegates revision transitions and registers
the durable XCH-05 alert on the first verification only), `LabResultService` (append-only kernel
that emits `RESULT_ISSUED`/`RESULT_CORRECTED`/`RESULT_WITHDRAWN` facts and exposes `events()`),
`TriggerEvent` and `LabTypes.TriggerType` (immutable facts with a stable dedup identity),
`ResultConfiguration` (wires the injectable kernel bean) and `CriticalResultService`
(starts the durable alert). Storage is `db/migrations/V23__critical_results.sql`
(`critical_result` and `critical_result_event` with tenant policies and FORCE RLS).

Entry point to test trace: `POST /api/v1/lab/results/{id}` → `LabResultEntryController.enter` →
`ResultEntryService.enter` → `LabResultService.capture`; `.../verify` and `.../correct` →
`LabResultEntryController.verify/correct` → `ResultEntryService.decide` →
`LabResultService.verify/correct` → `emit` → `LabResultService.events(ORG_A)` asserted in
`verifyingACriticalRevisionEmitsIssueAndCriticalTriggers` and
`correctingAnIssuedRevisionEmitsCorrectedTriggerWithoutDuplicateAlert`; `ResultEntryService.decide`
→ `CriticalResultService.register` → `public.critical_result` asserted by `criticalCount` in the
same tests.

Local verification (tool evidence):

```
AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=TriggerXchRelevantIssuedRevisionsTest test
```

Surefire report: `tests="7" errors="0" skipped="0" failures="0"`.

This is a test-only and documentation-only change: no production line, branch, method or class
was added, so no new coverage obligation or JaCoCo gate applies to this pull request beyond the
recorded green test run; the full JaCoCo gate remains integrator-owned. No test was skipped and
no threshold, exclusion or suppression was changed.

"Proven by tests" is limited to the trigger behaviour of this clause — creating XCH-04 routing
facts and starting the durable XCH-05 alert on relevant issued revisions, and refusing them for
preliminary, unauthorised, cross-tenant and stale attempts. It does not claim that the downstream
XCH-04/XCH-05 delivery or clinical workflow is complete; those are separate requirement IDs with
their own stories.

## Gap

The trigger itself is built and proven. The following are out of scope and pre-existing, and are
listed so the status is not read as full XCH-04/XCH-05 delivery:

1. **No persisted or delivered XCH-04 route.** `LabResultService.events()` is an in-memory,
   tenant-keyed fact list with no controller, migration or outbox consumer (its own Javadoc states
   "its events are not a delivery channel"). Persisting and replaying the `RESULT_ISSUED` and
   `RESULT_CORRECTED` facts would add an outbox table with RLS and FORCE RLS, a publisher worker
   and recipient/delivery records: **one ARC-05 outbox task plus one XCH-04 delivery task**.
2. **No in-memory `CRITICAL_RESULT` event from the HTTP bean.** `ResultConfiguration` builds the
   wired kernel with `new LabPolicyCatalog(List.of(), List.of())`, so `LabResultService.evaluate()`
   finds no approved `CriticalPolicy` and `emitCritical()` emits no in-memory `CRITICAL_RESULT`
   `TriggerEvent`. The critical flag is derived at the trust boundary by
   `ResultEntryService.flag()` from the command's `criticalLow`/`criticalHigh`; XCH-05 is therefore
   represented by the durable `critical_result` row and its `REGISTERED` `critical_result_event`,
   which the tests assert. Seeding the wired policy catalog from approved configuration is a
   **separate ADM-01 policy-configuration task**.
3. **Downstream XCH-04/XCH-05 workflow.** Reconciliation of unmatched/conflicting reports,
   prior-recipient notification and preserved review state (XCH-04), and the alert's attempts,
   recipient, read-back, acknowledgment, follow-up as separate events, overdue reporting, roster
   gaps, failed-notification reporting and backup contact channels (XCH-05) are separate
   requirement IDs with their own stories. This task proves only that issuing or correcting a
   relevant revision triggers them.

This evidence task added only a test class and this document; it changed no production code,
existing test, contract, migration or dependency.
