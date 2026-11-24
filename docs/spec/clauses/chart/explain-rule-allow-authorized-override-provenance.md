# CLN-06 clause 2 — explain the rule and allow authorized override with provenance

## Clause

Explain the rule and allow authorized override with provenance.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.chart.clauses.ExplainRuleAllowAuthorizedOverrideProvenanceTest`
(`apps/api/src/test/java/com/aurevia/chart/clauses/ExplainRuleAllowAuthorizedOverrideProvenanceTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with synthetic subjects and a synthetic patient:

- `suggestionExplainsRuleAndAuthorizedOverrideRecordsProvenanceThroughHttp`
  (rule met) — a reception member suggests a `FOLLOW` visit. The response reports
  the explanatory `ruleExplanation` (the approved 14-day interval and the config
  key `followup.interval.follow`), `basis: CONFIGURED_INTERVAL`,
  `intervalDays: 14` and `suggestedDate: 2027-03-20`, with no booking and no
  override. An authorized override with a reason returns version 2, the
  replacement date, and `override.actor`, `override.reason`,
  `override.recordedAt`, `override.originalDate` and
  `override.replacementDate`; the rule explanation is extended with the actor and
  reason, while the recorded instant is asserted on the override record. A direct
  SQL read under forced row-level security confirms the persisted
  `rule_explanation` and all five `override_*` columns, so the provenance survives
  the request boundary.
- `overrideWithoutAReasonIsRefusedAndLeavesNoProvenance` (negative) — a blank
  reason is refused (422, `INVALID_REASON`); the row stays at version 1 with no
  override actor or reason and the original suggested date.
- `clinicianWhoMayReadCannotOverrideSuggestionWithoutReschedulePermission`
  (negative) — a clinician can create and read the suggestion
  (`APPOINTMENT_READ`, `GET /api/v1/follow-ups/{id}/tasks` returns 200) but is
  refused the override (403, `FORBIDDEN`) because the bounded policy withholds
  `APPOINTMENT_RESCHEDULE`; no override is recorded.
- `peerTenantCannotOverrideAnotherTenantsSuggestion` (negative) — a member of a
  second tenant that shares the local facility and visit-type codes is refused
  (404, `NOT_FOUND`) and cannot see or alter the first tenant's suggestion.
- `confirmedSuggestionCannotBeOverridden` (negative) — after explicit
  confirmation with the current `If-Match` version creates one booking, an
  override is refused (409, `ALREADY_CONFIRMED`) and the confirmed row keeps no
  override provenance.
- `suggestionCannotBeOverriddenTwice` (negative) — after one successful
  override (version 2), a second override is refused (409,
  `ALREADY_OVERRIDDEN`) and the replacement date is unchanged.

Production classes exercised through `POST /api/v1/follow-ups/suggest`,
`POST /api/v1/follow-ups/{id}/override` (and `confirm` and `GET .../{id}/tasks`
for the terminal-state and read preconditions):

- `com.aurevia.scheduling.followup.FollowUpController` — the thin
  HTTP adapter that binds the command bodies and never accepts the organization,
  actor or rule from the request.
- `com.aurevia.scheduling.followup.FollowUpService` — builds the rule
  explanation from the approved interval or applicable prior follow-up, and
  records the override actor, reason and instant from the authenticated context.
- `com.aurevia.scheduling.followup.FollowUpSuggestion` and
  `com.aurevia.scheduling.followup.FollowUpOverride` — the immutable
  suggestion and its append-only provenance, including the extended explanation.
- `com.aurevia.scheduling.followup.FollowUpCommandService` — the
  tenant-guarded transaction boundary that commits the suggestion and audit facts.
- `com.aurevia.authz.ActionPolicy` — the bounded matrix that grants
  `APPOINTMENT_RESCHEDULE` to reception and tenant_admin but not to clinician.
- `com.aurevia.scheduling.followup.JdbcFollowUpStore` — the
  tenant-scoped JDBC boundary that persists the `override_*` columns on
  `public.follow_up_suggestion` in `db/migrations/V74__follow_up_suggestions.sql` (forced row
  level security).

Corroborating suite already on `main` (not re-tested here):
`com.aurevia.scheduling.followup.it.FollowUpHttpIntegrationTest`
(`overrideKeepsProvenanceAndOnlyExplicitConfirmationBooks`,
`clinicianCannotConfirmBecauseBookingIsNotTheirAction`,
`peerTenantCannotReadAnotherTenantsSuggestion`) covers the positive,
reception-actor and peer-tenant outcomes at the same entry points. This test is
new in its clinician override denial, the direct persisted `override_*` column
assertion, and the two terminal-state override refusals.

Verified locally: `AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=ExplainRuleAllowAuthorizedOverrideProvenanceTest test` —
`tests="6" errors="0" skipped="0" failures="0"` in
`apps/api/target/surefire-reports/TEST-com.aurevia.chart.clauses.ExplainRuleAllowAuthorizedOverrideProvenanceTest.xml`,
with PostgreSQL on 127.0.0.1:5432 as the prerequisite. "Proven by tests" is
limited to the clause behaviour above; it does not claim that the follow-up
routes or provenance fields are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the committed
OpenAPI contract has no `/api/v1/follow-ups/**` paths (they are already listed in
`contracts/openapi/missing-routes.txt`) and no follow-up-specific `ruleExplanation`
field or follow-up `override` provenance schema. The contract's `OverrideRecord`
belongs to the medication-draft domain, not follow-ups. Documenting them would
change `contracts/openapi.yaml`, the generated
staff-web client and the contract tests — one separate ARC-04/WEB-05 contract
task.
