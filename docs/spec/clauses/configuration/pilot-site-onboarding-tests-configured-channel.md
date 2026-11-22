# ADM-01 clause 3 — Pilot site onboarding tests each configured channel

## Clause

Pilot site onboarding tests each configured channel.

## Status

Not built

## Evidence

The would-prove API test is `apps/api/src/test/java/com/aurevia/smartclinic/configuration/clauses/PilotSiteOnboardingTestsConfiguredChannelTest.java`. Every method carries `@Disabled("GAP: ...")` because the behaviour is absent, so the branch stays green and proves nothing about the clause today. The class itself is also `@Disabled("GAP: ...")` so the test container is skipped before Spring starts a context or Flyway touches a database. No red run is possible: there is no surface to call. All four methods are complete, compile-safe and use synthetic fixtures only; a later task enables them by removing the class-level and method-level annotations.

- `onboardingTestsEveryConfiguredChannel` (rule met) — after the primary, backup, urgent and downtime channel keys are drafted and named-approved in the active tenant, a channel-test request returns HTTP 200 with one outcome per configured channel, records each outcome, excludes an approved but unrelated key and a foreign tenant's key, and appends four audit events.
- `onboardingTestRefusesWrongRole` (negative) — the same request by a `reception` membership is refused with HTTP 403, no results are returned and no channel-test audit event is appended.
- `onboardingTestRefusesAnotherTenant` (negative) — a valid subject asserting a foreign organization is refused with HTTP 403, no channel value or organization from the home tenant leaks in the body, and no channel is invoked; a repeat in the home tenant still returns four outcomes.
- `onboardingTestRejectsMissingOrUnapprovedChannelConfig` (negative) — a channel key that was never configured and one that exists only as a pending, unapproved draft are each refused before any channel is invoked (422, no results, no audit event).

The test uses the same synthetic PostgreSQL setup as `apps/api/src/test/java/com/aurevia/smartclinic/it/ConfigurationIsolationTest.java`: Flyway migrations from `db/migrations`, a dedicated database, `X-Synthetic-Subject` and `X-Active-Organization` headers, two seeded organizations (`11111111-1111-1111-1111-111111111111` and `22222222-2222-2222-2222-222222222222`), and draft/approval requests through the existing configuration endpoints. The would-be route `/api/v1/configuration/channel-tests` is a string literal; no Java type refers to it.

Production classes and files surveyed, none of which tests a configured channel at onboarding:

- `com.aurevia.smartclinic.configuration.ConfigurationService` — implements draft, named approval, effective read and append-only history only; its `authorize` checks active membership and role permission, with no channel input or channel invocation.
- `com.aurevia.smartclinic.configuration.ConfigurationRepository`, `ConfigEntry` and `ConfigHistory` — versioned key/value storage and projections; no channel-test result or outcome type.
- `com.aurevia.smartclinic.web.ConfigurationController` — maps only `POST /api/v1/configuration`, `POST /api/v1/configuration/{key}/versions/{version}/approve`, `GET /api/v1/configuration/{key}` and `GET /api/v1/configuration/{key}/history`.
- `db/migrations/V5__tenant_configuration.sql` — `config_entry` and `config_approval` with row-level security, forced row-level security and immutable triggers; no channel-test table or outcome column.
- `contracts/openapi.yaml` — the configuration section documents the four operations above and no channel-test operation.
- `com.aurevia.smartclinic.exchange.critical.CriticalResultService` and `db/migrations/V23__critical_results.sql` — use `critical.escalation.primary_channel` and `critical.escalation.backup_channel` as escalation configuration keys, not as channels to be probed.
- `db/migrations/V92__service_profiles.sql` — stores `escalation_channel`, `urgent_channel` and `downtime_channel` on a service profile as declared attributes, with no test command or recorded outcome.
- `com.aurevia.smartclinic.tenant.importing` — an unrelated, controlled onboarding data import (OPS-08); it neither reads channel configuration nor invokes channels.

## Gap

Channel identity exists only as configuration keys and service-profile columns. There is no onboarding command that enumerates the configured channels, no adapter that invokes a channel, no per-channel outcome or audit record, and no protected HTTP operation that could enforce role, tenant and approval preconditions. A configured channel can therefore be wrong or unreachable and pilot onboarding would not notice.

A future implementation would change:

1. A new tenant-owned, forced-row-level-security migration for a channel-test run and its per-channel outcomes (with an idempotency key and an audit action), plus a `Jdbc*` store behind a store interface.
2. A new channel-test service that reads only approved, in-tenant channel configuration, refuses missing or unapproved keys, invokes each channel through a narrow adapter and records each outcome transactionally, with role and tenant authorization in the existing policy.
3. A thin new controller with a `POST` channel-test route, an OpenAPI operation carrying its tag, `Idempotency-Key` header and structured error responses, and a regenerated client.
4. Removal of the class-level and four method-level `@Disabled` annotations in the test above once that surface exists.

Estimate: three implementation tasks (persistence, domain service and adapter, then HTTP contract and client) plus one test-enablement task. This task is test-only by scope and does not write production code, migrations, the contract or an existing test.
