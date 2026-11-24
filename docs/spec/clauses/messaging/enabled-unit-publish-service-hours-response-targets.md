# MSG-06 clause 1 — enabled unit publishes service hours, response targets, topics and urgent instructions

## Clause

Each enabled unit must publish service hours, response targets, appropriate topics and urgent contact instructions.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.messaging.clauses.EnabledUnitPublishServiceHoursResponseTargetsTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/EnabledUnitPublishServiceHoursResponseTargetsTest.java`).
It is a synthetic unit acceptance test following the `messaging.service` fixture style:
`InMemoryServiceProfileStore`, `ServiceProfileService` with a fixed clock
(`Clock.fixed(2026-11-19T12:00:00Z, UTC)`), `ServiceProfileActions`, `TenantContext` set
and cleared per test, and synthetic UUID and staff-subject constants only.

Test methods (one positive rule proof and four refusals):

- `enabledUnitPublishesHoursTargetsTopicsAndUrgentInstructions` — a `tenant_admin`
  publishes a fresh unit with a UTC zone, Monday and Wednesday windows, a 30-minute
  response target, the topics `collection` and `results`, English and Arabic urgent
  instructions, urgent/downtime/escalation channels, a named queue owner and backup,
  and a past effective date. A different named `tenant_admin` approves version 1.
  The served profile is asserted to be that exact version and to round-trip the zone,
  both windows, the response target (including its computed `dueAt` deadline), the
  topics, both localized instructions, the channels and staffing fields, and
  `enablesUnitAt(NOW) == true`. `PROFILE_PUBLISH` and `PROFILE_VIEW` success audits
  are asserted, proving the protected publish and serve paths ran.
- `publishIsRefusedForWrongRole` — an active patient has view permission but not
  publish permission; the publish is refused (403 `FORBIDDEN`).
- `publishIsRefusedWithStaleVersion` — after a version-0 publish, replaying the same
  precondition is refused (412 `STALE_VERSION`) rather than overwriting the draft.
- `publishIsRefusedForAnotherTenantUnit` — an authorized publisher in one tenant
  cannot publish a unit registered only in another tenant (404 `NOT_FOUND`), so the
  clause cannot be used to reach across a tenant boundary.
- `publishIsRefusedWithoutTopicsOrUrgentInstructions` — a publish with no topics or
  no urgent instructions is refused at the validation boundary (422 `INVALID_TOPICS`,
  `INVALID_URGENTINSTRUCTIONS`). This proves the "must publish" obligation directly,
  not merely the governance around it.

Production classes the new tests exercise:

- Domain service: `com.aurevia.messaging.service.ServiceProfileService`
  (`publish`, `approve`, `viewProfile`, `authorize`, `requireUnitAccess`).
- Value contract: `com.aurevia.messaging.service.ServiceProfileContract`
  (`ServiceProfile.enablesUnitAt`, `effectiveAt`, `dueAt`; `ServiceHours`,
  `ServiceWindow`, `LocalizedText`, `PublishProfileRequest`).
- Action matrix and validation: `ServiceProfileActions` (`PROFILE_PUBLISH` is
  `tenant_admin`-only), `ServiceProfileInputs` and
  `com.aurevia.messaging.units.MessagingInputs`.
- HTTP adapter: `com.aurevia.messaging.service.ServiceProfileController`
  (`POST /api/v1/care-units/{unitId}/service-profiles` and
  `GET /api/v1/care-units/{unitId}/service-profile`). These tests call the service
  the controller delegates to; the HTTP entry point itself is corroborated by the
  existing `com.aurevia.it.messaging.ServiceProfileHttpIntegrationTest`
  methods `publishesApprovesAndServesDurableProfileThroughHttp` and
  `deniedSubjectsCannotCrossTenantOrActWithoutAuthority`.
- Durability: `com.aurevia.messaging.service.JdbcServiceProfileStore`
  with forced row-level security and the `public.service_profile` table from
  `db/migrations/V92__service_profiles.sql` (`time_zone`, `windows`,
  `response_target_minutes`, `topics`, `urgent_instructions`, `urgent_channel`,
  `downtime_channel`, `escalation_channel`).

Where the clause is implemented today (survey result): publish validation and the
named-approval rule live in `messaging/service`; all four field groups are persisted
by `V92__service_profiles.sql`; only an approved, effective profile that still names
a queue owner and a backup is served by `viewProfile` via
`ServiceProfile.enablesUnitAt`. The routes are currently baselined in
`contracts/openapi.yaml` rather than declared in the primary OpenAPI
document; promoting them is tracked separately and is not this clause's behaviour.
"Appropriate topics" is enforced here as a validated, non-blank, PHI-free publish
round-trip; a cross-check against an approved topic catalog is not part of the
publish command and is not claimed by this proof.

## Gap

None
