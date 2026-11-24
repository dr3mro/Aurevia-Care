# PRD-01 clause 1 — one longitudinal chart

## Clause

One longitudinal chart per patient per organization, covering registration, booking, check-in, clinical documentation and follow-up.

## Status

Partly built

## Evidence

This is a repository-survey evidence note. Everything below was found by reading files and running the read-only commands recorded at the end of this section; no test run or CI result is claimed here. The constituent workflows and a coded chart core exist, but they are not yet one production-wired longitudinal chart (see `## Gap`).

Workflow pieces that exist today:

- **Registration** — [PatientController](../../../../apps/api/src/main/java/com/aurevia/web/PatientController.java) and [PatientService](../../../../apps/api/src/main/java/com/aurevia/patient/PatientService.java) implement patient registration behind `POST /api/v1/patients`; the append-only patient record and issuer-scoped MRN live in [V7__patient_registration.sql](../../../../db/migrations/V7__patient_registration.sql); HTTP/registration acceptance is in [PatientRegistrationTest.java](../../../../apps/api/src/test/java/com/aurevia/it/PatientRegistrationTest.java).
- **Booking / scheduling** — [AppointmentService](../../../../apps/api/src/main/java/com/aurevia/scheduling/AppointmentService.java) and [SchedulingAdminService](../../../../apps/api/src/main/java/com/aurevia/scheduling/SchedulingAdminService.java) implement booking, rescheduling and appointment lists; the schema is [V8__scheduling.sql](../../../../db/migrations/V8__scheduling.sql); acceptance tests are [SchedulingBookingTest.java](../../../../apps/api/src/test/java/com/aurevia/it/SchedulingBookingTest.java) and [AppointmentListIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/it/scheduling/AppointmentListIntegrationTest.java).
- **Check-in** — [CheckInService](../../../../apps/api/src/main/java/com/aurevia/scheduling/checkin/CheckInService.java) and [ClinicController](../../../../apps/api/src/main/java/com/aurevia/scheduling/checkin/ClinicController.java) implement identity verification, completion and the worklist; tables are in [V12__checkin_worklist.sql](../../../../db/migrations/V12__checkin_worklist.sql); acceptance is in [CheckInWorklistIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/it/schedulingcheckin/CheckInWorklistIntegrationTest.java).
- **Clinical documentation** — [ClinicalNoteService](../../../../apps/api/src/main/java/com/aurevia/clinical/notes/ClinicalNoteService.java) and [ClinicalNoteController](../../../../apps/api/src/main/java/com/aurevia/clinical/notes/ClinicalNoteController.java) implement draft/sign/amend/entered-in-error note revisions; the schema is [V15__clinical_note_signing.sql](../../../../db/migrations/V15__clinical_note_signing.sql); acceptance is in [ClinicalNoteControllerTest.java](../../../../apps/api/src/test/java/com/aurevia/it/clinical/ClinicalNoteControllerTest.java) and [ClinicalNoteRevisionIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/it/clinical/ClinicalNoteRevisionIntegrationTest.java).
- **Follow-up** — [FollowUpService](../../../../apps/api/src/main/java/com/aurevia/scheduling/followup/FollowUpService.java), [FollowUpController](../../../../apps/api/src/main/java/com/aurevia/scheduling/followup/FollowUpController.java) and the durable [JdbcFollowUpStore](../../../../apps/api/src/main/java/com/aurevia/scheduling/followup/JdbcFollowUpStore.java) implement suggestions and care-team tasks; their tables and forced RLS are in [V74__follow_up_suggestions.sql](../../../../db/migrations/V74__follow_up_suggestions.sql); HTTP acceptance is in [FollowUpHttpIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/scheduling/followup/it/FollowUpHttpIntegrationTest.java).

Longitudinal chart core that exists today:

- [ChartService](../../../../apps/api/src/main/java/com/aurevia/chart/record/ChartService.java) records and verifies `PROBLEM`, `ALLERGY`, `MEDICATION` and `VITAL` entries and exposes `listForPatient`; [ChartController](../../../../apps/api/src/main/java/com/aurevia/chart/record/ChartController.java) publishes `GET/POST /api/v1/chart/records/...`; [ChartView](../../../../apps/api/src/main/java/com/aurevia/chart/record/ChartView.java) and [ChartEntry](../../../../apps/api/src/main/java/com/aurevia/chart/record/ChartEntry.java) are the read model. Tenant scope comes from the active organization's forced RLS, so a chart is per patient per organization.
- [ChartRecordIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/it/chart/record/ChartRecordIntegrationTest.java) exercises the HTTP entry point end to end: provenance, version-bound verification, concurrent verification, cross-tenant concealment and fail-closed authorization (synthetic fixtures only).
- Staff web renders the chart in [LongitudinalChart.tsx](../../../../apps/staff-web/src/features/chart/LongitudinalChart.tsx) through [api.ts](../../../../apps/staff-web/src/features/chart/api.ts); the screen is documented in [screens-and-navigation.md](../../../../docs/design/screens-and-navigation.md).

Read-only commands run for this note and their results:

```text
$ grep -rl "chart_entry" db/migrations/
(no output)  # chart_entry is NOT defined in any production migration
$ grep -n "/api" contracts/openapi.yaml | grep -i "chart\|clinical-note"
(no output)  # chart and clinical-note routes are absent from the contract
$ grep -n "/api" contracts/openapi/generated/openapi.yaml | grep -i "chart\|clinical-note"
(no output)  # and from the generated contract/client
$ find docs/spec -type f
(no output)  # no clause notes existed before this one
```

Two in-repository comments corroborate the gaps: [V13__longitudinal_chart.sql](../../../../apps/api/src/test/java/com/aurevia/it/chart/record/migrations/V13__longitudinal_chart.sql) says "Test-scoped migration for S-G1-03 ... production sequencing into db/migrations belongs to the integrator"; and [api.ts](../../../../apps/staff-web/src/features/chart/api.ts) says "Chart, note and patient commands are not published there yet" and that "Notes and documents are fetched by separate workflows and are intentionally empty on this slice."

## Gap

The clause asks for **one** chart that spans all five workflow areas. Today the five areas are separate workflows, and the coded chart core covers only problems, allergies, medications and vitals. Missing:

1. **Production persistence of the chart core.** The `chart_entry` table (RLS + FORCE RLS, tenant `WITH CHECK`, indexes) exists only as a test-scoped migration under `apps/api/src/test/.../it/chart/record/migrations/V13__longitudinal_chart.sql`; no production migration in `db/migrations/` defines it. The API chart routes therefore cannot store data that survives a production restart. Change: add the reserved `V13__longitudinal_chart.sql` (or next free reserved version) to `db/migrations/` under the OPS-06 checker, keep the test migration in step with it, and run `db/check.sh`. One cross-boundary database + integration task.
2. **Published contract and generated client.** `GET/POST /api/v1/chart/records/...` and `/api/v1/clinical-notes/...` are not in `contracts/openapi.yaml` or `contracts/openapi/generated/`; the staff web client works around this by borrowing a header descriptor from an unrelated path. Change: add the chart and note paths to `contracts/openapi.yaml`, regenerate `contracts/openapi/generated/*` via the contract check, and remove the unpublished-route workaround in `apps/staff-web/src/features/chart/api.ts`. One cross-boundary contract task.
3. **Unification of the five workflows into the chart read model and UI.** `ChartView`/`toChart` intentionally leave notes and documents empty, and registration, booking, check-in and follow-up are read through their own services rather than projected into the one per-patient-per-organization chart. Change: extend the chart projection and repository plus the staff-web chart domain (`domain.ts`, `LongitudinalChart.tsx`, `ChartScreen.tsx`) to surface those records with provenance, and add tests that a single chart request returns them. One product/chart task.
4. **G5 human evidence.** No repository evidence shows a site authorizing or clinically accepting this chart for real-patient use. The G5 launch row of [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) requires each site to approve its workflow, release policy and downtime drills before launch. This is not a code gap and is not claimable from the repository; a clinical owner and each site's operations owner must supply it before G5.

Total: **three separate code tasks plus one human-gated G5 evidence task**. Item 1 must land before item 3 can be verified end to end.

## How to verify

Reviewer checks (read-only and local; no production access):

```bash
python3 -I scripts/check-docs.py                    # this note and all Markdown links/IDs
grep -rl chart_entry db/migrations/                 # must list a production migration (currently none)
grep -n 'chart/records\|clinical-notes' contracts/openapi.yaml contracts/openapi/generated/openapi.yaml \
                                                    # must list the chart and note paths (currently none)
bash db/check.sh                                    # applies production migrations and asserts RLS on PostgreSQL
mvn -f apps/api/pom.xml -q -B test -Dtest=ChartRecordIntegrationTest   # HTTP chart entry point (needs local PostgreSQL)
bash apps/staff-web/check.sh                        # typecheck, lint and chart-screen tests
```

The chart is verified end to end only when: a production Flyway migration defines `chart_entry` with forced RLS; `grep` finds the chart and note routes in the contract and generated client; the chart HTTP integration test passes against that production schema; and a single chart read returns the registration, booking, check-in, clinical-note and follow-up records for one patient in the active organization.

Before G5, a clinical owner and each enabled site's operations owner must produce the workflow, release-policy and downtime-drill approvals required by the G5 launch gate in [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md); no such human evidence exists in this repository today.
