# ADM-02 clause 2 — authorized operators assign, resolve, retry or escalate with reason

## Clause

Authorized operators assign, resolve, retry or escalate with reason.

## Status

Partly proven

## Evidence

Test class `com.aurevia.configuration.clauses.AuthorizedOperatorsAssignResolveRetryEscalateReasonTest`
(`apps/api/src/test/java/com/aurevia/configuration/clauses/AuthorizedOperatorsAssignResolveRetryEscalateReasonTest.java`).
It is a synthetic, database-free clause test in the `clauses` style of
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: opaque tenant,
operator and item identifiers, a fixed `Instant`, a synthetic `TenantContext`
set and cleared around every case, and a mocked `TransactionTemplate` that runs
the real callback synchronously. No patient data or real identifier is used.

Active tests, all against the built kernel only:

- `assignRecordsOwnerAndReasonWithoutMutatingTheOriginal` — an open item is
  assigned to a named owner; the new item is `ASSIGNED`, retains the owner and
  the reason, carries exactly one `QueueAction("assign", actor, reason, at)`,
  keeps its tenant and queue identity, and the original item is still `OPEN`
  with no owner, reason or action (rule met for assign).
- `resolveIsRefusedOnAnAlreadyResolvedItem` — a second resolve and an assign
  on an already `RESOLVED` item are both refused with 409 `INVALID_TRANSITION`,
  and the original action history is unchanged (negative: forbidden state;
  resolution is terminal).
- `assignIsRefusedWithoutAReason` — a `null` and a blank reason are both refused
  with 422 `REASON_REQUIRED`, and no action is appended (negative: missing
  input, proving the "with reason" qualifier is enforced).

The operator command boundary is not built, so this change carries no HTTP-level
test and no skipped test: a test that cannot reach a route, service or audit row
would prove nothing. The cases a later task must make executable are specified
under `## Gap` below:

- Every command (assign, resolve, retry and escalate) must require a reason and
  commit a reason-bearing action plus an audit row, with an idempotency replay
  appending no second action.
- A caller from another tenant, a caller without the operator role and a
  reasonless command must be refused at the boundary.
- An outdated version must be refused with 409 while the current version still
  succeeds.

Production classes surveyed and exercised by the active tests:

- `com.aurevia.config.queues.OperationalQueueItem` — `open` (`:59`),
  `assign` (`:75`), `resolve` (`:96`), `requireActionable` (`:106`) and
  `requireText` (`:115`) own the only built part of this clause: tenant-scoped,
  immutable work items with mandatory actor, owner and reason, a frozen
  append-only action history, and terminal resolution.
- `com.aurevia.config.queues.OperationalWorkState` (`OPEN`,
  `ASSIGNED`, `RESOLVED`) and `QueueAction` (`type`, `actor`, `reason`, `at`) —
  the state model and the single action verb pair `assign`/`resolve`.
  `QueueAction`'s own Javadoc (`:10-11`) states that operator authorization and
  durable audit references belong at a command boundary outside this model.
- `com.aurevia.api.ApiException` — the `INVALID_TRANSITION` (409)
  and `REASON_REQUIRED` (422) refusals asserted above, plus `OWNER_REQUIRED`,
  `ACTOR_REQUIRED` and `INVALID_ACTION_TIME`.
- `com.aurevia.tenant.TenantContext` — the synthetic tenant scope
  used by the clause fixtures.
- Derivations that create the items: `com.aurevia.clinical.queues.UnsignedEncounterQueue.query`
  and `com.aurevia.config.rosters.MissingCoverageQueue.query`. Both
  emit only `OPEN` items; neither assigns or resolves anything.

Survey result: `com.aurevia.configuration` holds ADM-01
administration (`ConfigurationService.create/approve/get/history`, `ConfigEntry`,
`ConfigHistory`, `ConfigurationRepository`) and is not this rule. No controller,
service, store, request type, migration, table, RLS policy, audit writer or
OpenAPI route exists for ADM-02 operational queues; `retry` and `escalate` do
not exist on the model at all. The assign/resolve/retry/escalate routes listed
in `contracts/openapi.yaml` (lines 152, 164, 168, 172-173, 184,
221, 224, 269-270, 300, 351) belong to the check-in worklist, conversations,
critical results, exchange delivery, imaging, patient instructions, patient
access cases, prescriptions and reconciliation; they are baseline context for
other domains, not ADM-02 queue actions, and were not added or edited here.
A stale tracker claim that READY-31 delivered ADM-02 operator assign/retry/
escalate refers to that staff-web UI calling those other-domain endpoints; it
is not API proof for this clause.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=AuthorizedOperatorsAssignResolveRetryEscalateReasonTest test`
— `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

The immutable assign/resolve-with-reason kernel is implemented and proven. The
authorized-operator API is not built: there is no role, tenant, version or
reason check at a command boundary, no `retry` or `escalate` action or state,
no HTTP route, no OpenAPI entry or generated client, and no durable queue
action or audit store. Closing that gap needs **two** bounded tasks:

1. A database task: a tenant-scoped queue-action/audit table with a Flyway
   migration using the reserved version, `FORCE ROW LEVEL SECURITY`, append-only
   grants, a `Jdbc*Store` under `src/main` and PostgreSQL transaction tests.
   Files it would change (none changed here): `db/migrations/`, the new store
   under `apps/api/src/main/java/com/aurevia/config/queues/`, its
   test under `apps/api/src/test/` and `db/check.sh` coverage.
2. An API task: an injected operator command service enforcing role, tenant,
   reason and version inside the tenant-guarded transaction, adding `retry` and
   `escalate` to `OperationalWorkState`/`QueueAction`, a thin controller with
   the four command routes, HTTP-level integration tests for the three cases
   listed under `## Evidence`, the OpenAPI entries with tags and
   `Idempotency-Key` headers, and the regenerated client.

Separately, five of the seven ADM-02 queue derivations (unreconciled reports,
unacknowledged critical findings, rejected exchanges, document failures and
revoked accounts) are still missing; that is adjacent ADM-02 work, not a
prerequisite for proving this clause's operator actions, and belongs to its own
task.
