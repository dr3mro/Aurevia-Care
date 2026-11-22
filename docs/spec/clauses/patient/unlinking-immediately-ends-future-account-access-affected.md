# IAM-05 clause 3 — API test evidence

## Clause

Unlinking immediately ends future account access for the affected chart.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.patient.clauses.UnlinkingImmediatelyEndsFutureAccountAccessAffectedTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/UnlinkingImmediatelyEndsFutureAccountAccessAffectedTest.java`).
All four tests are active (none are disabled) and use a dedicated PostgreSQL
database created from the production Flyway migrations, synthetic tenants,
synthetic subjects and synthetic charts.

- `unlinkEndsFutureAccessOnTheNextRequestForTheAffectedChart` — positive proof.
  A verified account links a chart (`POST /api/v1/patient-identity/links/claim`),
  the very next `GET /api/v1/patient-identity/accounts/{subject}/access`
  returns `true`, the patient unlinks the chart
  (`POST /api/v1/patient-identity/links/unlink`) and the following GET returns
  `false`. The unlink response and `GET .../links` both report the append-only
  event `unlinked` with method `PATIENT_REQUEST`.
- `unlinkLeavesOtherVerifiedChartsAccessible` — positive scoping proof. With two
  independently verified charts, unlinking one denies only that chart; the
  sibling chart still returns `true`. The rule is per chart, not per account.
- `aReceptionStaffCannotUnlinkAndAccessContinues` — negative proof (wrong role).
  `LINK_UNLINK` is a privileged action; a `reception` staff member receives HTTP
  403, and the previously granted access and `linked` event remain unchanged.
- `unlinkOfANeverLinkedChartIsRefusedWithoutCreatingAccess` — negative proof
  (forbidden state). Unlinking a chart the account never linked is refused with
  HTTP 404 `LINK_NOT_FOUND`, and the refused command does not create access to
  that chart.

Production classes exercised through the real HTTP entry point
(`IdentityProofingController`):

- `com.aurevia.smartclinic.patient.identity.IdentityProofingController` — thin
  adapter for `/links/unlink`, `/accounts/{subject}/access` and `/accounts/{subject}/links`.
- `com.aurevia.smartclinic.patient.identity.IdentityProofingService` — appends an
  `unlinked` link event on unlink, retires unspent invitations, enforces the
  self-or-privileged-staff rule, and answers access from the current link fold.
- `com.aurevia.smartclinic.patient.identity.IdentityProofingRepository` — the
  single SQL boundary; `hasAccess` calls `public.has_chart_access`, and
  `currentLink` joins `public.current_chart_links()` on the database-owned
  sequence of the append-only event.
- `com.aurevia.smartclinic.patient.identity.IdentityActionPolicy` — grants the
  privileged `PATIENT_LINK_UNLINK` action to `tenant_admin` and
  `privacy_auditor`, but not to `reception` (negative test).
- `com.aurevia.smartclinic.patient.identity.ChartLink` — the append-only event
  projection whose `event` is asserted as `unlinked` after the command.

Database evidence: `db/migrations/V10__patient_identity_proofing.sql` creates
`account_chart_link` with forced row-level security and append-only triggers,
and defines `current_chart_links()` and `has_chart_access(text, uuid)`; the
latter is `coalesce(bool_or(event = 'linked'), false)` over the current events,
so an `unlinked` event ends access on the next request without editing history.
The `/api/v1/patient-identity` routes (including `/links/unlink` and
`/accounts/{subject}/access`) are currently baselined as missing from
`contracts/openapi.yaml` in `contracts/openapi/missing-routes.txt`, not
documented there. That is a separate contract-completeness gap and does not
change this clause's runtime proof.

## Gap

None for this clause.

The IAM-05 clause-3 rule is built and proven by active tests through the real
HTTP entry point and the production migrations. This task added proof only; it
did not change production code, an existing test, the OpenAPI contract or a
migration. Two observations are outside this clause's rule and were not changed
here: a repeat unlink of an already-unlinked chart is accepted and appends a
second `unlinked` event rather than returning 404 (access stays denied), and the
patient-identity routes remain in the OpenAPI missing-routes baseline.
