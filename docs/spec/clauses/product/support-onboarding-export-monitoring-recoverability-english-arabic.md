# PRD-05 clause 1 — evidence note

## Clause

Support onboarding, export, monitoring, recoverability, and English and Arabic operation.

## Status

Partly built

## Evidence

This note records only files, tests, gates and commands that exist in this
repository today. Evidence source for the commands below is the executing tool
(observed output); the remaining statements come from reading the linked files.
No human, partner, cloud-account or signed-decision evidence is held here.

### Onboarding — implemented in application code (OPS-08)

- REST entry point [`ImportController.java`](../../../../apps/api/src/main/java/com/aurevia/tenant/importing/ImportController.java)
  and service [`OnboardingImportService.java`](../../../../apps/api/src/main/java/com/aurevia/tenant/importing/OnboardingImportService.java)
  implement the versioned manifest, deterministic mapping, dry-run findings,
  controlled batches and provenance-preserving import. The package also holds
  `ImportManifest`, `DryRunReport`, `ImportBatch`, `ImportProvenance`,
  `HistoricalSignature` and related types under
  [`tenant/importing/`](../../../../apps/api/src/main/java/com/aurevia/tenant/importing/ImportController.java).
- Behaviour is tested by [`OnboardingImportServiceTest.java`](../../../../apps/api/src/test/java/com/aurevia/tenant/importing/OnboardingImportServiceTest.java)
  and [`ImportControllerTest.java`](../../../../apps/api/src/test/java/com/aurevia/tenant/importing/ImportControllerTest.java).
- Fail-closed contract evaluator [`infra/onboarding/onboarding.py`](../../../../infra/onboarding/onboarding.py)
  with gate [`infra/onboarding/check.sh`](../../../../infra/onboarding/check.sh)
  and handoff [`docs/operations/onboarding/README.md`](../../../operations/onboarding/README.md).
  Observed: `bash infra/onboarding/check.sh` → exit 0, 70 unit tests OK,
  conforming fixture passes, violation fixture rejected.

### Export / tenant lifecycle — evaluator and documents only (OPS-07)

- Fail-closed evaluator [`infra/tenant-lifecycle/tenant_lifecycle_evidence.py`](../../../../infra/tenant-lifecycle/tenant_lifecycle_evidence.py)
  and gate [`infra/tenant-lifecycle/check.sh`](../../../../infra/tenant-lifecycle/check.sh),
  documented in [`docs/operations/tenant-lifecycle/README.md`](../../../operations/tenant-lifecycle/README.md)
  and [`export-manifest.md`](../../../operations/tenant-lifecycle/export-manifest.md).
  Observed: `bash infra/tenant-lifecycle/check.sh` → exit 0, 94 unit tests OK,
  synthetic and measured-shape fixtures pass, violation fixture exits 1 with its
  named breaches.
- A search of `apps/api/src/main/java` found no application export service,
  controller or store that produces a tenant export manifest with checksums,
  authorized documents, provenance and audit history. The `export`-named Java
  files found are dictionary/localization and messaging-governance code, not a
  tenant export feature.

### Monitoring — health probes and evidence evaluators only (OPS-03, OPS-05, WEB-05, WEB-06)

- [`HealthController.java`](../../../../apps/api/src/main/java/com/aurevia/web/HealthController.java)
  serves the unauthenticated `/health/live` and `/health/ready` probes (WEB-05);
  readiness performs a status-only database round trip.
- Signal and ownership catalog in [`docs/operations/recovery/operational-runbooks.md`](../../../operations/recovery/operational-runbooks.md)
  (OPS-05), queue evidence in [`infra/ops-queues/ops_queues.py`](../../../../infra/ops-queues/ops_queues.py)
  and capacity targets in [`infra/capacity/capacity_evidence.py`](../../../../infra/capacity/capacity_evidence.py).
  Observed: `bash infra/ops-queues/check.sh` → exit 0, 104 tests OK;
  `bash infra/capacity/check.sh` → exit 0, 55 tests OK.
- A search of the application sources found no metrics emitter, dashboard,
  alerting rule or structured request-log pipeline (no Micrometer, Prometheus,
  actuator or metrics endpoint), so WEB-06 observability is not implemented.

### Recoverability — evaluator and runbooks only (OPS-04)

- Fail-closed evaluator [`infra/recovery/recovery_evidence.py`](../../../../infra/recovery/recovery_evidence.py)
  with gate [`infra/recovery/check.sh`](../../../../infra/recovery/check.sh),
  targets in [`docs/operations/recovery/README.md`](../../../operations/recovery/README.md)
  and procedure in [`restore-procedure.md`](../../../operations/recovery/restore-procedure.md).
  Observed: `bash infra/recovery/check.sh` → exit 0; build-failing coverage gate
  reports PASS, 100% line coverage (271 lines); synthetic fixtures only and a
  violation fixture rejected.
- The evaluators state explicitly that a passing synthetic artifact is not a
  drill: no real RPO/RTO measurement, restore run or reconciliation record is in
  this repository.

### English and Arabic operation — implemented and tested (UX-01, CLN-07)

- Staff web localization: [`I18nProvider.tsx`](../../../../apps/staff-web/src/i18n/I18nProvider.tsx),
  [`messages.ts`](../../../../apps/staff-web/src/i18n/messages.ts) and
  [`format.ts`](../../../../apps/staff-web/src/i18n/format.ts), with direction
  test [`I18nProvider.direction.test.tsx`](../../../../apps/staff-web/src/i18n/I18nProvider.direction.test.tsx).
- Patient app localization: [`messages.ts`](../../../../apps/patient-mobile/src/app/messages.ts)
  and direction test [`direction.test.ts`](../../../../apps/patient-mobile/src/app/direction.test.ts).
- Server-side document rendering: [`PdfRenderer.java`](../../../../apps/api/src/main/java/com/aurevia/documents/PdfRenderer.java)
  derives `RTL` from the `ar` locale and emits a direction marker; its own
  Javadoc limits this slice to structure and direction, not glyph shaping or
  font licensing. Tested by [`PdfRendererTest.java`](../../../../apps/api/src/test/java/com/aurevia/it/documents/PdfRendererTest.java).
  Catalog direction support lives in [`Direction.java`](../../../../apps/api/src/main/java/com/aurevia/catalog/dictionary/Direction.java).
- Localization/RTL contract evaluator [`infra/accessibility/ux01_evidence.py`](../../../../infra/accessibility/ux01_evidence.py)
  and gate [`infra/accessibility/check-ux01.sh`](../../../../infra/accessibility/check-ux01.sh),
  documented in [`docs/operations/accessibility/localization-and-rtl.md`](../../../operations/accessibility/localization-and-rtl.md)
  and [`docs/design/rtl-and-localization.md`](../../../design/rtl-and-localization.md).
  Observed: `bash infra/accessibility/check-ux01.sh` → exit 0, 54 tests OK.

### Delivery boundary — one market per deployment (OPS-01)

- CI delivery pipeline [`.github/workflows/delivery.yml`](../../../../.github/workflows/delivery.yml)
  and signed-build/delivery-safety material under
  [`infra/delivery-safety/check.sh`](../../../../infra/delivery-safety/check.sh).
  The requirement boundary and the pre-production decision table are in
  [`PROJECT_V1_SPEC.md`](../../../../.opencode/config/PROJECT_V1_SPEC.md) (sections 8, 8.1
  and 10). No market-specific policy pack or jurisdiction decision artifact is
  held in the repository.

## Gap

The clause is partly built: onboarding import and English/Arabic operation have
real application code and synthetic tests, whereas export, monitoring signals
and recoverability exist only as fail-closed evidence evaluators, documents and
runbooks. Every evaluator in `infra/` states that it judges an artifact and
cannot prove that a real import, export, drill, measurement or human approval
occurred. Nothing in the repository proves the requirement end to end for a live
deployment.

Missing pieces, the files that would change and the separate work needed:

1. **Runtime tenant export (OPS-07).** Add an export service and controller
   behind a store under `apps/api/src/main/java/.../tenant/exporting/` with a
   tenant-owned forced-RLS Flyway migration, an OpenAPI entry, a regenerated
   client and an HTTP-level integration test. The evaluator and handoff docs
   would be updated to reference the real entry point. One engineering task.
2. **Observability (WEB-06, OPS-05).** Add structured request logging with
   redaction, per-route rate/error/latency metrics, saturation and queue-age
   export plus alerting rules in `apps/api` and `infra/`; extend the OPS-05
   signal catalog with evidence that each signal is emitted. One engineering
   task.
3. **Conducted restore drill (OPS-04).** No measured RPO/RTO or reconciliation
   record exists; Docker is unavailable locally (see
   [`WORKSPACE.md`](../../../../WORKSPACE.md)). An authorized environment must
   produce a dated drill artifact and runbook sign-off. One human/cloud-gated
   task.
4. **Real approved onboarding import (OPS-08).** The importer exists, but no
   approved source dataset, dry-run record or named sign-off does. Operations and
   the privacy lead must supply them from an authorized environment. One
   human-gated task.
5. **Real tenant-lifecycle scenarios (OPS-07).** Record requests, exports,
   offboarding and disputed-access runs are only declared in synthetic
   artifacts; a live tenant run with privacy/clinical approval is required.
   One human-gated task.
6. **Market policy pack and Arabic production evidence (OPS-01, WEB-08,
   WEB-10).** No signed jurisdiction/hosting decision, market-specific policy
   pack, Arabic glyph-shaping/font-licensing evidence or English/Arabic RTL
   end-to-end matrix exists. Product owner, privacy lead, QA and UX must supply
   them. One signed-decision task plus one human evidence task.

That is at least seven separate tasks: two bounded engineering slices (items 1
and 2) and five human, partner, cloud-account or signed-decision slices (items
3–6, with item 6 counting as two). No task here changes code, workflows,
contracts or migrations.

## How to verify

Repository checks a reviewer can run now (all were executed for this note;
evidence source is the command output):

- `python3 -I scripts/check-docs.py` — relative links and requirement IDs.
- `bash infra/onboarding/check.sh` — OPS-08 evaluator (observed exit 0, 70 tests).
- `bash infra/tenant-lifecycle/check.sh` — OPS-07 evaluator (observed exit 0, 94 tests).
- `bash infra/recovery/check.sh` — OPS-04/OPS-05 evaluator and 100% line-coverage
  gate (observed exit 0, PASS 271 lines).
- `bash infra/capacity/check.sh` — OPS-03 evaluator (observed exit 0, 55 tests).
- `bash infra/ops-queues/check.sh` — operations queue evidence (observed exit 0,
  104 tests).
- `bash infra/accessibility/check-ux01.sh` — localization/RTL contract (observed
  exit 0, 54 tests).

Integration and full-coverage gates for a future implementation would be run by
the integrator/Delivery, not inferred from this note: targeted Java tests such as
`mvn -q -B test -Dtest=OnboardingImportServiceTest`, `bash apps/staff-web/check.sh`
and `bash apps/patient-mobile/check.sh`.

Human evidence a site must produce before G5 (specification sections 9 and 10):

- A signed market policy pack, jurisdiction and hosting-region decision, data
  handling map and named owners — product owner and privacy lead.
- An approved onboarding dataset with versioned manifest, dry-run findings and a
  named authorized sign-off — operations with the privacy lead.
- A real tenant export with manifest, schema version, checksums, authorized
  documents, provenance and audit history from a live tenant — operations and
  privacy.
- A 30-minute load report at the declared pilot profile plus a calendar-month
  availability measurement — engineering with the operations owner.
- A conducted restore drill with measured RPO ≤ 15 minutes and RTO ≤ 4 hours,
  clean-environment restore, reconciliation and reopening approval — engineering,
  operations and privacy.
- OPS-05 dashboards and on-call/clinical coverage ownership for the required
  signals, with downtime and recovery runbooks — operations.
- English and Arabic right-to-left end-to-end runs with an accessibility scan
  reporting zero serious or critical violations, plus validated English/Arabic
  PDFs and Arabic font/shaping evidence — QA, UX and clinical reviewers.
