# CLN-06 clause 2 — explain the rule and allow authorized override with provenance

## Clause

Explain the rule and allow authorized override with provenance.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.chart.clauses.ExplainRuleAllowAuthorizedOverrideProvenanceTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ExplainRuleAllowAuthorizedOverrideProvenanceTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
HTTP entry points `POST /api/v1/follow-ups/suggest`, `POST /api/v1/follow-ups/{id}/override`
and `POST /api/v1/follow-ups/{id}/confirm` with synthetic fixtures. It seeds two
organizations that share local facility, resource, visit-type and patient-identifier
codes, applies only the production Flyway migrations from `db/migrations`, and uses
a dedicated `smartclinic_cln06_override_it` database.

- `ruleExplanationStatesIntervalAndAuthorizedOverrideKeepsProvenance` (rule met) —
  suggests a `FOLLOW` date from the approved `followup.interval.follow = 14` policy
  and asserts `basis = CONFIGURED_INTERVAL`, `intervalDays = 14`,
  `suggestedDate = 2027-03-20` and an explanation containing both
  `approved interval 14 days` and the config key `followup.interval.follow`. An
  override by the authenticated reception subject with reason `clinician directed`
  returns `suggestedDate = 2027-03-25` and `version = 2`; the response and the
  stored row both carry the actor, reason, recorded instant, original date and
  replacement date, and the explanation retains the original rule and records
  `overridden by reception-1111 ... (was 2027-03-20)`. The suggestion stays
  unconfirmed with no booking, and the appointment count is unchanged.
- `requestBodyCannotSpoofTheRecordedOverrideActor` (negative) — an override body
  that injects `actor`, `subject` and `organizationId` fields still records
  `reception-1111` (the authenticated subject) as the actor in both the response
  and the stored row, proving provenance is header-derived rather than
  payload-derived.
- `blankReasonOverrideIsRefusedAndLeavesSuggestionUnchanged` (negative) — a blank
  reason is refused with HTTP 422 and code `INVALID_REASON`; the row keeps version
  1, has no override provenance and no booking.
- `clinicianCannotOverrideSuggestion` (negative) — a clinician, who lacks
  `APPOINTMENT_RESCHEDULE` in the action policy, is refused with HTTP 403 and code
  `FORBIDDEN`; the suggestion is untouched.
- `peerTenantCannotOverrideAnotherTenantsSuggestion` (negative) — a member of the
  peer tenant, whose identical local identifiers are isolated by forced RLS, gets
  HTTP 404 and code `NOT_FOUND`; the source tenant's row is untouched.
- `secondOverrideIsRefusedAsAlreadyOverridden` (negative) — a second override after
  a successful one is refused with HTTP 409 and code `ALREADY_OVERRIDDEN`; the
  first override's provenance and version remain intact.
- `overrideAfterConfirmationIsRefusedAsAlreadyConfirmed` (negative) — after an
  explicit confirmation creates exactly one booking, an override is refused with
  HTTP 409 and code `ALREADY_CONFIRMED`; the confirmed row and booking are intact.

Production classes and files exercised by these request paths:

- `com.aurevia.smartclinic.scheduling.followup.FollowUpController` — the thin HTTP
  adapter for the suggest, override and confirm routes under `/api/v1/follow-ups`.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpCommandService` — the
  transactional boundary that sets tenant context and invokes the domain service.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpService` — validation,
  `APPOINTMENT_RESCHEDULE` authorization, the `ALREADY_CONFIRMED` and
  `ALREADY_OVERRIDDEN` guards, and derivation of the actor from the authenticated
  subject rather than the request body.
- `com.aurevia.smartclinic.scheduling.followup.FollowUpSuggestion` and
  `com.aurevia.smartclinic.scheduling.followup.FollowUpOverride` — the immutable
  suggestion version and its append-only provenance, including a rule explanation
  that keeps the original wording visible after an override.
- `com.aurevia.smartclinic.scheduling.followup.JdbcFollowUpStore` and
  `com.aurevia.smartclinic.scheduling.followup.FollowUpStore` — the tenant-scoped
  persistence of all `override_*` columns.
- `com.aurevia.smartclinic.authz.ActionPolicy` — the role matrix reserving
  `APPOINTMENT_RESCHEDULE` for reception and tenant administrators.
- `com.aurevia.smartclinic.api.ApiErrorHandler` — the uniform error body carrying
  the stable refusal code.
- `com.aurevia.smartclinic.web.TenantContextInterceptor` — binds the authenticated
  synthetic subject and active organization to the request.
- `db/migrations/V74__build_05.sql` — the `follow_up_suggestion` table with its
  override provenance columns, checks, forced row-level security and grants.

The HTTP routes above are still listed in `contracts/openapi/missing-routes.txt`;
their OpenAPI registration is tracked as separate contract work. This evidence
proves the clause behaviour only, through the real HTTP entry points.

## Gap

None.
