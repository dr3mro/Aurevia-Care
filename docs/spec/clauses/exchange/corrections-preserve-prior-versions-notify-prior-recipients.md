# XCH-04 clause 4 — corrections preserve prior versions, notify prior recipients, reopen reviewed results

## Clause

Corrections preserve prior versions, notify prior recipients where appropriate, and reopen review even after acknowledgment.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.CorrectionsPreservePriorVersionsNotifyPriorRecipientsTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/CorrectionsPreservePriorVersionsNotifyPriorRecipientsTest.java`).
All five tests are active (none disabled), use only synthetic fixtures and drive the real HTTP
entry point against a freshly migrated database. The class is tagged `integration`, runs under the
`synthetic-auth` profile and uses its own database `smartclinic_xch04_correct_it`, following the
neighbouring `com.aurevia.smartclinic.it.exchangeresult.ResultRoutingIntegrationTest` fixtures
and setup. It creates its own `smartclinic_migrate` and `smartclinic_app` roles so it is
self-contained when run alone, and drops the database in `@AfterAll`.

- `correctionPreservesPriorRevisionNotifiesPriorRecipientAndReopensAcknowledgedReview` — positive
  proof. After a synthetic order is registered, `verifier-a` imports `final` v1 and `clinician-a`
  acknowledges it with `If-Match: 1`. A `corrected` result with `supersedesVersion: 1` returns HTTP
  201 with version 2 and `previousId` equal to the v1 id. A direct ledger query confirms two
  revision rows for the accession. `GET /api/v1/xch04/results/{v1Id}` still reports `/review/state`
  `acknowledged`, while `GET .../results/{v2Id}` reports `unreviewed`, a `/lineage` of two entries,
  exactly one notification whose `/recipient` is `verifier-a` and `/supersededVersion` is `1`, and
  one `XCH04_REVIEW_REOPENED` audit row keyed to v1. The prior revision is never overwritten.
- `correctionBySubjectWithoutImportRoleIsForbidden` — negative proof of the authorization boundary.
  After a matched v1 baseline, a `reception` subject posts the correction and is refused with HTTP
  403; the revision and notification counts are unchanged.
- `correctionFromPeerTenantCannotModifyHomeTenantData` — negative proof of tenant isolation. A
  `lab_verifier` of organization B with organization A active is refused with HTTP 403 for lack of
  membership. With its own tenant and its own patient, the same subject reading organization A's
  revision gets HTTP 404 under forced RLS, and importing a correction that reuses organization A's
  order, source and accession identifiers is diverted to reconciliation (HTTP 202, `unmatched`)
  while organization A's revision and notification counts stay unchanged.
- `correctionWithNonTipSupersedesVersionIsQueuedNotImported` — negative proof of the lineage guard.
  With v1 as the tip, a correction naming version 9 is queued with HTTP 202 and reason `conflict`;
  no revision or notification is appended.
- `correctedResultWithoutSupersedesVersionIsRefused` — negative proof of the input contract. With a
  matched v1 baseline, a `corrected` result without `supersedesVersion` and a `final` result with
  one are both refused with HTTP 422, leaving the revision and notification counts unchanged.

Entry-point-to-test trace: `POST /api/v1/xch04/orders` and `POST /api/v1/xch04/results` →
`ResultController` → `ResultRoutingService.registerOrder` / `importResult` → `importGuarded`;
`POST .../results/{id}/acknowledge` → `ResultController` → `ResultRoutingService.acknowledge`;
`GET .../results/{id}` → `ResultController` → `ResultRoutingService.getReport`. Direct SQL
assertions read the `xch04_report_revision`, `xch04_notification` and `audit_event` ledgers.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.exchange.result.ResultController` — thin adapter binding the JSON
  request and the `If-Match` precondition to the domain service; a correction returns 201 with a
  `Location` header while a diverted report returns 202.
- `com.aurevia.smartclinic.exchange.result.ResultRoutingService` — owner of the command.
  `importGuarded` requires `supersedesVersion` on a correction, holds a non-tip target for
  reconciliation, captures the prior revision's id, its owners and its review before appending the
  new immutable revision, opens a fresh review task, writes one notification per prior recipient and
  stamps `XCH04_REVIEW_REOPENED` only when the prior review was acknowledged.
- `com.aurevia.smartclinic.exchange.result.ResultRepository` — the SQL boundary for
  `priorRecipients`, `insertNotification`, `notifications`, `insertReview` and the lineage queries.
- `com.aurevia.smartclinic.exchange.result.ResultActionPolicy` — bounds `RESULT_IMPORT` to the
  `lab_verifier` role and re-checks active membership inside the protected transaction.
- `com.aurevia.smartclinic.exchange.result.ResultModel` — the `ResultReport`, `Review`,
  `RecipientNotification`, `ImportOutcome` and `ResultDetail` wire types carrying `previousId` and
  the notification's superseded version.
- `db/migrations/V22__result_routing.sql` — append-only, forced-RLS revision ledger with tenant
  policies, the review and notification tables and the foreign keys that keep cross-tenant
  references from resolving.

These `/api/v1/xch04/*` routes are already baselined in `contracts/openapi/missing-routes.txt`, so
this test-only story changes no production code, contract, generated client or migration.

## Gap

None.
