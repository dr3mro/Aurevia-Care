# XCH-04 clause 4 — corrections preserve prior versions, notify prior recipients and reopen review

## Clause

Corrections preserve prior versions, notify prior recipients where appropriate, and reopen review even after acknowledgment.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.CorrectionsPreservePriorVersionsNotifyPriorRecipientsTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/CorrectionsPreservePriorVersionsNotifyPriorRecipientsTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with two synthetic organizations, synthetic
subjects and one synthetic patient per organization:

- `correctionPreservesPriorRevisionNotifiesPriorRecipientsAndReopensAcknowledgedReview`
  — a `tenant_admin` registers an expected order, a `lab_verifier` imports a
  `final` result and a `clinician` acknowledges revision 1 with `If-Match: 1`.
  The verifier then imports a `corrected` result with `supersedesVersion: 1`.
  The response carries version 2 and `previousId` equal to the original id.
  Persisted state shows two immutable revisions, one notification on the new
  revision with `recipient_subject = verifier-a` and `superseded_version = 1`,
  the original review still `acknowledged`, the new review `unreviewed`, and one
  `XCH04_REVIEW_REOPENED` audit event on the original revision. Reads of both
  revisions confirm the original attestation survives and the new revision
  carries a lineage of two and the prior-recipient notice. This adds persisted
  notice, review-state and audit assertions to the behaviour already covered by
  the broader `ResultRoutingIntegrationTest` suite.
- `correctionWithoutSupersedesIsRefused` — a `corrected` import with no
  `supersedesVersion` is refused (422, `SUPERSEDES_REQUIRED`); no revision,
  notice, reconciliation entry or reopen audit is written.
- `staleCorrectionIsDivertedToReconciliation` — a `corrected` import targeting
  version 9 when the lineage tip is 1, after the original was acknowledged, is
  diverted to reconciliation (202, reason `conflict`, mismatch
  `supersedesVersion`); no new revision or notice is written, no reopen audit is
  emitted, the acknowledged original is unchanged and exactly one reconciliation
  row is stored.
- `finalResultWithSupersedesIsRefused` — a non-correction carrying
  `supersedesVersion` is refused (422, `SUPERSEDES_NOT_ALLOWED`) with no write.
- `actorWithoutImportRoleCannotPostCorrection` — a `reception` actor cannot post
  a correction (403 `FORBIDDEN`); no revision, notice, reconciliation row or
  reopen audit is written.
- `correctionByActorWhoIsNotAMemberOfTheActiveTenantIsDenied` — a verifier of
  the origin tenant acting under the peer tenant's organization cannot post a
  correction (403 `FORBIDDEN` under the peer tenant); the origin
  lineage is unchanged and the peer tenant stores no revision.

Production classes exercised through `POST /api/v1/xch04/orders`,
`POST /api/v1/xch04/results`, `POST /api/v1/xch04/results/{id}/acknowledge` and
`GET /api/v1/xch04/results/{id}`:
`com.aurevia.smartclinic.exchange.result.ResultController` (thin HTTP adapter),
`com.aurevia.smartclinic.exchange.result.ResultRoutingService` (correction
branch, tip check, prior-recipient lookup, review-reopen audit and the two
supersession refusals), `com.aurevia.smartclinic.exchange.result.ResultCommands`,
`com.aurevia.smartclinic.exchange.result.ResultActionPolicy` (lab-verifier-only
import and tenant membership), `com.aurevia.smartclinic.exchange.result.ResultRepository`
(lineage, prior recipients, notifications, review tasks and RLS-bounded reads),
`com.aurevia.smartclinic.exchange.result.ResultRules` and
`com.aurevia.smartclinic.exchange.result.ResultModel`. Storage and invariants
come from `db/migrations/V22__result_routing.sql` (tenant RLS and FORCE RLS,
immutable revision/notification ledgers, linked `previous_id`, and the guarded
review transition).

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.smartclinic.it.exchangeresult.ResultRoutingIntegrationTest`
(`correctionPreservesTheOriginalReopensReviewAndNotifiesPriorRecipients`),
`com.aurevia.smartclinic.acceptance.exchange.FinalReportReplayIntegrityAcceptanceTest`
(`correctionReopensReviewAfterHumanAcknowledgment`),
`com.aurevia.smartclinic.exchange.result.ResultRoutingServiceTest` and
`com.aurevia.smartclinic.exchange.conformance.PartnerConformanceFixturesTest`
(`correctionPreservesThePriorRevisionAndReopensReview`).

Verified locally with `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=CorrectionsPreservePriorVersionsNotifyPriorRecipientsTest test`
— Surefire XML: `tests="6" errors="0" skipped="0" failures="0"`, matching the six
`@Test` methods. "Proven by tests" is limited to the clause behaviour above; it
does not claim that these routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the committed
`contracts/openapi.yaml` has no `/api/v1/xch04` paths — the routes are listed in
`contracts/openapi/missing-routes.txt`. Documenting them would change the
contract, the generated client and the contract tests: one separate
ARC-04/WEB-05 contract task.
