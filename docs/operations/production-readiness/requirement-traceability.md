# PRD-level requirement traceability

This is the product-level companion to the WEB-level [evidence register](evidence.md).
The [readiness register](README.md) and the [launch checklist](launch-checklist.md)
cover the WEB readiness outcomes (WEB-01 to WEB-13) and the owner-gated launch
items; neither gives the G5 reviewer a view of the six product outcomes in
section 1 of the [SmartClinic Cloud V1 specification](../../../.opencode/config/PROJECT_V1_SPEC.md).
This table supplies that view. Paths are relative to the repository root.

A test is named only when it exists in this checkout and asserts behavior; a
requirement ID in a Javadoc or comment is not proof. Status words here are
`built`, `partly built` and `decision needed from a person`. They are deliberate
and are not the four words of `evidence.md`: `partly built` means a named test
asserts part of the outcome but a code, contract or human-gated gap recorded in
the clause note remains. `decision needed from a person` is reserved for a row
whose only remaining work is a human decision; no row qualifies today because
each also has a repository-testable gap. The row status is a rollup, not a claim
about every clause: the four boundary clause notes for partner-approval,
proxy-record requests, claims/payer exclusion and market policy pack say
`Not built`, while the main workflow clause notes say `Partly built`. A row
marked `partly built` therefore always has an open boundary, named below.
No row may be changed to `built` or `decision needed from a person` without an
authoritative clause `Status` heading and the concrete blocking gap from that
clause's `Gap` section; complete tests alone are never enough.

`Main test` names the focused unit or domain test; `Acceptance test` names the
HTTP or flow-level test. Both cells are an existing test file with behavioral
assertions, never a blank, a repeated file or a Javadoc/ID mention.

## Not built

Every row below is `partly built`; none is `built`. No row is advanced to signed,
done or accepted by this document, and no throwaway test was written to force a
row to `built`. The main gap for each, with its authoritative clause note, is:

- **PRD-01** — the chart core now has a production Flyway migration with forced
  RLS and published OpenAPI routes, but registration, booking, check-in,
  documentation and follow-up are still read through separate workflows rather
  than one per-patient-per-organization chart projection (notes and documents are
  still empty in the staff-web chart view); the clause note predates that
  migration and contract work
  ([clause note](../../spec/clauses/product/longitudinal-chart-patient-organization-covering-registration-booking.md)).
- **PRD-02** — partner interface approval, manual delivery and verification
  evidence, conformance reports and a conducted outage drill are owner-gated
  ([clause note](../../spec/clauses/product/closed-loop-orders-diagnostic-results-specialty-referrals.md)).
- **PRD-03** — patient command wiring, notification preferences, verified-adult
  eligibility and synthetic production ports are open code and contract gaps;
  identity-proofing and unit evidence are owner-gated
  ([clause note](../../spec/clauses/product/verified-adult-patients-see-appointments-released-reports.md)).
- **PRD-04** — the staff-web finance journey and browser acceptance are absent,
  the generated contract advertises `/api/v1/invoices` where the controller serves
  `/api/v1/finance/invoices`, and the G4 and G5 acceptance packs are open
  ([clause note](../../spec/clauses/product/track-self-pay-invoices-payments-refunds-receipts.md)).
- **PRD-05** — runtime tenant export and `/health/metrics` are now production
  code with migrations, controllers and HTTP integration tests; the conducted
  restore drill (OPS-04), market policy pack, Arabic production evidence and a
  signed market decision are still owner-gated; the clause note predates the
  export and metrics implementation
  ([clause note](../../spec/clauses/product/support-onboarding-export-monitoring-recoverability-english-arabic.md)).
- **PRD-06** — `tenant_admin` may still authorize a release pending a signed
  clinical decision; G5 clinical sign-off, contract parity for note signing and
  prescription attestation, and assurance for the exclusions are open
  ([clause note](../../spec/clauses/product/clinical-users-decide-attest-diagnosis-treatment-release.md)).

## Product outcome traceability

| Requirement | Module | Main test | Acceptance test | Status |
|---|---|---|---|---|
| PRD-01 | `apps/api` patient, scheduling, chart.record, clinical.notes, scheduling.followup; `apps/staff-web` features/chart | `apps/api/src/test/java/com/aurevia/it/chart/record/ChartRecordIntegrationTest.java` — HTTP chart read and write, provenance and cross-tenant concealment | `apps/api/src/test/java/com/aurevia/acceptance/scenarios/FirstImplementationSliceAcceptanceTest.java` — one patient from registration and booking through check-in, signed order, verification and approved release; `apps/api/src/test/java/com/aurevia/scheduling/followup/it/FollowUpHttpIntegrationTest.java` — follow-up HTTP flow | partly built |
| PRD-02 | `apps/api` exchange, orderentry, labflow, prescription, radiology, therapy; `apps/staff-web` features/partnerboard | `apps/api/src/test/java/com/aurevia/orderentry/delivery/OrderDeliveryCoordinatorTest.java` — delivery state transitions and channel routing | `apps/api/src/test/java/com/aurevia/journey/loop/LaboratoryJourneyLoopTest.java` — closed-loop lab journey; `apps/api/src/test/java/com/aurevia/labflow/outage/OutageIntegrationTest.java` — downtime manual evidence | partly built |
| PRD-03 | `apps/api` messaging, patient identity, exchange.release; `apps/patient-mobile` features/appointments, notifications | `apps/api/src/test/java/com/aurevia/it/messagingenrollment/PatientAccessTest.java` — patient enrollment, access grants and tenant isolation | `apps/api/src/test/java/com/aurevia/messaging/appointments/PatientAppointmentControllerHttpTest.java` — full appointment workflow over HTTP; `apps/patient-mobile/src/features/appointments/deviceClient.test.ts` — device registration and revocation | partly built |
| PRD-04 | `apps/api` finance | `apps/api/src/test/java/com/aurevia/finance/FinanceLedgerTest.java` — fixed-precision money, immutable ledger entries and numbered receipts | `apps/api/src/test/java/com/aurevia/acceptance/specialty/FinanceLedgerReplayAcceptanceTest.java` — payment and refund replay is a no-op, reference reuse with a new payload conflicts; `apps/api/src/test/java/com/aurevia/it/finance/FinanceFlowTest.java` — full invoice lifecycle | partly built |
| PRD-05 | `apps/api` catalog.dictionary, tenant.importing, tenant.export, observability; `apps/staff-web` i18n; `infra` evidence evaluators | `apps/api/src/test/java/com/aurevia/it/catalog/MixedScriptSupportTest.java` — mixed-script content, units, UTC and IANA zones across DST; `apps/api/src/test/java/com/aurevia/it/catalog/LocalizationTest.java` — equivalent localization assertions | `apps/api/src/test/java/com/aurevia/tenant/importing/ImportControllerTest.java` — HTTP dry run with counts and unsupported fields; `apps/api/src/test/java/com/aurevia/tenant/importing/OnboardingImportServiceTest.java` — deterministic, no-commit imports; `apps/staff-web/src/i18n/I18nProvider.direction.test.tsx` — Arabic RTL direction | partly built |
| PRD-06 | `apps/api` it.authz, clinical.notes, exchange.release, prescription, radiology, therapy | `apps/api/src/test/java/com/aurevia/it/authz/SpecialtyPolicyTest.java` — attestation reserved for clinical decision roles | `apps/api/src/test/java/com/aurevia/it/authz/SpecialtyAuthorizationFlowTest.java` — HTTP attestation flow with derived decision and audit rows; `apps/api/src/test/java/com/aurevia/it/authz/SpecialtyHttpSecurityTest.java` — HTTP denial for wrong roles | partly built |

## How this table was assembled

The gap was reproduced with
`grep -rn "PRD-0[1-6]" docs/operations/production-readiness`, which found no
PRD mention in the three readiness documents. Test names came from the clause
notes under `docs/spec/clauses/product/`. Every path in both test columns was then
tool-verified: each file exists and contains named test methods with behavioral
assertions, so no cell is an ID comment, a Javadoc mention or an assertion-free
call. `check-docs.py` validates Markdown links, IDs and table shape but not bare
test paths in table cells, so this file-existence and assertion check is recorded
explicitly. Clause notes are historical evidence notes and can predate later
implementation, so each "Not built" gap was reconciled against this checkout
before it was written, including the production chart migration and published
routes for PRD-01 and the export controller, JDBC store and metrics route for
PRD-05. `ClinicalCatalogAcceptanceTest` is deliberately not cited: it builds
its services with the test-only no-argument constructor, so it asserts the
synthetic domain kernel rather than the production-wired path. Owner evidence in
the [launch checklist](launch-checklist.md) remains open: this document reports
gaps and never substitutes a simulation for a person's sign-off.
