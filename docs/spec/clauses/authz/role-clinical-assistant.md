# IAM-01 / CLN-02 — Clinical assistant role row

## Clause

Role Clinical assistant: allowed workflow: Intake, vitals, reconciliation preparation and queue handoff; scope constraint: Assigned encounters or care team.

## Status

Partly proven

## Evidence

The characterization tests are in `apps/api/src/test/java/com/aurevia/aurevia/authz/clauses/RoleClinicalAssistantTest.java`. They use synthetic UUIDs, a fixed clock and tenant context, mocked `WorklistRepository`, `SchedulingRepository`, `AppointmentService` and `AuditAppender`, an inline `TransactionTemplate` and the real `WorklistPolicy` and `ChartActionPolicy`. The request path they model is `ClinicController` `GET /api/v1/clinic/worklist`, `POST /api/v1/clinic/worklist/intake` and `POST /api/v1/clinic/worklist/{id}/handoff` into `CheckInService`, then the action policy and the audit appender. HTTP coverage of those routes is already in `apps/api/src/test/java/com/aurevia/aurevia/it/schedulingcheckin/ClinicControllerTest.java` and `CheckInWorklistIntegrationTest.java`; this class is the clause-focused proof and does not duplicate them.

Verbs granted to the assistant and proved green:

- `allowsEachPermittedVerbForClinicalAssistant` — the real `WorklistPolicy` grants `WORKLIST_READ`, `VITALS_RECORD`, `HANDOFF` and `INTAKE_LINK` to `clinical_assistant`.
- `worklistReadReachesCheckInServiceForClinicalAssistant` — `CheckInService.worklist` returns the facility worklist and asserts a `WORKLIST_READ` / `SUCCESS` audit append.
- `intakeLinkReachesCheckInServiceForClinicalAssistant` — `CheckInService.linkIntake` creates a walk-in entry and asserts an `INTAKE_LINK` / `SUCCESS` audit append.
- `handoffReachesCheckInServiceForClinicalAssistant` — `CheckInService.handoff` transfers an item currently held by the caller and asserts a `HANDOFF` / `SUCCESS` audit append.
- `chartPolicyAllowsAssistantToCreateVitalsEntryButNotVerifyIt` — `ChartActionPolicy` grants `CHART_ENTRY_CREATE` to the assistant but withholds `CHART_ENTRY_VERIFY`. Vitals are recorded in the CLN-03 chart slice: `WorklistPolicy` has a `VITALS_RECORD` key but `CheckInService` has no vitals command, so this verb is proven at policy level only.

Negative and narrowing guards proved green (generic IAM-01/workflow guards, **not** proof of this row's scope constraint):

- `refusesOtherRolesVerbsAndUnknownRoles` — the assistant is refused `ENCOUNTER_OPEN`, `IDENTITY_VERIFY`, `CHECK_IN` and `WORKLIST_ASSIGN`; reception, clinician, tenant_admin, privacy_auditor, a missing role, an unknown role and an unknown action are each refused at least three verbs. All `WorklistPolicy` switch arms and the default are exercised.
- `assistantCannotAssignWorklistItemThroughService` — `CheckInService.assign` denies before reading the item and asserts a `WORKLIST_ASSIGN` / `DENIED` audit append.
- `deniesWhenMembershipIsInactive` — `CheckInService.authorize` refuses a worklist read with HTTP 403 `FORBIDDEN` and asserts a `DENIED` audit append when active membership is absent.
- `handoffRefusesAnotherFacility` and `intakeRefusesAnotherFacility` — `CheckInService.facilityDenial` refuses a resource in another facility with HTTP 403 `FORBIDDEN` and asserts a `DENIED` audit append. This is the *facility* narrowing of the Reception row, implemented via `WorklistRepository.outsideAssignedFacilities` and `public.is_facility_out_of_scope`; it is not the "assigned encounters or care team" constraint of this row.
- `handoffRefusesAnUnassignedItem` — HTTP 409 `NO_ACTIVE_ASSIGNEE` when the item has no current assignee; an item-state guard, not a caller-relationship check.
- `handoffRefusesStaleVersion` — HTTP 412 `STALE_VERSION` when the expected version does not match; an optimistic-concurrency guard, not a scope check.

Production classes exercised by this test: `com.aurevia.aurevia.scheduling.checkin.WorklistPolicy`, `CheckInService`, `WorklistRepository` and `com.aurevia.aurevia.chart.record.ChartActionPolicy`. `ClinicController` is the modeled HTTP entry point, exercised by the separate integration suites named above rather than instantiated here. The clinical-assistant permission is a role-to-action decision only: `WorklistPolicy.allows(String, String)` takes no encounter, assignee or care-team input.

## Gap

Two parts of the row are not implemented, so no passing assertion is written for them and no test is skipped. Each would need its own production story; the tests below would be written red first in those stories.

1. **Reconciliation preparation has no action or entry point for this role.** `WorklistPolicy` has no reconciliation key, `CheckInService` has no reconciliation command and `ClinicController` has no route. `com.aurevia.aurevia.exchange.result.ResultActionPolicy` limits `RECONCILIATION_READ` to lab_verifier, tenant_admin and privacy_auditor and `RECONCILIATION_RESOLVE` to lab_verifier and tenant_admin; `com.aurevia.aurevia.authz.specialty.SpecialtyRoles` has no `CLINICAL_ASSISTANT` key. Would-prove test `allowsAssistantToPrepareReconciliationForAnAssignedEncounter`: with active assistant membership, call a reconciliation-preparation command for an assigned encounter and assert it succeeds with a `SUCCESS` audit row. It would fail today because the action is not granted to the role. Files that would change: the owning policy, a service command and its store, the controller and OpenAPI with a regenerated client, and a migration with RLS if a ledger is added. Estimate: two tasks (domain/persistence slice, then HTTP contract and tests).

2. **"Assigned encounters or care team" is not enforced as a caller constraint.** `CheckInService.handoff` checks that the *current assignee* has active membership and that the target is active, but never compares `context.subject()` with the item's assignee or with a patient care team; `facilityDenial` narrows by facility only, and the worklist repository filters by facility code. Would-prove test `handoffRefusesAnItemAssignedToAnotherCareTeam`: set the item's assignee to another active member, keep the assistant caller in the same facility, attempt the handoff and assert HTTP 403 `FORBIDDEN` with a `DENIED` audit row and no handoff event. It would fail today because the transfer succeeds. A companion `worklistRefusesAnEncounterOutsideTheAssignedCareTeam` would assert that a cross-team encounter is absent from the assistant's worklist. Files that would change: a care-team membership source and store, the authorization/command paths in `CheckInService` (or a resource-aware repository predicate), a migration with RLS and FORCE RLS if a care-team table is added, and negative tests. Estimate: two tasks (data model and policy, then service and HTTP enforcement).

Story step 3 asks for these would-prove tests to be added as `@Disabled("GAP: ...")`. That was not done: `scripts/check-constraints.py` rejects every added `@Disabled`/`@Ignore` and a new test file consists entirely of added lines, so it would fail the worker and integrator gates. The tests are specified here instead; this pull request stays green.
