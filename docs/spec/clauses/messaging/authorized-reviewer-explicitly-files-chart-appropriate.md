# MSG-03 clause 9 — an authorized reviewer explicitly files it in the chart if appropriate

## Clause

An authorized reviewer explicitly files it in the chart if appropriate.

## Status

Partly proven

## Evidence

Test class
`com.aurevia.messaging.clauses.AuthorizedReviewerExplicitlyFilesChartAppropriateTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/AuthorizedReviewerExplicitlyFilesChartAppropriateTest.java`),
a pure domain-service test using `AttachmentService`, `InMemoryAttachmentStore`
and a fixed clock. It is deliberately **not** an HTTP-level test: the
`AttachmentController` exposes only upload, scan and download, and no production
caller invokes `fileIntoChart`.

Entry point and production classes exercised:
`com.aurevia.messaging.attachments.AttachmentService.fileIntoChart`
(`apps/api/src/main/java/com/aurevia/messaging/attachments/AttachmentService.java:243-257`),
`AttachmentActions.FILE` (permitted only for the `CLINICIAN` role,
`AttachmentActions.java:53`), `AttachmentContract.FileRequest`,
`AttachmentContract.EventType.FILED_TO_CHART`,
`AttachmentContract.AttachmentEvent`, `AttachmentInputs.key` and
`AttachmentInputs.detail`. The fixture chain calls `upload`, `scan` and
`publish` on the same service to reach a `PUBLISHED` attachment.

- `clinicianFilesPublishedMaterialAndAppendsFiledFact` — a clinician assigned
  to the receiving unit files a published patient-supplied file; the returned
  and persisted attachment are the unchanged version 3, no projection write
  occurs, and exactly one `FILED_TO_CHART` fact is appended with subject
  `clinician-a` and outcome `success` while all prior facts are preserved.
- `receptionStaffCannotFileIntoTheChart` — a `reception` actor is refused
  (403 `FORBIDDEN`) by the clinician-only action matrix.
- `clinicianOfAnotherUnitCannotFileIntoTheChart` — a clinician of another unit
  is refused (403 `WRONG_UNIT`).
- `clinicianWithoutAUnitCannotFileIntoTheChart` — a clinician with no unit
  assignment is refused (403 `WRONG_UNIT`) rather than causing a null failure.
- `clinicianOfAnotherTenantCannotFileIntoTheChart` — a clinician of another
  organization cannot see the file (404 `NOT_FOUND`), proving tenant binding.
- `staleVersionCannotFileIntoTheChart` — a version other than the one read is
  refused (409 `STALE_VERSION`).
- `unpublishedDraftCannotBeFiledIntoTheChart` — both a quarantined draft and a
  clean-but-unpublished file are refused (409 `NOT_PUBLISHED`), so a clean scan
  alone does not qualify as published material.
- `invalidEncounterIdCannotFileIntoTheChart` — a malformed encounter key and an
  overlong provenance note are refused (422 `INVALID_ENCOUNTER_ID` and
  422 `INVALID_DETAIL`).
- `unknownAttachmentCannotBeFiled` — an unknown identifier yields
  404 `NOT_FOUND`.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=AuthorizedReviewerExplicitlyFilesChartAppropriateTest test`
— 9 tests, `failures="0" errors="0" skipped="0"` in the Surefire XML report.

The authorization rule, the published precondition, unit and tenant binding,
the version guard and the append-only fact are fully proven by these tests.
`Partly proven` is used because the durable "in the chart" effect described in
the Gap below is not built.

## Gap

The implementation records the filing decision only as a single append-only
`FILED_TO_CHART` fact. It writes no chart row, invokes no chart store or
service, and returns the attachment unchanged: `FileRequest.encounterId` and
`FileRequest.note` are validated and then discarded (the fact detail is
`null`). There is also no HTTP route for filing and no production caller of
`fileIntoChart`, so the operation is not reachable from an API request today.

Completing the clause would require a chart write path (a Flyway migration with
RLS and FORCE RLS, a `Jdbc*` store behind a store interface and a chart service
call), an injected controller route with an OpenAPI tag, `Idempotency-Key` and
a regenerated client, and an HTTP-level integration test through that route —
at least two separate tasks. These files are outside this test-only story's
scope and were not changed. The tests above do not claim an HTTP-level filing
test that cannot exist.
