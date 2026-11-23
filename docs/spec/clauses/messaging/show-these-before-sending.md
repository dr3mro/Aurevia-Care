# MSG-06 clause 2 — API test evidence

## Clause

Show these before sending.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.ShowTheseBeforeSendingTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/ShowTheseBeforeSendingTest.java`).
All six tests are active (none are disabled) and use synthetic data only with a fixed
`Clock` at `2026-11-19T12:00:00Z`.

- `connectedPatientIsShownPublishedHoursTargetTopicsAndUrgentContactsBeforeSending` — positive proof.
  An active patient with a verified chart and an active connection to the unit reads the profile
  through `ServiceProfileService.viewProfile`, the exact service call behind
  `GET /api/v1/care-units/{unitId}/service-profile`. The test asserts every field of the returned
  profile: timezone and local service windows, response target, topics, localized urgent
  instructions, named queue owner, named backups, escalation/urgent/downtime channels and the
  approval metadata. It also asserts the audited `PROFILE_VIEW`/`SUCCESS` fact with the tenant,
  subject, profile id and timestamp. This is the pre-send disclosure: the read is independent of
  any send command and exposes the full published profile before a message is composed.
- `draftOrFutureDatedProfileIsNeverShownToTheSender` — negative proof of publication state. On a
  connected unit with only a draft version and an approved version whose effective date is in the
  future, the read fails with 404 `NOT_FOUND`. An approved, effective version whose named owner and
  backups are blank is then selected by the store and still refused with 404 `NOT_FOUND`, proving
  the domain enablement filter, not an empty store, withholds the information. The refusals are
  audited as `PROFILE_VIEW`/`NOT_FOUND`.
- `patientWithoutAnActiveConnectionCannotSeeTheUnitsServiceInformation` — negative proof of
  connection scope. Both a patient with no connection and one whose connection was revoked are
  concealed with 404 `NOT_FOUND`.
- `aCrossTenantUnitIsConcealedEvenWithTheSameLocalIdentifier` — negative proof of tenant isolation.
  An active member of another organization requesting the same local unit identifier gets 404
  `NOT_FOUND`, and the denial is recorded in that organization's audit stream. A unit that exists
  only in the home tenant is likewise refused with 404 `NOT_FOUND`.
- `aRoleWithoutProfileViewPermissionIsDenied` — negative proof of role. An active member with a
  role that lacks `PROFILE_VIEW` gets 403 `FORBIDDEN`, and the denial is audited.
- `missingContextReturnsUnauthenticated` — negative proof of request context. Without a bound
  `TenantContext` the read fails closed with 401 `UNAUTHENTICATED` before any profile is resolved
  and before any audit fact is written.

Production classes on the read path under proof. This clause test calls
`ServiceProfileService.viewProfile` directly; the unchanged HTTP test below exercises the whole
chain from `ServiceProfileController.view`:

- `com.aurevia.smartclinic.messaging.service.ServiceProfileController` — thin HTTP adapter whose
  `GET /api/v1/care-units/{unitId}/service-profile` route is documented as showing the effective
  published service information before a patient sends; it injects the service and decides no policy.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileService` — owns input validation,
  request context, membership and role authorization, exact-unit access, effective-version
  selection, the 404/403/401 refusals and the audit facts.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileActions` — grants `PROFILE_VIEW` to the
  patient, tenant administrator, privacy auditor and assigned supervisor roles and withholds it
  from other active roles.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileContract` — the immutable profile,
  hours, localized instructions and audit value types; `ServiceProfile.enablesUnitAt` enforces the
  named-owner and backup invariant before a profile is shown.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileStore` and its test-scope fixture
  `InMemoryServiceProfileStore` — the tenant-scoped read boundary used by these unit tests.

The end-to-end HTTP proof that the route reaches the durable store is the existing
`com.aurevia.smartclinic.it.messaging.ServiceProfileHttpIntegrationTest`, which serves a published
and approved profile over `GET /api/v1/care-units/{unitId}/service-profile` and asserts the
denials for a subject without active membership, a revoked patient connection and another
tenant. The thin controller delegation is covered separately by
`com.aurevia.smartclinic.messaging.service.ServiceProfileControllerTest`.
Neither file was changed by this task.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the contract or a migration. The OpenAPI entry for the
service-profile route is tracked separately as `FIX-OPENAPI-03` and is outside this clause's scope.
