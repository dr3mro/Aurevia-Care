# ADM-01 clause 3 — Pilot site onboarding tests each configured channel

## Clause

Pilot site onboarding tests each configured channel.

## Status

Not built

## Evidence

No runnable test proves this clause, and no production surface implements it. The
configuration domain supports only draft, named approval, effective reads and
append-only history; there is no onboarding channel-test command, no channel
invocation adapter and no per-channel outcome record to exercise.

A `@Disabled("GAP: ...")` would-prove test was drafted but is deliberately not part
of this change. The repository quality gate (`scripts/check-constraints.py`) rejects
every newly added skipped test, and AGENTS.md forbids keeping a test that proves
nothing: a disabled class cannot reach a route that does not exist. This task is
test-only by scope, so it does not add a fabricated channel adapter or production
command merely to enable a test. The honest result is a documented gap with no test
evidence rather than a skipped placeholder.

Production classes and files surveyed, none of which tests a configured channel at
onboarding:

- `com.aurevia.configuration.ConfigurationService` — draft, named
  approval, effective read and append-only history only; its authorization checks
  active membership and role permission, with no channel input or channel invocation.
- `com.aurevia.configuration.ConfigurationRepository`, `ConfigEntry` and
  `ConfigHistory` — versioned key/value storage and projections; no channel-test
  result or outcome type.
- `com.aurevia.web.ConfigurationController` — maps only
  `POST /api/v1/configuration`,
  `POST /api/v1/configuration/{key}/versions/{version}/approve`,
  `GET /api/v1/configuration/{key}` and `GET /api/v1/configuration/{key}/history`.
- `db/migrations/V5__tenant_configuration.sql` — `config_entry` and `config_approval`
  with row-level security, forced row-level security and immutable triggers; no
  channel-test table or outcome column.
- `contracts/openapi.yaml` — the configuration section documents the four operations
  above and no channel-test operation.
- `com.aurevia.exchange.critical.CriticalResultService` and
  `db/migrations/V23__critical_results.sql` — use
  `critical.escalation.primary_channel` and `critical.escalation.backup_channel` as
  escalation configuration keys, not as channels to be probed.
- `db/migrations/V92__service_profiles.sql` — stores `escalation_channel`,
  `urgent_channel` and `downtime_channel` on a service profile as declared
  attributes, with no test command or recorded outcome.
- `com.aurevia.tenant.importing` — an unrelated, controlled onboarding
  data import (OPS-08); it neither reads channel configuration nor invokes channels.

## Gap

Channel identity exists only as configuration keys and service-profile columns.
There is no onboarding command that enumerates the configured channels, no adapter
that invokes a channel, no per-channel outcome or audit record, and no protected HTTP
operation that could enforce role, tenant and approval preconditions. A configured
channel can therefore be wrong or unreachable and pilot onboarding would not notice.

A future implementation would change:

1. A new tenant-owned, forced-row-level-security migration for a channel-test run and
   its per-channel outcomes (with an idempotency key and an audit action), plus a
   `Jdbc*` store behind a store interface.
2. A new channel-test service that reads only approved, in-tenant channel
   configuration, refuses missing or unapproved keys, invokes each channel through a
   narrow adapter and records each outcome transactionally, with role and tenant
   authorization in the existing policy.
3. A thin new controller with a `POST /api/v1/configuration/channel-tests` route, an
   OpenAPI operation carrying its tag, `Idempotency-Key` header and structured error
   responses, and a regenerated client.
4. A new HTTP-level integration test for that route with one positive case and
   negative cases for wrong role, foreign tenant and missing/unapproved channel
   configuration, at 100% coverage of the new production code. No placeholder test
   remains to enable: a skipped scaffold was removed because it could never reach a
   route that does not exist, so the test must be written when the surface lands.

Estimate: three implementation tasks (persistence, domain service and adapter, then
HTTP contract and client) plus one test task. This task is test-only by scope and
does not write production code, migrations, the contract or an existing test.
