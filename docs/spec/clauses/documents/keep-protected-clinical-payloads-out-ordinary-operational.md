# ARC-06 clause 5 — keep protected payloads out of ordinary operational surfaces

## Clause

Keep protected clinical payloads out of ordinary operational logs, URLs, analytics and notification content.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.documents.clauses.KeepProtectedClinicalPayloadsOutOrdinaryOperationalTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/KeepProtectedClinicalPayloadsOutOrdinaryOperationalTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants and a distinctive
synthetic protected-value marker in the original file name:

- `downloadHandleCarriesNoPayloadNameOrUrl` — after upload, clean scan and completion, the mediated
  download response has exactly the five metadata fields (`objectKey`, `objectVersion`, `checksum`,
  `contentType`, `sizeBytes`), no `Location` header and no marker value, patient identifier, URL
  scheme or presign query parameter. The assertion that the stored artifact does contain the marker
  keeps the absence check non-vacuous.
- `auditFactsNeverCarryPayloadNameOrUrl` — the append-only `document_event` trail returned by the
  view contains `UPLOAD_ACCEPTED`, `SCAN_CLEAN`, `UPLOAD_COMPLETED` and `DOWNLOADED`; every non-null
  `detail` is at most 200 characters and carries none of the marker, patient id, URL scheme or
  presign token, and a direct ledger query finds no row mentioning the marker.
- `wrongRoleIsRefusedWithoutEchoingProtectedData` — a wrong-role scan is refused with
  `403 FORBIDDEN` and the static message `missing SCAN permission`, and the same-tenant denial fact
  stores only the stable code. A cross-tenant view is concealed with `404 NOT_FOUND` and the generic
  message; `DocumentGuard.notFound()` writes no fact by design, so concealment cannot be probed
  through the ledger. Neither error body echoes the protected marker.
- `smuggledValueIsNotEchoedInErrorDetail` — a URL-like file name is rejected with
  `422 INVALID_FILE_NAME` and a fixed message that does not contain the submitted value; the
  ledger count for that value is unchanged and an earlier valid artifact's fact trail contains it in
  no detail.

Production classes exercised through the entry point:
`com.aurevia.smartclinic.documents.DocumentArtifactController` (download and view routes),
`com.aurevia.smartclinic.documents.QuarantineService`,
`com.aurevia.smartclinic.documents.DocumentGuard`,
`com.aurevia.smartclinic.documents.DocumentContract` (`MediatedDownload`, `DocumentEvent`),
`com.aurevia.smartclinic.documents.UploadValidation`,
`com.aurevia.smartclinic.api.ApiErrorHandler`, and the RLS-backed `document_event` table from
`db/migrations/V81__document_store.sql`. The routes are described in
`contracts/documents/document-artifacts.md`; no new route was added, so no OpenAPI edit was needed.

URLs: the mediated handle emits no URL at all and every 201 `Location` header in the two document
controllers uses an opaque identifier. No HTTP endpoint emits a signed URL. The separate
`SignedAccessPort` / `S3ObjectStore.signedDownloadUrl` capability is not called outside the storage
package; its opaque-key-only request, 60-second lifetime and four refusal paths are already proved
by `apps/api/src/test/java/com/aurevia/smartclinic/documents/storage/S3ObjectStoreTest.java`
(`signedAccessIsShortLivedAndOnlyForPromotedVerifiedObjects` and the `signedAccessIsDeniedFor*`
tests). No duplicate adapter test was added here.

Logs, analytics and notifications: static evidence only. A scoped search of
`apps/api/src/main/java/com/aurevia/smartclinic/documents` and `.../web` found no logger or
`System.out` statement carrying a payload, file name, patient field or URL; no notification,
outbox or analytics/metrics code exists in the `documents` package, and the request metrics carry
no document-payload label. The fixtures install no log appender, as required by the brief, so these
three channels are not asserted by a dedicated runtime capture. The Surefire output captured from
the green run contains only `CorrelationIdFilter` records (correlation id, method, route, status and
latency) and no marker value, which is supporting rather than decisive evidence.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=KeepProtectedClinicalPayloadsOutOrdinaryOperationalTest test`
— `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` in the Surefire report.

## Gap

The logs, analytics and notification channels are proven only by the absence of code paths and by
static search, not by a runtime assertion; proving them at runtime would need a test-scoped log
appender with a positive control and, if those channels are later wired, a dedicated notification
and analytics assertion. The signed-URL capability has no HTTP surface in this slice, so no API test
can assert its containment; its control is covered at the adapter boundary instead. Separately,
caller-supplied `ScanRequest.detail`, `CompleteRequest.note`, `RemoveRequest.reason` and
`FailRequest.detail` are persisted into the append-only audit ledger with a 200-character bound but
no content sanitisation; that is an audit-ledger invariant, not an ordinary operational log, URL,
analytics or notification channel, and is deliberately not made red here because this story may not
change production code.

Closing the residual items needs two separate follow-up tasks: one test-only story adding a
Logback list appender with a positive control (changing only the new test class) and one production
story sanitising caller-supplied ledger detail in `QuarantineService` / `PdfBindingService` with its
own tests and a migration-safe rollout.
