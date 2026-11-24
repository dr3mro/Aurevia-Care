# IAM-05 clause 2 — A verified patient account may link to several independently verified charts

## Clause

A verified patient account may link to several independently verified charts.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.patient.clauses.VerifiedPatientAccountLinkSeveralIndependentlyVerifiedTest`
(`apps/api/src/test/java/com/aurevia/patient/clauses/VerifiedPatientAccountLinkSeveralIndependentlyVerifiedTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
normal HTTP entry points under `/api/v1/patient-identity` with synthetic accounts and charts.

- `verifiedAccountLinksToSeveralIndependentlyVerifiedCharts` — one site-verified
  account redeems three distinct single-use invitations for three different verified
  charts; the links endpoint returns one current `linked` event per chart with
  method `INVITATION`, and each per-chart access check returns `true`. Distinct
  invitation tokens show that each chart was independently verified.
- `chartAbsentFromTheActiveTenantCannotBeLinked` (negative) — issuing an invitation
  for a chart that is not visible in the active tenant returns HTTP 404 and creates
  no link, so a chart unverified in that tenant cannot be linked.
- `anotherAccountCannotClaimOrReadTheLinks` (negative) — a different verified
  account cannot claim the invitation (403) or read the owner's links (403), and
  replaying the consumed single-use token returns 409.

Production classes and files exercised by the request path:

- `com.aurevia.patient.identity.IdentityProofingController` —
  `POST /api/v1/patient-identity/accounts`, `POST /invitations`,
  `POST /links/claim`, `GET /accounts/{subject}/links` and
  `GET /accounts/{subject}/access`; a thin adapter that injects the service.
- `com.aurevia.patient.identity.IdentityProofingService` —
  `enroll`, `issueInvitation`, `linkWithInvitation`, `links` and `hasAccess`;
  each link is an append-only event issued from a separate single-use invitation.
- `com.aurevia.patient.identity.IdentityProofingRepository` —
  `insertLink`, `currentLink`, `links`, `hasAccess` and `consumeInvitation`;
  the single SQL boundary over the tenant-guarded transaction.
- `com.aurevia.patient.identity.PatientAccount`, `ChartLink`,
  `InvitationIssue`, `InvitationRecord` and `IdentityActionPolicy` — the domain
  types and bounded staff-action policy; `PatientAccount` and the `current_chart_links()`
  fold key links to immutable chart identifiers, never to mutable contact data.
- `db/migrations/V10__patient_identity_proofing.sql` — tenant-owned tables with
  forced row-level security, append-only link triggers and the
  `current_chart_links()` / `has_chart_access()` folds that allow one current link
  per account and chart.

## Gap

None for the clause behavior: the rule is implemented and reachable through the
HTTP entry points above. Separately, the seven `/api/v1/patient-identity/**`
routes are listed in `contracts/openapi.yaml` rather than published
in `contracts/openapi.yaml`. Documenting them is a repo-wide contract task already
tracked in `TODO.md`; it does not change this clause's runtime behavior or its
test evidence.
