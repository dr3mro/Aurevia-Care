# XCH-03 clause 5 — apply law and policy to recipient retention

## Clause

Apply law and policy to recipient retention.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.ApplyLawPolicyRecipientRetentionTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/ApplyLawPolicyRecipientRetentionTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with synthetic subjects and two synthetic
patients. Every test drives the real `/api/v1/xch03/orders` entry point, and the
durable retained copy is also read back with a tenant-bound SQL assertion against
`public.xch03_retained_copy` (the migration owner is subject to FORCE RLS).

- `legalHoldPolicyRetainsPurposeAndActorInLedgerAndDatabase` — accepting with
  `permittedPurpose = RESULT_REVIEW` and `retentionPolicy = LEGAL_HOLD` returns
  200; `GET /{id}` exposes one retained copy carrying that purpose, policy,
  `legalHold = true`, the released checksum and the authenticated user's opaque
  `actorId` (not its subject). A SQL read of revision 1 of the retained copy
  asserts the same purpose, policy, hold flag and actor.
- `standardPolicyRetainsACopyWithoutLegalHold` and
  `extendedPolicyIsApprovedWithoutLegalHold` — the other two approved policies
  are accepted and produce a retained copy with `legalHold = false` in both the
  HTTP view and the SQL row. Together with the legal-hold case this proves the
  flag is derived from the approved policy rather than hardcoded.
- `unapprovedRetentionPolicyIsRefusedAndNoCopyIsWritten` — `FOREVER` is
  syntactically a valid code but is not an approved policy: acceptance is
  refused with 422 and `INVALID_RETENTION_POLICY`, and no retained copy is
  written. This is the allow-list branch of `ExchangeResponseInputs.retentionPolicy`.
- `actorWithoutRecipientRoleCannotApplyRetentionPolicy` — a `reception` actor is
  refused with 403 `FORBIDDEN` and writes no copy, proving that applying a
  retention policy is a recipient-only action.
- `otherTenantCannotApplyRetentionPolicy` — an actor whose active organization
  is the peer tenant cannot retain an order owned by the first tenant (404
  `NOT_FOUND` under forced RLS); no copy is written in the order's tenant.
- `staleVersionCannotApplyRetentionPolicy` — a mismatched `If-Match` is refused
  with 412 `STALE_VERSION` and writes no copy.
- `secondAcceptCannotCreateAnotherRetainedCopy` — a second accept at the current
  version is refused with 409 `INVALID_TRANSITION`, leaving one retained copy.
- `acceptedChangeReRetainsNextRevisionUnderOriginalPolicy` — an order is first
  accepted under `STANDARD` / `RESULT_REVIEW`; after a linked change is accepted,
  revision 2 is re-retained under those same captured values (still `STANDARD`
  and `legalHold = false`, not a hardcoded legal hold), with the responding
  recipient as actor. Two retained rows exist in the SQL ledger.
- `rejectedChangeLeavesRetainedCopyUnchanged` — rejecting the same linked change
  leaves exactly the original retained copy, with its checksum unchanged.

Production classes exercised through `POST /api/v1/xch03/orders/{id}/accept`,
`POST /{id}/propose-change`, `POST /{id}/change-response` and `GET /{id}`:

- `com.aurevia.smartclinic.exchange.response.ExchangeResponseController` — thin
  HTTP adapter that parses the `If-Match` precondition.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseService` — derives
  `legalHold` from the approved policy, captures the purpose and policy on
  acceptance, and re-retains the next revision under the same policy when an
  accepted change is responded to.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseInputs` — the
  approved `STANDARD` / `EXTENDED` / `LEGAL_HOLD` allow-list and the refusal of
  any other policy code.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseActionPolicy` —
  recipient-only role matrix; `reception` cannot apply a retention decision.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseRepository` — the
  tenant-guarded SQL boundary that writes and reads `xch03_retained_copy`.
- `com.aurevia.smartclinic.exchange.response.ExchangeResponseCommands.RetainedCopy`
  — the immutable retained-copy projection.
- `db/migrations/V21__xch03_order_response.sql` — `retention_policy` CHECK,
  `legal_hold NOT NULL`, the unique retained-copy key and RLS/FORCE RLS.

Discovery found no other recipient-retention implementation: the messaging
governance retention code belongs to a different record family, and the tenant
retention kernel belongs to infrastructure. "Proven by tests" is limited to the
XCH-03 acceptance-time policy application and re-retention above.

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=ApplyLawPolicyRecipientRetentionTest test` — Surefire
`tests="10" errors="0" skipped="0" failures="0"`. The full JaCoCo gate remains
with the integrator.

Static analysis for this test-only change: SpotBugs Maven Plugin 4.10.5.0,
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check`
— exit 0, zero findings (tool evidence). OWASP Dependency-Check 12.1.0 adds no
new surface because no dependency, production class or build file changed; the
integrator runs the dependency gate. The repository's fast policy checks
(`check-docs`, `check-names`, `check-wiring`, `check-constraints`, `check-secrets`,
`check-migrations`, `python-coverage`) each exit 0.

## Gap

Two pre-existing gaps are deliberately not changed by this test-only story:

1. The committed `contracts/openapi.yaml` has no `/api/v1/xch03/orders` path. All
   eleven deployed routes are listed in `contracts/openapi/missing-routes.txt`.
   Publishing them, adding the required headers and tags and regenerating the
   client is one separate contract-documentation task.
2. `xch03_retained_copy` declares a `retention_policy` and a `legal_hold` flag,
   but no live job expires copies or suspends expiry for a held copy. This is
   recorded in `docs/security/data-inventory.md` as the OPS-07 `TASK-RETENTION`
   gap; approved jurisdiction-specific periods are an OPS-01 owner decision, not
   an engineering task. Implementing the job and its policy configuration is
   separate work outside this XCH-03 requirement. No skipped test was added for
   it: it has no XCH-03 HTTP entry point to exercise, and the repository's
   constraint gate rejects an added skipped test.
