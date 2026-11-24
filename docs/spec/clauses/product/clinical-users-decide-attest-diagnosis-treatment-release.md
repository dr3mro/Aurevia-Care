# PRD-06 clause 1 — clinical users decide and attest

## Clause

Clinical users decide and attest to diagnosis, treatment and release.

## Status

Partly built

## Evidence

This is a repository-survey evidence note. Everything below was found by reading
files and running the read-only commands recorded at the end of this section.
No test run, CI result, clinical review or human evidence is claimed here. The
platform mechanisms that keep these three decisions human and attested are
built and synthetically tested, but the clause is not fully verified: a signed
clinical-owner decision and G5 site evidence are absent, and some entry points
are not published in the command contract (see `## Gap`).

The clause belongs to the PRD-06 boundary in
[PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md), whose exclusion
list is "No autonomous care decisions, diagnostic image viewer, in-house PACS,
controlled electronic prescribing, pharmacy inventory, full dispensing system,
inpatient workflows or offline clinical editing". The three decision classes are
named in the domain model and in supporting requirements (CLN-03, CLN-05,
LAB-02, RAD-01, PT-01, RX-02, XCH-06).

**Human decision, never system decision (the core of the clause).**

- [SpecialtyDecision.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyDecision.java) is the enum of the three PRD-06 classes (`DIAGNOSIS`, `TREATMENT`, `RELEASE`); its Javadoc states a decision is never caller-supplied but derived from the action the human attests.
- [SpecialtyActions.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyActions.java) maps four attestable actions to those decisions: `LAB_VERIFY_RELEASE → RELEASE`, `RAD_SIGN_INTERPRETATION → DIAGNOSIS`, `PT_ACCEPT_REFERRAL → TREATMENT`, `PT_SIGN_DISCHARGE → TREATMENT`. Its Javadoc records structural separation of duties: lab collectors never verify/release, radiology staff never sign an interpretation, pharmacy staff never make a diagnosis, treatment or release decision.
- [SpecialtyAttestationService.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyAttestationService.java) re-derives membership and roles inside a tenant-guarded transaction, requires the declared role to hold `SPECIALTY_ATTEST` *and* the claimed action, rejects a non-attestable action with `ACTION_NOT_ATTESTABLE`, derives `decisionFor(action)` server-side, and records an immutable attestation with human provenance. Its Javadoc cites PRD-06: a person decides and attests, and the platform does not make autonomous clinical decisions. [SpecialtyController.java](../../../../apps/api/src/main/java/com/aurevia/authz/specialty/SpecialtyController.java) publishes `POST /api/v1/specialty/attestations`.
- [CommandCatalog.java](../../../../apps/api/src/main/java/com/aurevia/api/authorization/CommandCatalog.java), [CommandAuthorization.java](../../../../apps/api/src/main/java/com/aurevia/api/authorization/CommandAuthorization.java) and [contracts/openapi/README.md](../../../../contracts/openapi/README.md) bind each command to one owning policy and generate a client that has no role/permission/actor argument; the README states the generated client "cannot" supply authority and that PRD-06 decisions remain human acts in the owning services.

**Diagnosis decisions.**

- [ImagingService.java](../../../../apps/api/src/main/java/com/aurevia/radiology/ImagingService.java) records an immutable signed interpretation (and linked addenda) for an associated study; `RAD_SIGN_INTERPRETATION` is the attested `DIAGNOSIS` action above. Its Javadoc says the images remain under partner access control and that patient/study association is the partner PACS/DICOMweb verification responsibility — the repository stores references and signatures, not images.
- [ChartService.java](../../../../apps/api/src/main/java/com/aurevia/chart/record/ChartService.java) records `PROBLEM` entries with a version-bound optional diagnosis mapping and records `clinicalAuthor` separately from the entry actor; [DiagnosisCatalogService.java](../../../../apps/api/src/main/java/com/aurevia/catalog/dictionary/DiagnosisCatalogService.java) and [DiagnosisCode.java](../../../../apps/api/src/main/java/com/aurevia/catalog/dictionary/DiagnosisCode.java) provide the versioned market catalog.

**Treatment decisions.**

- [ClinicalNoteService.java](../../../../apps/api/src/main/java/com/aurevia/clinical/notes/ClinicalNoteService.java) implements CLN-05 draft → signed: `sign(id, expectedVersion, attestation)` and `signAddendum(...)` require an explicit attestation, sign permission, correct patient context, required fields and a fresh version, and append an immutable signed revision carrying signer, attestation, signed/effective instants, content checksum and template version. `allows(...)` grants sign/amend/entered-in-error only to `clinician`; `clinical_assistant` may only create, edit and read. Its Javadoc says signing does not release content to a patient and does not render a PDF. [ClinicalNoteController.java](../../../../apps/api/src/main/java/com/aurevia/clinical/notes/ClinicalNoteController.java) publishes `/api/v1/clinical-notes/{id}/sign`, `/sign-addendum`, `/amend` and `/entered-in-error`.
- The two therapy actions above (`PT_ACCEPT_REFERRAL`, `PT_SIGN_DISCHARGE`) are the specialty treatment decisions; [RxService.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxService.java) `attest(...)` records the prescriber's attestation after re-checking market entitlement and requires the caller to be the prescribing clinician.

**Release decisions.**

- [ReleaseService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseService.java) implements XCH-06 authorization of one immutable revision to a patient: `authorize`, `liftHold`, `withdraw` and `correct` re-derive membership and role, use a version precondition for irreversible transitions, and commit state plus append-only history, notification and audit together. Its Javadoc states authorization to release is separate from transmission and from clinical acknowledgment. [AuthorizeRelease.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/AuthorizeRelease.java) derives issuer, author and tenant from context, never the body; [ReleaseActionPolicy.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseActionPolicy.java) names the deciders and readers; [PatientReleaseController.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/PatientReleaseController.java) publishes `/api/v1/releases/{id}/authorize|correct|withdraw`.
- [ReleasedReportService.java](../../../../apps/api/src/main/java/com/aurevia/messaging/reports/ReleasedReportService.java) and [ReleasedReportController.java](../../../../apps/api/src/main/java/com/aurevia/messaging/reports/ReleasedReportController.java) serve only authorized released revisions to a verified patient; [ReleaseService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseService.java) `visibleRevisions` checks the live patient chart link on each request.

**Negative boundary (the exclusions in PRD-06).**

- `RxChannel.java` (at `../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxChannel.java`) defines only `E_PRESCRIBE` (partner channel with a stable submission id) and `PRINTABLE_LOCAL` (locally approved printable prescription, never electronic delivery or pharmacy acceptance); `RxService` requires an approved channel and market entitlement and records dispensing evidence distinct from pharmacy acceptance. No controlled electronic prescribing, pharmacy inventory or full dispensing system was found.
- `ImagingService.java` (path above) keeps images with the partner PACS/DICOMweb and stores references/signatures only; no diagnostic image viewer or in-house PACS was found.
- A case-insensitive search for `PACS`, `inpatient`, `diagnostic image viewer`, `controlled electronic prescrib`, `pharmacy inventory`, `full dispensing` and `offline clinical` across `apps`, `contracts`, `infra` and `docs` returned only explanatory comments/tests and archived planning text (see commands below); no outpatient/inpatient workflow or offline clinical editing implementation was found. Absence is an observation, not a CI-proven property.

**Persistence and contract.**

- Production migrations with tenant RLS and FORCE RLS for this state: [V15__clinical_note_signing.sql](../../../../db/migrations/V15__clinical_note_signing.sql), [V24__patient_release.sql](../../../../db/migrations/V24__patient_release.sql), [V43__prescription_drafts.sql](../../../../db/migrations/V43__prescription_drafts.sql), [V44__rx02_prescription_transmission.sql](../../../../db/migrations/V44__rx02_prescription_transmission.sql), [V46__specialty_attestation.sql](../../../../db/migrations/V46__specialty_attestation.sql), [V84__report_release_store.sql](../../../../db/migrations/V84__report_release_store.sql).
- [contracts/openapi/generated/openapi.yaml](../../../../contracts/openapi/generated/openapi.yaml), [manifest.json](../../../../contracts/openapi/generated/manifest.json) and [client.ts](../../../../contracts/openapi/generated/client.ts) publish `SPECIALTY_ATTEST`, `SPECIALTY_LAB_VERIFY_RELEASE`, `RELEASE_AUTHORIZE`, `RELEASE_CORRECT` and `RELEASE_WITHDRAW` (with the other release decisions in the owning service). [delivery.yml](../../../../.github/workflows/delivery.yml) runs the Java/database verification job that executes the test suites below.

**Synthetic acceptance tests (files read, not executed here).** The entry-point-to-test traces are:

| Entry point | Test |
|---|---|
| `ClinicalNoteController` `/sign`, `/sign-addendum` → `ClinicalNoteService` | [ClinicalNoteServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/clinical/ClinicalNoteServiceTest.java) `signRequiresAttestationPermissionAndCorrectPatientContext`, `signingAppendsImmutableSignedRevisionWithProvenance`; [ClinicalNoteControllerTest.java](../../../../apps/api/src/test/java/com/aurevia/it/clinical/ClinicalNoteControllerTest.java) |
| `SpecialtyController` `POST /specialty/attestations` → `SpecialtyAttestationService` | [SpecialtyAuthorizationFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/authz/SpecialtyAuthorizationFlowTest.java) `fullAttestationFlowIsHumanAuthorizedDerivableAndAudited`, `separationOfDutiesAndRoleSpoofingAreDenied`; [SpecialtyPolicyTest.java](../../../../apps/api/src/test/java/com/aurevia/it/authz/SpecialtyPolicyTest.java) |
| `PatientReleaseController` `/authorize`, `/withdraw`, `/correct` → `ReleaseService` | [ReleaseServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangerelease/ReleaseServiceTest.java) `authorizeImmediateIsVisibleAndNotifies`, `authorizeHoldIsNotVisibleUntilReviewed`; [PatientReleaseControllerHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/it/exchangerelease/PatientReleaseControllerHttpTest.java) |
| `POST /api/v1/prescriptions/{id}/attest` → `RxService` | [RxTransmissionFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/prescription/RxTransmissionFlowTest.java), [RxContractTest.java](../../../../apps/api/src/test/java/com/aurevia/it/prescription/RxContractTest.java) |
| Radiology sign / therapy accept decisions | [ImagingServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/it/radiology/ImagingServiceTest.java), [StudyLinkReviewTest.java](../../../../apps/api/src/test/java/com/aurevia/radiology/StudyLinkReviewTest.java) |

Read-only commands run for this note and their results:

```text
$ grep -rliE '\bPACS\b|inpatient|diagnostic image viewer|controlled electronic prescrib|pharmacy inventory|full dispensing|offline clinical' apps contracts infra docs --include='*.java' --include='*.ts' --include='*.tsx' --include='*.md' --include='*.yaml' --include='*.yml'
apps/api/src/main/java/com/aurevia/radiology/ImagingService.java
apps/api/src/test/java/com/aurevia/it/radiology/ImagingServiceTest.java
apps/api/src/test/java/com/aurevia/it/radiology/RadDomainRecordsTest.java
apps/api/src/test/java/com/aurevia/radiology/ImagingControllerHttpTest.java
apps/api/src/test/java/com/aurevia/radiology/StudyLinkReviewTest.java
apps/staff-web/src/features/scheduling/fixtures.ts
docs/archive/PLAN.md
# only explanatory comments, tests and archived planning text; no excluded feature implementation

$ grep -nE '^  /api/v1/' contracts/openapi/generated/openapi.yaml | grep -iE 'note|prescri|chart|rx|release|attest|spec'
/ lab/specimens/{id}/collect, /specialty/attestations, /releases/{id}/authorize|correct|withdraw
# clinical-note and prescription decision routes are NOT in the generated command contract

$ grep -nE '^  /api/v1/' contracts/openapi.yaml | grep -iE 'note|prescri|chart|release|attest'
(no output)  # none of these decision routes is in the staff API OpenAPI file

$ ls db/migrations | grep -iE 'note|release|attest|specialt|prescri|rx|chart'
V15__clinical_note_signing.sql V24__patient_release.sql V43__prescription_drafts.sql
V44__rx02_prescription_transmission.sql V46__specialty_attestation.sql
V59__rx02_ambiguous_transition.sql V84__report_release_store.sql
```

## Gap

The platform side is partly built; the clause is not fully verified and cannot
be claimed for real-patient use. Missing:

1. **G5 human evidence (human-gated, not code).** No repository evidence shows a
   clinical or site owner authorizing these decisions for live use. Section 9
   (G5) and section 10 of the specification require the clinical safety lead and
   site medical director to approve templates, code sets, interpreting
   privileges and escalation, the privacy lead and clinical safety lead to
   approve result-release rules, and the product/clinical/engineering/privacy
   owners to sign the acceptance matrix. [contracts/openapi/README.md](../../../../contracts/openapi/README.md) states explicitly that no human, clinical or patient-participant evidence is claimed. The site clinical owners, privacy lead and operations owners must supply this before G5; it is not claimable from code. One human-gated evidence task.
2. **Signed decision for release delegation.** [ReleaseActionPolicy.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseActionPolicy.java) names `tenant_admin` alongside `clinician` as a release decider. The same README flags that delegation as needing clinical-owner sign-off before live enablement. Until that decision exists, a non-clinical role can authorize a release, which sits in tension with "clinical users decide … release". Change: `ReleaseActionPolicy` (and its tests) after the signed decision. One safety-critical task, blocked on item 1's clinical owner.
3. **Published contract and generated client for note signing and prescription attestation.** `POST /api/v1/clinical-notes/{id}/sign` and `POST /api/v1/prescriptions/{id}/attest` (and `/radiology/.../sign`, `/therapy/.../accept`) are live controllers but absent from `contracts/openapi.yaml` and `contracts/openapi/generated/*`; the generated command catalog also does not wire its gate into every POST route (see README "Source-of-truth honesty and limits"). Change: add the decision entry points to the contract, regenerate the client and add a POST-mapping ↔ catalog parity test. One cross-boundary contract task.
4. **Assurance for the exclusions.** The "no PACS viewer / inpatient / offline clinical editing / pharmacy inventory" boundary is enforced by design and absence, not by an automated test or a partner conformance artifact in this repository. Change: a negative regression test plus partner-interface evidence (PACS/DICOMweb and pharmacy contracts are themselves a section-10 decision). One partner/integration task plus partner evidence, gated before G5.

Total: **three code/contract tasks (items 2–4, item 2 blocked on a decision) plus one human-gated G5 evidence task (item 1).** Item 1 is not a code task and must be supplied by people.

## How to verify

Reviewer checks (read-only and local; no production access):

```bash
python3 -I scripts/check-docs.py                    # this note, all Markdown links and requirement IDs
grep -rliE '\bPACS\b|inpatient|offline clinical|pharmacy inventory' apps infra contracts   # must show no excluded feature implementation
grep -nE 'SPECIALTY_ATTEST|RELEASE_AUTHORIZE' contracts/openapi/generated/manifest.json     # published decision commands
grep -nE 'FORCE ROW LEVEL SECURITY' db/migrations/V15__clinical_note_signing.sql \
  db/migrations/V24__patient_release.sql db/migrations/V46__specialty_attestation.sql       # durable, RLS-bounded decision state
mvn -q -B -f apps/api/pom.xml test -Dtest=ClinicalNoteServiceTest,SpecialtyAuthorizationFlowTest,ReleaseServiceTest,RxTransmissionFlowTest \
                                                    # targeted, synthetic; integrator runs the full gate
bash db/check.sh                                    # applies production migrations and asserts RLS
```

The clause is verified end to end only when a clinician role (and no clerical
or spoofed role) can produce a signed, attested DIAGNOSIS, TREATMENT or RELEASE
decision through an HTTP entry point that is published in the contract, whose
state survives a restart under forced RLS, and whose negative decision paths
(denied role, wrong patient context, stale version, non-attestable action,
withdrawn release) are asserted by tests.

Before G5, a clinical owner, the privacy lead and each enabled site's operations
owner must produce the section 9/10 approvals: clinical templates, code sets and
interpreting privileges; result-release rules; the signed acceptance matrix; and
partner interface conformance. No such human or partner evidence exists in this
repository today, so a reviewer cannot claim "Built and verified" from these
files alone.
