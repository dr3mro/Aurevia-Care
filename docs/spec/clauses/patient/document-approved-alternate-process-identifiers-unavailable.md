# IAM-03 clause 6 — API test evidence

## Clause

Document the approved alternate process when identifiers are unavailable.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.patient.clauses.DocumentApprovedAlternateProcessIdentifiersUnavailableTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/DocumentApprovedAlternateProcessIdentifiersUnavailableTest.java`).
All four tests are active (none are disabled) and pass with synthetic fixtures only.

- `documentsAlternateProcessWhenIdentifiersCannotBeConfirmed` — positive proof. Approving a
  consolidation with no confirmed identifiers but a documented alternate process
  (`site_witness`) succeeds, appends the consolidation event, and records the audit outcome
  `CONSOLIDATION_IDENTIFIER_CONFIRM` / `ALTERNATE:site_witness`, so the basis actually relied on
  is durable.
- `refusesAlternateProcessThatIsNotABoundedCodedReference` — negative proof. An unbounded value
  (`!!`) is refused with HTTP 422 `INVALID_ALTERNATE_PROCESS`, a `DENIED` confirmation audit is
  written, and no chart association or consolidation event is appended.
- `refusesAlternateProcessCombinedWithConfirmedIdentifiers` — negative proof. A caller cannot pad
  a weak claim with an escape hatch: supplying both two confirmed identifiers and an alternate
  process is refused with HTTP 422 `CONFLICTING_CONFIRMATION` and nothing is appended.
- `refusesConsolidationWhenIdentifiersAreUnavailableAndNoAlternateProcessIsDocumented` — negative
  proof of the rule itself. With an empty identifier list and no alternate process the approval is
  refused with HTTP 422 `IDENTIFIER_CONFIRMATION_REQUIRED`, so an undocumented absence of
  identifiers cannot pass a high-risk transition.

Production classes exercised through the normal consolidation command path:

- `com.aurevia.smartclinic.patient.consolidation.ConsolidationService` — `consolidate(...)` calls
  `confirmationFailure(...)` and, on a valid confirmation, appends
  `CONSOLIDATION_IDENTIFIER_CONFIRM` with `confirmationOutcome(...)` (`ALTERNATE:<code>` or
  `CONFIRMED:<...>`). `confirmationFailure(...)` enforces mutual exclusivity, the bounded
  `[a-z_]{2,32}` alternate-process code, and the two-identifier requirement; its caller
  `consolidate(...)` records `DENIED` on every refusal.
- `com.aurevia.smartclinic.patient.consolidation.ConsolidationController` — `ConsolidationApprovalRequest`
  binds `confirmedIdentifiers` and `alternateProcess` from the HTTP request and freezes the list;
  `consolidate(...)` passes them to the service and organization and actor are never client input.
- `com.aurevia.smartclinic.patient.consolidation.DuplicateRepository` and `DuplicatePolicy` — the
  RLS-bounded append boundary and the identity-administration action policy used by the command.

Source survey (not changed by this task): the alternate-process behaviour lives in the chart
consolidation path under `apps/api/src/main/java/com/aurevia/smartclinic/patient/consolidation`.
The HTTP entry is `POST /api/v1/duplicate-reviews/{reviewId}/consolidate`, mapped by
`ConsolidationController` (it is currently listed in `contracts/openapi.yaml`,
not documented in `contracts/openapi.yaml`); the append-only `consolidation`, `chart_association`
and `audit_event` tables are created by the Flyway migrations under `db/migrations`. Neighbouring tests that
already exercise this behaviour are `it/patientidentity/ConsolidationServiceTest` (unit) and
`it/patientidentity/ConsolidationIsolationTest` (HTTP/tenant isolation). No separate approval
catalog is used: a caller names the site-approved process as a bounded coded reference and the
code recorded in the audit trail is the documentation of which process was used.

## Gap

None for IAM-03 clause 6: the rule is built and proven by active tests at the consolidation
command. This task added proof only; it did not change production code, an existing test, the
OpenAPI contract or a migration. The specific catalog of site-approved alternate processes (and
who authorizes a code) is a site policy concern documented in the launch policy pack, not an
application rule in this clause.

Separate pre-existing debt, outside this clause's scope and not changed by this task: the
`POST /api/v1/duplicate-reviews/{reviewId}/consolidate` route is mapped by `ConsolidationController`
but listed in `contracts/openapi.yaml` rather than documented in
`contracts/openapi.yaml`. Closing that needs one contract task (`contracts/openapi.yaml` plus
regenerated client types); it is not missing clause-6 behaviour.
