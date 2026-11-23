# XCH-06 clause 1 — API test evidence

## Clause

Patient release is a separate authorization referencing the immutable revision, release policy version and effective time.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.PatientReleaseSeparateAuthorizationReferencingImmutableRevisionTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/PatientReleaseSeparateAuthorizationReferencingImmutableRevisionTest.java`).
All five tests are active (none disabled) and pass against a real PostgreSQL database with
synthetic fixtures only. The suite recreates its own database from the authoritative Flyway
migrations, seeds two tenants with bounded roles, and drives the authorization through the
normal HTTP entry point `POST /api/v1/releases` using the `synthetic-auth` profile.

- `authorizeRecordsImmutableRevisionPolicyVersionAndEffectiveTime` — positive proof. A
  `clinician` authorizes a `TIMED` release of revision `XCH06-REV-0001` at version 3 under
  policy version 7 with an explicit effective instant. HTTP 201 returns the same revision id,
  revision version, policy version, effective instant, kind and state; the issuing
  organization and author are derived from the tenant context. The same references are
  persisted on the `patient_release` row, while the decision is recorded separately as one
  `RELEASE_AUTHORIZE` event with the deciding actor and one `RELEASE_AUTHORIZED` notification
  to the patient.
- `refusesReleaseAuthorizationForRoleWithoutDecidePermission` — negative proof of separation
  of duties. A `reception` member and a `privacy_auditor` member, both with active membership,
  are refused with HTTP 403; no release row or decision record is created.
- `refusesReleaseAuthorizationFromAnotherTenant` — negative proof of tenant isolation. A
  member of ORG_A using ORG_B as the active organization is refused with HTTP 403, and an
  ORG_A authorization is not readable by an ORG_B member (HTTP 404) or visible in ORG_B's
  release tables. The actor and organization come from request headers, never the body.
- `refusesReleaseWithMissingOrInvalidReferences` — negative proof of the reference contract.
  An empty revision id, a revision version below one, a non-UUID patient id and a policy
  version below one are each refused with HTTP 422 and a machine-readable error code, and
  leave no release row.
- `refusesCorrectionOntoTheSameImmutableRevision` — negative proof of immutability. A
  correction that re-points the authorized release at its own revision is refused with HTTP
  409 `CONFLICT`; the release stays `AUTHORIZED` and no `RELEASE_CORRECT` event is appended.

Production classes exercised through the entry point and its guarded transaction:

- `com.aurevia.smartclinic.exchange.release.PatientReleaseController` — thin adapter for
  `POST /api/v1/releases`, `POST /api/v1/releases/{id}/correct` and `GET /api/v1/releases/{id}`;
  it accepts no actor or organization from the request body.
- `com.aurevia.smartclinic.exchange.release.ReleaseService` — `authorize` validates the three
  references, re-derives membership and roles, commits the release and its decision records
  in one transaction, and `correct` rejects a correction onto the same immutable revision.
- `com.aurevia.smartclinic.exchange.release.ReleaseActionPolicy` — bounds the separate
  `RELEASE_AUTHORIZE` decision to the clinician and tenant administrator roles.
- `com.aurevia.smartclinic.exchange.release.AuthorizeRelease` and `PatientRelease` — the
  command and immutable projection carrying the revision id, revision version, policy version
  and effective instant.
- `com.aurevia.smartclinic.exchange.release.ReleaseRepository` — tenant-guarded writes and
  reads of the three release tables.
- `db/migrations/V24__patient_release.sql` — `patient_release`, `release_event` and
  `release_notification`, each with row-level security, forced row-level security and
  append-only triggers; revision, policy version and effective instant are immutable on
  update.

The existing suites `ReleaseServiceTest`, `PatientReleaseControllerHttpTest`,
`PatientReleaseIsolationTest` and `PatientReleasedRevisionsControllerHttpTest` already cover
the validation, visibility, HTTP status, correction, withdrawal and read-side behaviour.
This task adds a focused clause-only proof and changes none of them.

An adjacent, non-blocking contract debt is recorded for completeness: `POST /api/v1/releases`
is still listed in `contracts/openapi/missing-routes.txt`, so the authoritative OpenAPI
contract has not published this route. Publishing it and regenerating the client is a
separate contract task, outside this test-only scope and not required to prove the clause.

## Gap

None
