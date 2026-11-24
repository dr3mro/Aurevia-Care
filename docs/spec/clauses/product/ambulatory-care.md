# Ambulatory care only — PRD-01 clause 2 evidence note

## Clause

Ambulatory care only.

## Status

Partly built

## Evidence

The clause is a scope boundary, not a feature with a dedicated implementation. The repository holds the following relevant evidence; there is no class, migration, workflow, contract or configuration named for an "ambulatory-only" control, and no dedicated enforcement test.

Source of the clause:

- Section 1 describes Aurevia Cloud as a "hosted ambulatory electronic medical record, reporting and care coordination platform"; the PRD-01 row sets the boundary "Ambulatory care only" and the PRD-06 row excludes "inpatient workflows": [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md).
- The delivery record keeps the same boundary ("V1 workflow | Clinic, lab and patient access pilot first…"): [PROJECT.md](../../../../.opencode/config/PROJECT.md).

Ambulatory workflow implementation that gives the clause positive content (all synthetic/test-driven, no real patient data):

- Appointment state machine `HELD → BOOKED → CHECKED_IN → IN_PROGRESS → COMPLETED` with `CANCELLED`/`NO_SHOW` branches and a database guard trigger: [AppointmentState.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/AppointmentState.java), [AppointmentService.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/AppointmentService.java), and its transition test [AppointmentStateTest.java](../../../../apps/api/src/test/java/com/aurevia/scheduling/AppointmentStateTest.java). This is the CLN-01 visit model; there is no admission or bed state.
- Scheduling and check-in foundations with tenant-owned tables under forced RLS: [V8__scheduling.sql](../../../../db/migrations/V8__scheduling.sql), [V12__checkin_worklist.sql](../../../../db/migrations/V12__checkin_worklist.sql), [CheckInService.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/checkin/CheckInService.java) and [CheckInServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/schedulingcheckin/CheckInServiceTest.java).
- Clinical documentation and follow-up, the other two named PRD-01 activities: [ClinicalNoteService.java](../../../../apps/api/src/main/java/com/aurevia/clinical/notes/ClinicalNoteService.java), [NoteState.java](../../../../apps/api/src/main/java/com/aurevia/clinical/notes/NoteState.java) and [FollowUpService.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/followup/FollowUpService.java). The HTTP entry point for appointment booking is [AppointmentController.java](../../../../apps/api/src/main/java/com/aurevia/web/AppointmentController.java).

Negative evidence (commands executed for this note, tool evidence):

- A repository-wide search for `ambulatory|inpatient|in-patient|outpatient` found the clause only in [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) and in the tracking/task entries; no implementation artifact uses those terms.
- `grep -RniE '\b(inpatient|admission|bed|ward|lengthOfStay|patientAdmission)\b' apps/api/src/main db/migrations contracts infra .github --include='*.java' --include='*.sql' --include='*.yaml' --include='*.yml' --include='*.json'` returned no matches. A wider search over `apps` found "discharge" only as physical-therapy episode closure (a PT note kind, not an inpatient discharge) and "admission" only in a test comment about request admission to a protected endpoint — neither is an inpatient workflow.
- No workflow run in this repository asserts "ambulatory only"; none is claimed here.

## Gap

The positive ambulatory workflows (registration, booking, check-in, clinical documentation, follow-up) are implemented, but the "only" half of the clause is not independently established in this repository:

1. No machine-checkable guard or negative test asserts that the exposed state models, API surface and database schema contain no admission/bed/ward/inpatient transition. Today the boundary holds because such a feature was never built, not because a check prevents it. Closing this needs one engineering task: add a scope-boundary guard test under `apps/api/src/test/java/com/aurevia/scheduling/` (and, if an enforceable rule is agreed, the matching production guard). Adding an enforcement rule would change a public contract and needs an architecture decision first, not a doc-only edit.
2. The repository holds no signed site evidence that a production deployment is limited to ambulatory care. The G5 row "Per-site Product Owner and clinical sign-off covering workflow…" in [launch-checklist.md](../../../operations/production-readiness/launch-checklist.md) is `open` with `none yet`. The Product Owner and the site Clinical lead must supply the signed site acceptance matrix and G5 acceptance record before the boundary can be called verified for a real site.

Total: two separate tasks — one test/engineering task, one human/site-evidence item. This note changes no code, workflow, contract or migration.

## How to verify

A reviewer can check the repository-side claims with:

1. `python3 -I scripts/check-docs.py` — Markdown links and requirement IDs in this note must pass (exit 0).
2. `grep -RniE '\b(inpatient|admission|bed|ward|lengthOfStay|patientAdmission)\b' apps/api/src/main db/migrations contracts infra .github --include='*.java' --include='*.sql' --include='*.yaml' --include='*.yml' --include='*.json'` — expect no matches; any future hit must be reviewed for an inpatient workflow.
3. `mvn -q -B -f apps/api/pom.xml test -Dtest=AppointmentStateTest,CheckInServiceTest` — confirms the ambulatory visit state machine and check-in behavior still pass. This note did not run the Java suite (doc-only task inside worker scope); the integrator runs the full gate.
4. Trace one request from the booking entry point [AppointmentController.java](../../../../apps/api/src/main/java/com/aurevia/web/AppointmentController.java) through [AppointmentService.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/AppointmentService.java) to the `BOOKED → CHECKED_IN → IN_PROGRESS → COMPLETED` transitions asserted in [AppointmentStateTest.java](../../../../apps/api/src/test/java/com/aurevia/scheduling/AppointmentStateTest.java).

Before G5, a site must produce the human evidence that code cannot: the signed per-site Product Owner and clinical sign-off (workflow scope, including ambulatory-only) recorded on the G5 acceptance record, as required by the [launch checklist](../../../operations/production-readiness/launch-checklist.md). A passing local check is not that evidence.
