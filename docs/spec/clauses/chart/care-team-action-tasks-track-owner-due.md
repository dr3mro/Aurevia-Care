# CLN-06 clause 4 — care-team action tasks track owner, due date and outcome

## Clause

Care-team action tasks track owner, due date and outcome.

## Status

Proven by tests

## Evidence

Test class
`com.aurevia.smartclinic.chart.clauses.CareTeamActionTasksTrackOwnerDueTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/CareTeamActionTasksTrackOwnerDueTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants,
a unique database (`smartclinic_cln_care_team_task_it`) and one fresh
`public.follow_up_suggestion` row per tenant per test. The three follow-up task
routes are called through the real controller; clauses 1-3 (interval, override,
booking) are not exercised:

- `createTaskKeepsOwnerAndDueDateAndCompletionRecordsOutcome` — reception opens a
  task through `POST /api/v1/follow-ups/{id}/tasks` and the response keeps the
  accountable `owner` (`nurse-1`), the facility-local `dueDate`
  (`2027-03-18`), the `instruction`, the `createdBy` actor, the `followUpId` and
  the `OPEN` status; `GET /api/v1/follow-ups/{id}/tasks` lists it; a clinician
  closes it through `POST /api/v1/follow-ups/tasks/{taskId}/complete` and the
  response carries the recorded `outcome` while `owner` and `dueDate` stay
  unchanged (200 throughout).
- `clinicianCannotCreateATask` — a clinician has no `APPOINTMENT_BOOK`, so opening
  a task is refused (403 `FORBIDDEN`) and no task row is written for that
  suggestion.
- `receptionCannotRecordAnOutcome` — reception has no `APPOINTMENT_COMPLETE`, so
  closing is refused (403 `FORBIDDEN`) and a scoped HTTP re-read shows the task
  still `OPEN` with a null `outcome`.
- `peerTenantCannotReadOrCompleteAnotherTenantsTask` — the peer clinician can
  list tasks for its own tenant's suggestion (200, empty) but gets 404
  `NOT_FOUND` (concealment, not 403) for both the list read and the completion
  of the owning tenant's task; a scoped database count and an owning-actor
  re-read show the task still `OPEN` with its original owner and due date.
- `createTaskRejectsABlankOwnerWithoutWritingARow` — a blank owner is refused
  (422 `INVALID_OWNER`) and the scoped task count stays zero.
- `createTaskRejectsAMissingDueDateWithoutWritingARow` — a missing due date is
  refused (422 `INVALID_DUE_DATE`) and the scoped task count stays zero.
- `completeTaskRejectsABlankOutcomeWithoutClosingTheTask` — a blank outcome is
  refused (422 `INVALID_OUTCOME`) and the task stays `OPEN` with a null outcome.
- `secondCompletionIsRejectedAndKeepsTheFirstOutcome` — a second completion is
  refused (409 `ALREADY_COMPLETED`) and the first outcome, owner and due date
  survive unchanged.

Production classes exercised by these entry points:

- `com.aurevia.smartclinic.web` is not involved: the routes belong to
  `com.aurevia.smartclinic.scheduling.followup.FollowUpController`, whose thin
  `createTask`, `completeTask` and `tasks` methods delegate to
  `FollowUpCommandService` and `FollowUpService`.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpService` — validates the
  owner, due date and outcome (`text` / `date` / `uuid` validators), authorizes
  `APPOINTMENT_BOOK` for creation, `APPOINTMENT_COMPLETE` for completion and
  `APPOINTMENT_READ` for listing through `ActionPolicy`, conceals another
  tenant's suggestion or task as 404, rejects a closed task as
  `ALREADY_COMPLETED`, audits every allow/deny/conflict and keeps the original
  owner and due date when `com.aurevia.smartclinic.scheduling.followup.CareTeamTask.complete`
  records the outcome.
- `com.aurevia.smartclinic.scheduling.followup.JdbcFollowUpStore` behind
  `FollowUpStore` — writes and reads the durable task under the caller's tenant.
- `db/migrations/V74__build_05.sql` — `public.care_team_task` with `owner`,
  `due_date`, `status IN ('OPEN', 'COMPLETED')` and `outcome`, protected by
  `ENABLE` / `FORCE ROW LEVEL SECURITY` and a tenant-isolation policy.

Discovery survey (files read while locating today's implementation): the chart
main package (`apps/api/src/main/java/com/aurevia/smartclinic/chart/record`) and
the controllers under `apps/api/src/main/java/com/aurevia/smartclinic/web` hold
no care-team task code; the implementation lives in
`apps/api/src/main/java/com/aurevia/smartclinic/scheduling/followup` and
`db/migrations/V74__build_05.sql`. The clause's behaviour is built; a targeted
grep found no duplicate implementation or second test home. `chart/clauses` is
only the mandated clause-test home.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=CareTeamActionTasksTrackOwnerDueTest test`
— Surefire report `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None for the clause behaviour: owner, due date and outcome are tracked and
enforced at the HTTP boundary. The three follow-up task routes are currently
baselined as undocumented in
`contracts/openapi/missing-routes.txt` (the task routes at lines 48, 215 and
218; line 214 is the separate `POST /suggest` route, also out of scope); adding
their entries to `contracts/openapi.yaml` and regenerating
`contracts/openapi/generated/openapi.yaml` and
`contracts/openapi/generated/client.ts` is one separate contract task and is
out of scope for this test-only story.
