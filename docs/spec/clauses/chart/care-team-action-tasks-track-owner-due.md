# CLN-06 clause 4 — care-team action tasks track owner, due date and outcome

## Clause

Care-team action tasks track owner, due date and outcome.

## Status

Proven by tests

## Evidence

Test class
`com.aurevia.smartclinic.chart.clauses.CareTeamActionTasksTrackOwnerDueTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/CareTeamActionTasksTrackOwnerDueTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants that
share facility, visit-type and patient-local identifiers. The test creates a
disposable database from the production Flyway migrations and uses synthetic
subjects only. Every request goes through the normal follow-up entry points
`POST /api/v1/follow-ups/suggest`, `POST /api/v1/follow-ups/{id}/tasks`,
`POST /api/v1/follow-ups/tasks/{taskId}/complete` and
`GET /api/v1/follow-ups/{id}/tasks`.

- `careTeamTaskTracksOwnerDueDateAndOutcome` — positive proof. A task opened by a
  `reception` subject for a synthetic follow-up carries `owner = nurse-1`,
  `dueDate = 2027-03-18`, the instruction, `createdBy = reception-1111`, status
  `OPEN` and no outcome in both the HTTP response and the list response. A
  `clinician` records the outcome `patient confirmed by phone`; the task becomes
  `COMPLETED` while the owner, due date and creator stay unchanged in the
  response, the list and the persisted `public.care_team_task` row (read inside
  the owning tenant under forced row level security).
- `receptionCannotRecordTaskOutcome` — negative proof of the role boundary. A
  well-formed outcome submitted by `reception` is refused (403); the persisted
  task stays `OPEN` with a null outcome. Completion is a clinical action and
  reception may open a task but not close it.
- `clinicianFromPeerTenantCannotReadOrCompleteTask` — negative proof of tenant
  isolation. A clinician of another active organization has the read and complete
  actions, so authorization passes, but listing the suggestion's tasks and
  completing the task both return 404 and the owning tenant's row stays `OPEN`
  with a null outcome.
- `completedTaskCannotBeCompletedAgain` — negative proof of the state rule. A
  second well-formed completion of a closed task is refused (409) and the first
  recorded outcome is preserved on the row.
- `blankOutcomeIsRefused` — negative proof of missing input. A blank outcome is
  refused (422, `INVALID_OUTCOME`); the follow-up task list still shows the task
  `OPEN` with no outcome and the persisted row is unchanged.

Production classes exercised through those entry points:

- `com.aurevia.smartclinic.scheduling.followup.FollowUpController` — thin HTTP
  adapter binding the task request and mapping command outcomes to responses.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpCommandService` — the
  transactional command boundary; `FollowUpService` owns validation, the
  owner/due-date/outcome invariants, role authorization (create needs
  `APPOINTMENT_BOOK`, complete needs `APPOINTMENT_COMPLETE`, list needs
  `APPOINTMENT_READ`) and the `ALREADY_COMPLETED` conflict rule.
- `com.aurevia.smartclinic.scheduling.followup.CareTeamTask` — the task contract
  and its `complete` transition, which preserves the original owner and due date.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpStore` and
  `com.aurevia.smartclinic.scheduling.followup.JdbcFollowUpStore` — tenant-scoped
  insert, find and list of tasks; completion checks the OPEN state before an
  upsert of the task row.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpAuthorizationGuard` and
  `com.aurevia.smartclinic.authz.ActionPolicy` — re-derived membership and the
  role/action matrix that refuses reception completion.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpConfiguration` — the
  approved interval that makes the fixture suggestion deterministic.
- Persistence and isolation are supplied by
  `db/migrations/V74__build_05.sql`, which creates `public.care_team_task` with
  the owner, due-date, status and outcome columns, per-tenant `ENABLE` and
  `FORCE ROW LEVEL SECURITY`, the tenant isolation policy and the runtime grants.

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dgroups=integration -Dtest=CareTeamActionTasksTrackOwnerDueTest test`
— `tests="5" errors="0" skipped="0" failures="0"` in the Surefire XML report.

Survey note: the chart clause tests live under
`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses`, but this clause is
implemented in the neighbouring `com.aurevia.smartclinic.scheduling.followup`
package rather than in `chart.record`. The controllers under
`apps/api/src/main/java/com/aurevia/smartclinic/web` contain no care-team-task
route; the routes are declared on `FollowUpController`. Corroborating evidence
already on `main`:
`com.aurevia.smartclinic.scheduling.followup.it.FollowUpHttpIntegrationTest`
(`careTeamTasksCarryOwnerDueDateAndOutcomeOverHttp`) and the
`com.aurevia.smartclinic.scheduling.followup.FollowUpCommandServiceTest` /
`JdbcFollowUpStoreTest` suites.

## Gap

None for the clause behaviour: owner, due date and outcome are implemented and
proven through their HTTP entry points. Out of scope and pre-existing: the three
follow-up task routes are listed in `contracts/openapi.yaml`
(lines 48 and 214–218) and are not yet published in `contracts/openapi.yaml`.
Documenting them and regenerating the client is a separate contract task, as is
any staff-web UI for assigning or completing care-team tasks; neither was changed
by this evidence-only story.
