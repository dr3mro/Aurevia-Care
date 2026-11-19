# SmartClinic V1 queue

Check the repository against each gate before marking work ready. Split items into verifiable WIP-one slices. Gates are delivery checkpoints and may have independent slices.

| Gate | Planned work | Acceptance focus |
|---|---|---|
| G0 | Inventory repository. Bootstrap synthetic test foundations and implement IAM-01/02, ARC-02 and audit basics. Continue remaining tenancy and identity requirements. | Independent tenant membership and data isolation, denial after membership revocation, durable audit |
| G1 | CLN-01–07 and applicable identity and document requirements | Clinic scheduling, intake, signed chart, safe consolidation, localized PDFs |
| G2 | XCH-01–07 and LAB-01–02 | Requests, accession, corrections, critical escalations, reconciliation and release |
| G3 | PAT-01–03, MSG-01–07, ARC-07 and patient-facing operational controls | Patient identity, isolated unit connection and inbox, files, revised instructions, acknowledgment and revocation on iOS/Android |
| G4 | RAD-01, PT-01, RX-01–02 and FIN-01 | Specialty end-to-end flows, role permissions and repeatable payment ledger |
| G5 | Remaining OPS requirements and approvals for each enabled site | Restore, performance, privacy, clinical, partner and launch evidence |

Use `planned → in progress → verified → done`. Record `blocked` with reason and prerequisites whenever applicable. Record remaining acceptance IDs and evidence in PROGRESS.md. Use BUGS.md for reproducible defects.

## Current story

S-G0-01 · staff user of the active organization · read and write only the active tenant's configuration records and be denied on the next protected request after revocation · proves the G0 trust boundary before any clinical code · IAM-01, IAM-02, ARC-02, OPS-02 · G0 · synthetic two-tenant fixtures, real PostgreSQL and auth-derived tenant context · acceptance N1–N4 and E1–E8 recorded in PROGRESS.md · test-first HTTP integration through `GET/POST /api/v1/facilities` before the smallest passing slice · Product Owner definition; architect, Spring Security, DevSecOps and independent QA review; synthetic only · **in progress**.

Follow-on G0 stories, in order: S-G0-02 staff lifecycle and immutable membership history (IAM-02, depends on S-G0-01); S-G0-03 tenant configuration, catalog, broader audit and infrastructure (ADM-01, OPS); S-G0-04 search/files/workers/export isolation and concurrent-connection tests (ARC-02, G0).

## Story template

`ID · role · need · value · specification IDs · gate · dependencies · acceptance scenarios · test plan · ownership · feedback source · status`. Keep one current story. Queue improvement ideas with a reporter and rationale. Acceptance monitors and human sign-offs stay linked to the story.
