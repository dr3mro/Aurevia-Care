# X-03 document artifact contract (ARC-06)

Synthetic, implementation-facing contract for upload validation, private
quarantine, mediated downloads and PDF binding to immutable revisions. The
public REST surface is owned by the API versioning story; this document
describes the domain commands exposed by `com.aurevia.documents`
and the observable guarantees each command must keep.

## Entry points and guarantees

| Command | Entry point | Observable result | Facts |
|---|---|---|---|
| Upload | `QuarantineService.upload(Actor, UploadRequest)` | Artifact in `QUARANTINED` with `PENDING` verdict, private `quarantine/...` key, version 1 and object version 1 | `uploaded` |
| Scan | `QuarantineService.scan(Actor, id, ScanRequest, version)` | `CLEAN` or `FAILED`, version advanced by one | `scan_clean` or `scan_failed` |
| Complete | `QuarantineService.complete(Actor, id, CompleteRequest, version)` | `COMPLETED` only for a clean artifact whose `(objectKey, objectVersion, checksum)` verifies | `upload_completed` |
| Remove | `QuarantineService.remove(Actor, id, RemoveRequest, version)` | Terminal `REMOVED` for a draft or failed artifact owned by the uploader | `artifact_removed` |
| Download | `QuarantineService.download(Actor, id, version)` | `MediatedDownload` metadata handle; no bytes, file name or URL | `downloaded` |
| View | `QuarantineService.view(Actor, id)` | Artifact plus its tenant-scoped fact trail; other tenants concealed | none |
| Render request | `PdfBindingService.requestRender(Actor, RenderRequest)` | `REQUESTED` job for an exact `(revisionId, revisionVersion, contentChecksum)`; idempotent per revision, template and language | `render_requested` |
| Render pickup | `PdfBindingService.pickupRender(Actor, jobId, version)` | `RENDERING` job | `render_picked_up` |
| Render finish | `PdfBindingService.finishRender(Actor, jobId, RenderFinishRequest, version)` | `COMPLETE` job with a verified private object version | `render_complete` |
| Render failure | `PdfBindingService.failRender(Actor, jobId, detail, version)` | `FAILED` job that can never be bound | `render_failed` |
| Bind | `PdfBindingService.bind(Actor, BindRequest)` | Immutable `PdfBinding` to the exact revision; a second job for the same revision conflicts | `pdf_bound` |
| Release | `PdfBindingService.release(Actor, ReleaseRequest)` | Idempotent `ReleaseDecision`, or a blocked release with no decision | `released` or `release_blocked` |

## Invariants

- Media types are limited to PDF, JPEG and PNG. Declared content type, leading
  bytes and file extension must agree; size is 1 byte to 10 MiB; checksum is a
  lowercase SHA-256.
- Upload context, patient owner and organization come from a server-seeded
  `DocumentAuthorization`, never from the command. Every protected call
  re-derives the actor's active roles; denials append an audit fact before the
  exception and never contain a payload, original file name or URL.
- Two tenants with identical local identifiers cannot read each other's
  artifacts, jobs, bindings, releases or facts; cross-tenant reads return 404.
- Every state change is compare-and-set on the version the caller read. A
  racing writer is refused with 412 rather than overwriting committed state.
- A release that requires a PDF is refused with 409 and no `ReleaseDecision`
  when the binding is missing, belongs to a different revision version or
  checksum, or is backed by an unverified object. A corrected revision needs a
  fresh render and binding before it can be released.

## Requirement mapping

- ARC-06: all upload, quarantine, object-version, mediated-download and
  PDF-binding guarantees above.
- ARC-01, ARC-02: tenant-owned opaque keys, organization-scoped reads and
  fail-closed authorization.
- IAM-01: role-action policy and fresh caller identity on every command.
- OPS-02: append-only, sanitized facts for success, denial and conflict.
