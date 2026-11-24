# CLN-06 clause 1 — suggest a follow-up date using configured visit type intervals and applicable prior follow-up

## Clause

Suggest a follow-up date using configured visit type intervals and applicable prior follow-up.

## Status

Proven by tests

## Evidence

Active HTTP-level proof (`synthetic-auth` profile, a dedicated PostgreSQL database
migrated from `db/migrations`, synthetic fixtures only). No production code, existing
test, migration or contract was changed by this evidence task.

- Test class: `com.aurevia.chart.clauses.SuggestFollowUpDateUsingConfiguredVisitTest`
  (path: `apps/api/src/test/java/com/aurevia/chart/clauses/SuggestFollowUpDateUsingConfiguredVisitTest.java`).
  It follows the database setup and fixture style of the neighbouring
  `chart.clauses` tests: roles created if absent, a fresh database owned by
  `orevia_migrate`, Flyway migration from `filesystem:../../db/migrations`, a
  synthetic tenant with a `MAIN` facility in `America/New_York`, `NEW` / `FOLLOW` /
  `NOCONF` visit types, approved interval configuration and two synthetic patients.
- `suggestsFollowUpDateFromConfiguredVisitTypeInterval` — positive proof. `POST
  /api/v1/follow-ups/suggest` with visit type `NEW` and an encounter at
  `2026-03-08T04:30:00Z` returns HTTP 200 with `suggestedDate` `2026-03-08`,
  `intervalDays` `1`, `basis` `CONFIGURED_INTERVAL` and a `ruleExplanation` that
  names the interval and the config key. The `America/New_York` fixture crosses a
  daylight-saving change, so the assertion proves civil-day arithmetic rather
  than a 24-hour addition. `confirmed` is false, `bookingId` is null and the
  appointment count is unchanged: a suggestion creates no booking.
- `suggestsApplicablePriorFollowUpDateOverConfiguredInterval` — positive proof. A
  synthetic non-terminal `FOLLOW` appointment on `2027-02-20` is seeded for the
  patient. The same request with visit type `FOLLOW` and an encounter on
  `2027-03-06` returns `basis` `PRIOR_FOLLOW_UP`, `suggestedDate` `2027-02-20`, a
  null `intervalDays` and an explanation that the prior follow-up takes
  precedence over the approved 14-day interval — proving that an applicable
  prior follow-up governs, not the configured interval.
- `refusesSuggestionWhenNoIntervalAndNoPriorFollowUpApply` — negative proof. Visit
  type `NOCONF` has no approved interval and the patient has no prior appointment
  of that type; the request is refused with HTTP 422 `NO_FOLLOW_UP_RULE` and no
  appointment is created. The rule refuses to invent a clinical interval.
- `refusesSuggestionForRoleWithoutAppointmentRead` — negative proof. A synthetic
  `privacy_auditor` member with active membership but no `APPOINTMENT_READ`
  permission is refused with HTTP 403, so the suggestion rule is not reachable by
  a role without the scheduling read action.

Entry-point-to-test trace: `POST /api/v1/follow-ups/suggest` →
`com.aurevia.scheduling.followup.FollowUpController.suggest` →
`com.aurevia.scheduling.followup.FollowUpCommandService` (transaction
boundary) → `com.aurevia.scheduling.followup.FollowUpService.suggest`
(validates input, re-checks membership and `APPOINTMENT_READ`, derives tenant from
`TenantContext`, audits outcome) →
`com.aurevia.scheduling.followup.FollowUpConfiguration` (approved
setting fold, facility IANA zone and applicable prior follow-up from tenant-scoped
SQL) → `com.aurevia.scheduling.followup.FollowUpRule` (bounded interval
parsing) → `com.aurevia.scheduling.followup.FollowUpSuggestion` →
`com.aurevia.scheduling.followup.JdbcFollowUpStore` (tenant-owned
`follow_up_suggestion` table). The test drives every one of these through the HTTP
request; the existing `scheduling.followup.it.FollowUpHttpIntegrationTest` covers
the same domain with a broader fixture.

Production classes exercised: `FollowUpController`, `FollowUpCommandService`,
`FollowUpService`, `FollowUpAuthorizationGuard`, `FollowUpConfiguration`,
`FollowUpRule`, `PriorFollowUp`, `FollowUpSuggestion`, `JdbcFollowUpStore` and
`com.aurevia.authz.ActionPolicy`. The interval is read from the
approved configuration fold seeded into `config_entry` / `config_approval`; the
prior follow-up is read from `public.appointment`; both are enforced by forced
row level security in `db/migrations`.

## Gap

None

The other clauses of CLN-06 (authorized override with provenance, explicit
confirmation creating exactly one booking, and care-team action tasks) are outside
this clause's single sentence and are proven separately in
`apps/api/src/test/java/com/aurevia/scheduling/followup/it/FollowUpHttpIntegrationTest.java`.
No file would change to complete clause 1; it needs no further task.
