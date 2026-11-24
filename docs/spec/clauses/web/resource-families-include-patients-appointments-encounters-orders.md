# ARC-03 clause 2 — Resource families

## Clause

Resource families include patients, appointments, encounters, orders, reports, referrals, prescriptions, releases, files, invoices, patient-unit connections, conversations, messages, instructions and audit events.

## Status

Partly proven

## Evidence

Evidence source: **tool** (executed JUnit suite against the committed contract and catalog) plus **static** discovery by grep of the controllers under `apps/api/src/main`. No simulated or human review evidence is claimed.

Test class: `com.aurevia.web.clauses.ResourceFamiliesIncludePatientsAppointmentsEncountersOrdersTest`
(`apps/api/src/test/java/com/aurevia/web/clauses/ResourceFamiliesIncludePatientsAppointmentsEncountersOrdersTest.java`),
a no-Spring, no-database suite that parses `contracts/openapi.yaml` with the same SnakeYAML
helper pattern as `contract/OpenApiContractRulesTest` and reads `CommandCatalog.synthetic()`.
All four tests are active and assert outcomes; none is skipped.

- `everyImplementedResourceFamilyHasAnApiV1ContractPath` (rule met) — asserts a versioned
  contract path for each of the 13 families that exist today. The family-to-path mapping is:

  | Family | Contract path | Note |
  |---|---|---|
  | patients | `/api/v1/patients` | top-level collection |
  | appointments | `/api/v1/appointments` | top-level collection |
  | orders | `/api/v1/orders` | top-level collection; order-entry and exchange sub-routes also exist |
  | reports | `/api/v1/radiology/reports` | sub-namespace route; `/api/v1/patient/reports` also exists |
  | prescriptions | `/api/v1/prescriptions` | top-level collection |
  | releases | `/api/v1/releases` | top-level collection; `/api/v1/lab/releases` also exists |
  | files | `/api/v1/patient/files/upload` | sub-namespace route; `/api/v1/staff/attachments/*` also exists |
  | invoices | `/api/v1/finance/invoices` | sub-namespace route |
  | patient-unit connections | `/api/v1/patient-unit-connections` | top-level collection |
  | conversations | `/api/v1/conversations` | top-level collection |
  | messages | `/api/v1/staff/mail/messages/{id}` | sub-namespace route; instant messages also exist |
  | instructions | `/api/v1/patient-instructions` | sub-namespace route; `/api/v1/instructions/*` commands also exist |
  | audit events | `/api/v1/audit-events` | top-level collection |

  The clause names families, not path shapes, so a real sub-namespace route is counted as
  present for reports, files, messages and instructions; the rationale is recorded here so
  the 13/15 classification is auditable.
- `anUnknownResourceFamilyIsRejectedByTheContractAndTheCommandCatalog` (negative) — a family
  outside the clause (`/api/v1/immunizations`) is absent from the published contract and an
  unregistered command id is refused by `CommandCatalog.find`; a `registerPatient` positive
  control proves both lookups can succeed.
- `encountersAndReferralsHaveNoTopLevelResourceRouteYet` (negative / gap characterisation) —
  an **enabled** assertion records that neither `/api/v1/encounters` nor `/api/v1/referrals`
  exists, and that the registered `acceptTherapyReferral` catalog path
  `/api/v1/therapy/referrals/{id}/accept` is not published in the contract either. It flips
  when a route lands so the next task must replace it with a presence proof.
- `familyResourcePathsNeverSelectATenantByPath` (negative) — none of the 13 family paths
  embeds either synthetic organization id; a future tenant-selector path would fail here.

Production classes and files exercised by the test:

- `com.aurevia.api.authorization.CommandCatalog` — `synthetic()` and `find(String)`
  are the command-registry boundary used for the negative and gap assertions.
- `com.aurevia.api.authorization.CommandDefinition` — its `path()` accessor names
  the referral command route.
- `contracts/openapi.yaml` — its published path keys are the inventory authority.

Discovery-only evidence (cited, not executed by this suite): `web/PatientController`
(`/api/v1/patients`), `web/AppointmentController` (`/api/v1/appointments`),
`web/AuditController` (`/api/v1/audit-events`), `orderentry/place/PlaceOrderController`
(`/api/v1/order-entry`), `finance/FinanceController` (`/api/v1/finance/invoices`),
`radflow/report/ReportController`, `prescription/RxController` and `PrescriptionController`,
`exchange/release/PatientReleaseController` (`/api/v1/releases`),
`messaging/attachments/AttachmentController` (`/api/v1/patient/files`),
`messaging/conversations/ConversationController`, `messaging/instructions/InstructionController`,
`messaging/enrollment/PatientAccessController` (`/api/v1/patient-unit-connections`) and
`staffmsg/mail/MailController` (messages). Encounters appear only through
`clinical/queues/UnsignedEncounterQueue` and the `/api/v1/clinic/appointments/{id}/open-encounter`
sub-route; referrals only through the `CommandCatalog` entry above.

Verified locally:

```
AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security \
  -Dtest=ResourceFamiliesIncludePatientsAppointmentsEncountersOrdersTest test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

All four tests are active and assert outcomes; none is skipped. Because this task adds no
production line, branch, method or class, Java JaCoCo coverage is unchanged; the coverage
command and result are recorded in the worker notes.

## Gap

Two of the fifteen families are not exposed as resource routes today:

1. **Encounters** — no `/api/v1/encounters` collection or item route exists. Encounters appear
   only as a `clinical/queues/UnsignedEncounterQueue` projection and the
   `/api/v1/clinic/appointments/{id}/open-encounter` command. Closing this needs a durable
   tenant-owned encounter store with forced row-level security, an injected service behind a
   thin controller, an HTTP-level integration test, and the OpenAPI entry with a regenerated
   staff-web client.
2. **Referrals** — no `/api/v1/referrals` resource route exists. `acceptTherapyReferral` is
   registered in `CommandCatalog` at `/api/v1/therapy/referrals/{id}/accept`, but that path is
   not published in `contracts/openapi.yaml`, so it is a command definition rather than an
   exposed resource family. Closing this needs the referral read/write routes published in the
   contract with a regenerated client and an HTTP-level test through the owning controller.

These need at least **two separate owner-approved tasks** (one per family); the encounter task
must reserve its own migration version. No production code, migration, existing test, contract
entry or dependency was changed by this story, and no version is reserved here.
