# Self-access only — PRD-03 clause 2

## Clause

Self-access only.

## Status

Partly built

## Evidence

The clause is the boundary of PRD-03: the patient app and patient API expose only the authenticated patient's own verified, linked chart. No patient-side proxy, dependent, guardian or "act for another patient" mode exists in `apps/patient-mobile` (a case-insensitive search of that tree for those terms returns no patient-facing capability; this is a keyword-based observation, not a dedicated regression test). The boundary is enforced in code, and durable link state exists for identity proofing, conversations, enrollment and reports — but not for appointments or devices (see Gap 2). The items listed under **Gap** are why this is "Partly built" rather than "Built and verified". All fixtures and identifiers below are synthetic.

Server-side enforcement — every protected patient command re-derives the caller and the link:

- [VerifiedPatientLink.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/VerifiedPatientLink.java) — port requiring the link to be re-derived from the authenticated subject and active organization on every call; an absent or suspended link fails closed and a client-supplied patient identifier or demographic is never a substitute.
- [PatientAppointmentService.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/PatientAppointmentService.java) and [PatientAppointmentController.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/PatientAppointmentController.java) — `/api/v1/patient/appointments`; the subject comes from `TenantContext` and each command re-checks the verified link (403 `no verified patient link`).
- [ReleasedReportService.java](../../../../apps/api/src/main/java/com/aurevia/messaging/reports/ReleasedReportService.java) and [ReleasedReportController.java](../../../../apps/api/src/main/java/com/aurevia/messaging/reports/ReleasedReportController.java) — `/api/v1/patient/reports`; list and download re-derive tenant and verified link, require `verified && active`, and return 404 when the report's patient is not the link's patient, with an audit event for every outcome.
- [PatientChartLink.java](../../../../apps/api/src/main/java/com/aurevia/messaging/reports/PatientChartLink.java) — `verified`/`active` flags re-read on every protected action; an unlink ends future access to that chart immediately.
- [ConversationService.java](../../../../apps/api/src/main/java/com/aurevia/messaging/conversations/ConversationService.java) — a patient may address a unit only through a verified, active connection; the caller subject must equal the patient subject; stored patient/unit/connection identities are derived from the server-verified connection, never from the request body, and a revoked connection stops new messages.
- [PatientAccessService.java](../../../../apps/api/src/main/java/com/aurevia/messaging/enrollment/PatientAccessService.java) and [EnrollmentActions.java](../../../../apps/api/src/main/java/com/aurevia/messaging/enrollment/EnrollmentActions.java) — privacy-safe enrollment, recovery, lockout, sensitive change and support; the chart-access action is patient-only and object-level checks reject a command aimed at another patient's account.
- [AttachmentService.java](../../../../apps/api/src/main/java/com/aurevia/messaging/attachments/AttachmentService.java) — membership and verified-link state are rechecked on each protected file request.
- [PatientReleasedRevisionsController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/PatientReleasedRevisionsController.java) — `/api/v1/patients/{patientId}/released-revisions`; its contract states that the caller's link to the exact path patient must be verified.

Durable verified-link state (survives a restart):

- [V10__patient_identity_proofing.sql](../../../../db/migrations/V10__patient_identity_proofing.sql) and [IdentityProofingService.java](../../../../apps/api/src/main/java/com/aurevia/patient/identity/IdentityProofingService.java) — `patient_account` and append-only `account_chart_link`; chart links originate from site verification, not from demographics.
- [V33__conversation_lifecycle.sql](../../../../db/migrations/V33__conversation_lifecycle.sql) — `public.patient_link` (subject, patient, active) and `patient_link_active()`, with forced RLS.
- [V83__patient_access_store.sql](../../../../db/migrations/V83__patient_access_store.sql) and [JdbcPatientAccessStore.java](../../../../apps/api/src/main/java/com/aurevia/messaging/enrollment/JdbcPatientAccessStore.java) — account `linked_chart_ids`, single-use invitation and token hashes, tenant-owned with forced RLS.
- [V84__report_release_store.sql](../../../../db/migrations/V84__report_release_store.sql) and [JdbcReportReleaseStore.java](../../../../apps/api/src/main/java/com/aurevia/messaging/reports/JdbcReportReleaseStore.java) — `report_chart_link` with `verified` and `active` flags, tenant-owned with forced RLS.

No equivalent table or `Jdbc*` adapter exists for appointment or device links (Gap 2).

Patient app (self-only surface, no persistent clinical cache):

- [PatientResource.tsx](../../../../apps/patient-mobile/src/app/PatientResource.tsx) — one request at a time, late responses discarded, no clinical data kept between requests, 401 ends the session.
- [providers.ts](../../../../apps/patient-mobile/src/app/providers.ts) — the patient client loads `/api/v1/patient/appointments` and `/api/v1/patient/reports`; its comment states that `/api/v1/appointments` is a staff route and patient data comes only from the patient-scoped route.
- [units/types.ts](../../../../apps/patient-mobile/src/features/units/types.ts) — relationships are independently server-verified; demographic similarity, a shared phone or a directory listing never establishes a connection.
- [enrollment/model.ts](../../../../apps/patient-mobile/src/features/enrollment/model.ts) — single-use invitation shape and generic error mapping that does not disclose whether an account or code exists.
- Screens: [AppointmentListScreen.tsx](../../../../apps/patient-mobile/src/features/appointments/AppointmentListScreen.tsx), [ReleasedReportsScreen.tsx](../../../../apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tsx), [ConversationInbox.tsx](../../../../apps/patient-mobile/src/features/conversations/ConversationInbox.tsx).

Test sources in the repository (cited as file evidence; these suites were not re-executed in this documentation-only task):

- [PatientAccessTest.java](../../../../apps/api/src/test/java/com/aurevia/it/messagingenrollment/PatientAccessTest.java) — `patientCannotRecoverAnotherPatientsAccountEvenWithAValidCode` and `patientCannotApplySensitiveChangeOrCheckAccessOnAnotherPatientsAccount` assert 404/NOT_FOUND when a patient aims a valid recovery code or step-up token at a peer's account; the source comment names PRD-03 self-access and IAM-01.
- [ReleaseServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangerelease/ReleaseServiceTest.java) — `verifiedPatientSeesOnlyTheirOwnCurrentlyVisibleRevisions`, `patientLinkedToAnotherChartCannotEnumerateThisPatient` and `visibleRevisionsDeniesReceptionAndAuditorEnumeration` prove a verified link to the exact chart is required and that the patient query is not a staff enumeration surface.
- [PatientAppointmentControllerHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/messaging/appointments/PatientAppointmentControllerHttpTest.java) and [ReleasedReportControllerHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/messaging/reports/ReleasedReportControllerHttpTest.java) — HTTP-level entry points through the controllers (401 unauthenticated, 403 unlinked, 200 for the linked patient), which is the entry-point-to-test trace for this boundary.
- [PatientAppointmentServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/messaging/appointments/PatientAppointmentServiceTest.java), [ReleasedReportServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/messaging/reports/ReleasedReportServiceTest.java), [ConversationServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/messagingconversations/ConversationServiceTest.java) and [JdbcPatientAccessStoreIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/messaging/enrollment/it/JdbcPatientAccessStoreIntegrationTest.java) — service-branch and durable-store coverage.

No GitHub Actions run number is claimed: the evidence above is repository files and test sources only.

## Gap

1. **Patient self-service routes are absent from the published API contract.** The controllers expose `/api/v1/patient/appointments`, `/api/v1/patient/reports`, `/api/v1/patient/files`, `/api/v1/patient-instructions`, `/api/v1/conversations`, `/api/v1/patient-identity` and `/api/v1/patients/{patientId}/released-revisions`, but [openapi.yaml](../../../../contracts/openapi/generated/openapi.yaml), [client.ts](../../../../contracts/openapi/generated/client.ts) and [manifest.json](../../../../contracts/openapi/generated/manifest.json) contain only staff-facing paths such as `/api/v1/patients` and `/api/v1/appointments`. The contract is generated from [CommandCatalog.java](../../../../apps/api/src/main/java/com/aurevia/api/authorization/CommandCatalog.java) by [CommandClientGenerator.java](../../../../apps/api/src/main/java/com/aurevia/api/authorization/CommandClientGenerator.java). Without a contract entry and regenerated typed client, the self-access surface has no published wire contract and does not meet the repository's "done means working end to end" bar. Files that would change: the command catalog, the three generated contract files and the patient app's typed clients. **One code task.**

2. **Appointment and device patient self-access is not durable.** The production constructor of [PatientAppointmentController.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/PatientAppointmentController.java) wires [SyntheticPatientAppointmentPorts.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/SyntheticPatientAppointmentPorts.java), and the Javadoc of [VerifiedPatientLink.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/VerifiedPatientLink.java) states that no verified-link table exists for this slice, so the appointment guarantee is process-local and is lost on restart. Durable link tables cover identity proofing (V10), conversations (V33), enrollment (V83) and reports (V84), but there is no verified-link table for appointments or devices. Files that would change: a new tenant-owned forced-RLS migration for appointment verified links and device tokens, a `Jdbc*` adapter behind `VerifiedPatientLink`/[PatientAppointmentStore.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/PatientAppointmentStore.java)/[DeviceTokenStore.java](../../../../apps/api/src/main/java/com/aurevia/messaging/appointments/DeviceTokenStore.java), and controller rewiring. A free reserved migration version must be obtained from the orchestrator; none was added here. **One code task.**

3. **Report self-access decisions are not durably auditable.** [ReleasedReportService.java](../../../../apps/api/src/main/java/com/aurevia/messaging/reports/ReleasedReportService.java) records each allow/deny through `AuditSink`, but the wired implementation is its nested `InMemoryAuditSink`, annotated `@Component`, so access decisions are lost on restart and cannot be produced as later evidence. Files that would change: a `Jdbc*` implementation of `ReleasedReportService.AuditSink`, transaction-boundary wiring and, if no suitable table exists yet, a tenant-owned forced-RLS migration. **One code task.**

4. **No human/site evidence is in the repository.** The complementary approved manual process for proxy, dependent and other record requests is the subject of a separate clause and no such document or sign-off is present here. Before G5 the site privacy/clinical owner and Product Owner must supply the approved manual-process policy and a recorded check that the patient app exposes self-access only; no code in this repository can produce that evidence. **One site/human evidence item.**

Total: three code tasks and one site/human evidence item (four separate pieces of work).

## How to verify

A reviewer, working from the repository root, runs:

- `python3 -I scripts/check-docs.py` — relative links and requirement IDs in this note must resolve.
- `bash scripts/worker-check.sh` — fast gate plus PostgreSQL integration suites, database policy checks, frontend standards and SpotBugs (the worker gate does not run JaCoCo).
- `bash apps/api/check.sh` — canonical Java gate with enforced 100% JaCoCo; the reviewer/integrator runs this (it is deliberately not run by the swarm worker).
- `mvn -f apps/api/pom.xml -q -B -Pfast test -Dtest=PatientAppointmentControllerHttpTest,ReleasedReportControllerHttpTest` — targeted MockMvc entry-point checks for the patient appointments and released-reports routes.
- `bash apps/patient-mobile/check.sh` — pinned install, `tsc --noEmit`, lint and Jest with 100% coverage for the self-only patient app.
- `grep -rniE 'proxy|dependent|guardian|switch.patient|on behalf' apps/patient-mobile/src` — must show no patient-facing proxy capability (only incidental words such as "independently" or `http-proxy-agent` in the lockfile). This is a keyword check, not a positive assertion: no patient-app test currently asserts the absence of a proxy mode, so a dedicated regression test is still needed.

Human evidence required before G5: the site privacy/clinical owner publishes the approved manual process for proxy, dependent and other record requests, and records a check that the patient app offers only self-access; Product Owner accepts it against this note. No such evidence exists in the repository today, so this clause cannot be called "Built and verified" until the three code tasks and that site item are complete.
