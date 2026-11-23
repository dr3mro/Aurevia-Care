# CLN-06 clause 1 — suggest a follow-up date using configured visit type intervals and applicable prior follow-up

## Clause

Suggest a follow-up date using configured visit type intervals and applicable prior follow-up.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.chart.clauses.SuggestFollowUpDateUsingConfiguredVisitTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/SuggestFollowUpDateUsingConfiguredVisitTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from the
production Flyway migrations, with two synthetic organizations, synthetic subjects,
synthetic patients, an approved interval and two synthetic prior appointments:

- `suggestUsesConfiguredIntervalAndExplainsTheRule` — with an approved
  `followup.interval.followup = 14`, a UTC facility and no prior follow-up,
  `POST /api/v1/follow-ups/suggest` returns 200 with `suggestedDate =
  encounter + 14`, `intervalDays = 14`, `basis = CONFIGURED_INTERVAL`, and an
  explanation naming the config key and "14 days". The suggestion is `confirmed =
  false`, has no `bookingId`, is at `version = 1`, exactly one row exists in
  `public.follow_up_suggestion` for that patient, and the appointment count is
  unchanged — suggestion creates no booking.
- `applicablePriorFollowUpTakesPrecedenceOverConfiguredInterval` — a prior `booked`
  appointment of the same visit type started before the encounter; the response
  uses the prior appointment's local date, sets `intervalDays` to null, sets
  `basis = PRIOR_FOLLOW_UP`, and names both the appointment identifier and "takes
  precedence" together with the configured interval it overrides. No booking is
  created and exactly one suggestion row is stored.
- `priorFollowUpWithoutConfiguredRuleStillExplainsPrecedence` — a prior appointment
  of a visit type with no approved interval still yields 200 with
  `basis = PRIOR_FOLLOW_UP`, and the explanation states that no configured interval
  is approved for that visit type. This covers the no-rule side of the precedence
  explanation.
- `suggestIsRefusedWhenNoIntervalOrPriorExists` — with neither an approved interval
  nor an applicable prior appointment the command returns 422 `NO_FOLLOW_UP_RULE`
  and stores no row.
- `suggestIsRefusedForSubjectWithoutAppointmentReadRole` — a `privacy_auditor`
  subject (which holds only `AUDIT_READ`) is refused 403 `FORBIDDEN` and no row is
  stored.
- `crossTenantSuggestionIsRefused` — an ORG_B `reception` actor targeting the ORG_A
  facility code receives 404 `NOT_FOUND` because the ORG_A facility is invisible
  under forced RLS in the peer organization; no suggestion row is written in either
  organization.
- `missingVisitTypeOrEncounterIsRefused` — an omitted visit type returns 422
  `INVALID_VISIT_TYPE` and an omitted encounter instant returns 422
  `INVALID_ENCOUNTER_AT`; no row is stored.

Production classes exercised through `POST /api/v1/follow-ups/suggest`:
`com.aurevia.smartclinic.scheduling.followup.FollowUpController` (thin HTTP adapter),
`com.aurevia.smartclinic.scheduling.followup.FollowUpCommandService` (tenant-guarded
transaction boundary),
`com.aurevia.smartclinic.scheduling.followup.FollowUpService` (validation,
authorization and rule explanation),
`com.aurevia.smartclinic.scheduling.followup.FollowUpConfiguration` (approved config
fold, facility IANA zone and the non-terminal prior-appointment query),
`com.aurevia.smartclinic.scheduling.followup.FollowUpRule` (configuration key and
interval parsing), `com.aurevia.smartclinic.scheduling.followup.FollowUpAuthorizationGuard`
and `com.aurevia.smartclinic.authz.ActionPolicy` (role re-derivation and
`APPOINTMENT_READ`), `com.aurevia.smartclinic.scheduling.SchedulingRepository`
(the config and facility reads), and
`com.aurevia.smartclinic.scheduling.followup.JdbcFollowUpStore` behind
`FollowUpStore`, persisting to `public.follow_up_suggestion` from
`db/migrations/V74__build_05.sql` (ENABLE + FORCE row-level security, tenant policy
and grants). Success and denial audit events use `FOLLOWUP_SUGGEST` with purpose
`FOLLOW_UP`.

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.smartclinic.it.schedulingfollowup.FollowUpServiceTest`
(service-level suggest branches with fakes, including the daylight-saving case)
and `com.aurevia.smartclinic.scheduling.followup.it.FollowUpHttpIntegrationTest`
(the broader CLN-06 HTTP acceptance for override, explicit confirmation and
care-team tasks).

Verified locally (tool evidence):
`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security
-Dtest=SuggestFollowUpDateUsingConfiguredVisitTest test` —
`tests="7" errors="0" skipped="0" failures="0"` in the Surefire XML report; no test
is disabled or skipped. "Proven by tests" is limited to the clause behaviour above;
it does not claim clinical acceptance, a published OpenAPI contract entry, or that
a patient-ownership check exists in `suggest`.

## Gap

None for the clause behaviour proved above. Out of scope and pre-existing:

- `POST /api/v1/follow-ups/suggest` is absent from the committed
  `contracts/openapi.yaml` and its generated copy. The follow-up routes are already
  baselined in `contracts/openapi/missing-routes.txt` (under the `/api/v1/follow-ups`
  group), so this does not fail the current contract-parity gate. Publishing the
  route, its tag and its request/response schema into the contract and regenerating
  the client would be one separate contract task.
- The cross-tenant negative above proves facility concealment (404), not a
  patient-ownership check: `FollowUpService.suggest` does not look up the supplied
  patient to confirm it belongs to the active organization before storing the
  suggestion. Closing that would require a production authorization change in the
  follow-up slice and belongs to a separate task; this test-only story did not
  change it.
