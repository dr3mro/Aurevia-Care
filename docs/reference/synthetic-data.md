# Catalogue of synthetic test data

This page catalogues the invented data that the repository's tests actually use. It
exists so a new worker can reuse an existing fake rather than inventing a lookalike,
and so no one mistakes a test identifier for a real tenant, facility, staff member,
patient, order or person. All values below are **synthetic** by construction. This
is engineering documentation, not clinical or legal content.

Requirements: OPS-06 (separate test resources, synthetic fixtures, secret scanning)
and ARC-02 (tenant isolation proved with two synthetic tenants whose local
identifiers collide). Only data that exists in the tree is listed; each item names
the file that holds it.

## Naming rules that make fake data obvious

A value is acceptable as a test fixture only when its shape makes it unmistakably
invented. The conventions in use are:

- **Tenant IDs** are UUIDs whose groups repeat one digit or hex letter: one tenant is
  the all-`1` UUID, a second is the all-`2` UUID, a third uses stepped groups
  (`1111-2222-3333-4444-…`), and a "peer"/unknown tenant is the all-`9` stepped
  variant. Two tenants deliberately use the same *local* record IDs to prove that
  isolation comes from the tenant key, not from the local value.
- **Record IDs** (facilities, patients, orders, aggregates) are UUIDs made of a
  single repeated hex letter (`a`, `b`, `c`, `d`, …), so a reader sees at once that
  they are placeholders.
- **Human-readable local IDs** use short typed prefixes and small sequence numbers:
  `apt-` for appointments, `MRN-` / `M-` for medical record numbers, `pat-` and
  `patient-` for patients, `order-` / `ORD-` / `LAB-REQ-` for orders and
  requisitions, `ACC-` for accessions, `ext-` for external event IDs, `doc-` for
  document keys and `SYN-` for onboarding source IDs. The prefix plus a low number
  (001, 1, A) is the marker — never a real-world identifier format.
- **Subjects and actors** are role words plus a short disambiguating suffix (`-a`,
  `-b`, `-123`) or the `synthetic.` prefix (for example a reception subject, an
  admin subject, a clinician subject, an auditor subject, a patient subject and an
  "unlinked" subject). No real username, e-mail or phone number appears.
- **Display names** carry the literal word `Synthetic` in Latin script, an Arabic
  localisation that uses the Arabic word for "test/experimental", or both. A Latin
  display name is always paired with an Arabic-script value for the mixed-script
  and RTL tests. Specific personal-looking names are deliberately **not** reproduced
  in this catalogue.
- **Issuers, sources and environments** are labelled `SYNTHETIC`, `ORG-A`,
  `partner-lab`, `lab-partner`, `synthetic-onboarding-fixture` or
  `legacy-synthetic-emr`. A fixture JSON declares `"dataClass": "synthetic"` or
  `"synthetic_only": true`.
- **Tokens** are the literal word `synthetic-token-` followed by filler letters;
  they are not credentials and must never be mistaken for secrets.
- **Instants and dates** are fixed values in early 2026 so tests are deterministic;
  time zones are real zone names (`UTC`, a US Eastern zone, a UK zone) used only to
  exercise conversion and DST behaviour.

`scripts/check-secrets.py` defines what must never appear: private keys, AWS access
keys, GitHub/Slack tokens, JWTs, password/secret/API-key assignments, US
social-security-number-shaped strings, hard-coded NVD credentials and NVD keys
embedded in notes. A line that genuinely needs a lookalike value carries the
inline marker `synthetic-ok`, which
suppresses that one line. Nothing in this catalogue uses that escape hatch.

## Tenants and organizations

- Two synthetic organizations, named with the word `Synthetic`, each with a
  repeated-digit UUID, are the canonical tenants. They are defined in
  `apps/staff-web/src/fixtures/synthetic.ts` and asserted in
  `apps/staff-web/tests/fixtures/synthetic.test.ts`.
- The same two tenant UUIDs, under constant names such as `ORG`, `ORG_A`, `ORG_B`
  and `OTHER_ORG`, are used throughout the API tests, for example
  `apps/api/src/test/java/com/aurevia/staff/StaffInvitationIntegrationTest.java`,
  `apps/api/src/test/java/com/aurevia/web/ClaimsTenantResolverTest.java`
  and `apps/api/src/test/java/com/aurevia/it/exchangetask/Xch03ExchangeTest.java`.
- `apps/api/src/test/java/com/aurevia/api/contract/ApiContractPrimitivesTest.java`
  and `apps/api/src/test/java/com/aurevia/api/contract/IdempotencyLedgerTest.java`
  use stepped and reversed-stepped UUIDs for a tenant and its peer.
- Textual tenant IDs such as `org-a`, `org-b`, `org-rad` and `org-other` appear in
  pure-domain tests, for example
  `apps/api/src/test/java/com/aurevia/api/authorization/CommandAuthorizationTest.java`
  and `apps/api/src/test/java/com/aurevia/radiology/StudyLinkReviewTest.java`.
- The onboarding fixture declares issuer `ORG-A` in
  `infra/onboarding/fixtures/synthetic_onboarding_manifest.json`.

## Facilities and care units

- One synthetic facility with a repeated-letter UUID, the code `MAIN` and a name
  beginning with `Synthetic`, scoped to tenant A, is in
  `apps/staff-web/src/fixtures/synthetic.ts`; the same UUID is the `FACILITY`
  constant in
  `apps/api/src/test/java/com/aurevia/staff/StaffInvitationServiceTest.java`
  and `apps/api/src/test/java/com/aurevia/staff/JdbcStaffInvitationStoreTest.java`.
- Short facility/collection codes `MAIN`, `LAB` and `SYN` appear in
  `apps/staff-web/src/features/scheduling/fixtures.ts` (the `LAB` code belongs to
  tenant B and the `SYN` code to a generated walk-in booking).
- Tenant-scoped care units are seeded in the messaging store tests, for example
  `apps/api/src/test/java/com/aurevia/messaging/units/CareUnitDirectoryServiceTest.java`
  and `apps/api/src/test/java/com/aurevia/messaging/units/InMemoryMessagingStore.java`.

## Staff, subjects and roles

- A synthetic staff member with a `synthetic.`-prefixed subject and the `reception`
  role is in `apps/staff-web/src/fixtures/synthetic.ts`, asserted by
  `apps/staff-web/tests/fixtures/synthetic.test.ts`.
- HTTP tests pass the `X-Synthetic-Subject` header with role-like subjects (an
  admin, a reception user, a clinician, an auditor, a patient and an "unlinked"
  value) alongside `X-Active-Organization`; see
  `apps/api/src/test/java/com/aurevia/staff/StaffListControllerTest.java`,
  `apps/api/src/test/java/com/aurevia/staff/StaffInvitationControllerTest.java`,
  `apps/api/src/test/java/com/aurevia/messaging/evidence/GateEvidenceControllerHttpTest.java`
  and `apps/api/src/test/java/com/aurevia/messaging/appointments/PatientAppointmentControllerHttpTest.java`.
  The `X-Synthetic-Subject` header is only honoured under the `synthetic-auth`
  profile and is not an authentication mechanism.
- Unit tests set a `TenantContext` with subjects such as `admin-a`, `reception-a`,
  `clinician-a`, `patient-user`, `user-123` and `subject-1`; representative files
  are `apps/api/src/test/java/com/aurevia/staff/StaffServiceTest.java`,
  `apps/api/src/test/java/com/aurevia/messaging/instructions/InstructionControllerTest.java`
  and `apps/api/src/test/java/com/aurevia/web/TenantContextInterceptorJwtTest.java`.
- Invitation acceptance tests use the `synthetic-token-` prefix token; see
  `apps/api/src/test/java/com/aurevia/staff/StaffInvitationServiceTest.java`
  and `apps/api/src/test/java/com/aurevia/staff/StaffInvitationControllerTest.java`.
- Synthetic in-memory membership/role seeds are in
  `apps/api/src/test/java/com/aurevia/messaging/governance/InMemoryMessagingGovernanceStore.java`.

## Patients

- Synthetic patient records with `patient-` local IDs, `MRN-`-prefixed MRNs,
  issuer `SYNTHETIC`, fixed or explicitly-unknown dates of birth, and paired
  Latin/Arabic display names are in
  `apps/staff-web/src/features/patients/fixtures.ts`. Tenant B reuses the same MRN
  under a different issuer scope to prove issuer-scoped uniqueness.
- Reception-side registration is exercised with a synthetic tenant and a
  `reception-a` actor in
  `apps/api/src/test/java/com/aurevia/patient/PatientServiceTest.java`
  and `apps/api/src/test/java/com/aurevia/it/PatientRegistrationTest.java`.
- Repeated-letter patient UUIDs (the `a`, `b`, `c` variants) are used across the
  messaging, events, instructions and lab tests, for example
  `apps/api/src/test/java/com/aurevia/events/DurableEventServiceTest.java`,
  `apps/api/src/test/java/com/aurevia/messaging/acknowledgment/InstructionFactsTest.java`
  and `apps/api/src/test/java/com/aurevia/lab/accession/AccessionListServiceTest.java`.
- The partner event fixture uses a repeated-letter patient UUID in
  `contracts/events/fixtures/partner-events.json`.
- Patient names are never copied into documentation: only the pattern (a synthetic
  Latin name plus an Arabic-script equivalent carrying a "test" marker) is recorded.

## Orders, accessions and external events

- A short `order-1` / `order-2` identifier with an `ACC-`-prefixed accession and
  synthetic source/destination labels (`src-lab`, `dest-clinic`) is used in
  `apps/api/src/test/java/com/aurevia/it/exchangeresult/ResultRulesTest.java`
  and `apps/api/src/test/java/com/aurevia/exchange/result/ResultRepositoryInsertOrderTest.java`.
- HTTP result tests use an `ORD-`-prefixed example order ID in
  `apps/api/src/test/java/com/aurevia/it/exchangeresult/ResultControllerHttpTest.java`
  and `ORD-S`-prefixed IDs in
  `apps/api/src/test/java/com/aurevia/it/exchangeresult/ResultRoutingIntegrationTest.java`.
- The XCH-03 exchange tests build order IDs from a tenant and a small index and
  use a `LAB-REQ-`-prefixed requisition association in
  `apps/api/src/test/java/com/aurevia/it/exchangetask/Xch03ExchangeTest.java`.
- A repeated-letter report UUID and order ID are in
  `apps/api/src/test/java/com/aurevia/exchange/result/ResultRepositoryInsertReportTest.java`.
- The partner conformance fixture drives a six-revision history for `order-1` in
  `apps/api/src/test/java/com/aurevia/it/exchange/PartnerConformanceFixturesTest.java`.
- `contracts/events/fixtures/partner-events.json` holds external event IDs with an
  `ext-` prefix, an `observation.result` event type, a `partner-lab` namespace and
  scenarios for duplicate delivery, correction, out-of-order replay, a partial
  partner outage and unconfirmed identifiers. Its aggregate, order and patient IDs
  are repeated-letter UUIDs; one rejected case uses a different repeated letter.

## Fixed instants, dates and time zones

- A fixed instant, civil date and time-zone name, and a mixed-script clinical
  label, are in `apps/staff-web/src/fixtures/synthetic.ts`.
- A Monday anchor date, deliberately unordered appointment times and two time
  zones (a US Eastern zone for tenant A and a UK zone for tenant B) are in
  `apps/staff-web/src/features/scheduling/fixtures.ts`; appointment IDs use the
  `apt-` prefix and resource IDs use `room-` / `clinician-` prefixes.
- Fixed instants, including a spring-forward gap, are in
  `apps/api/src/test/java/com/aurevia/messaging/service/ServiceHoursCalculatorTest.java`.

## Other synthetic fixture files

The same conventions govern the evidence fixtures under
`infra/<domain>/fixtures/` (files named `synthetic_*_evidence.json` for the pilot,
recovery, lifecycle, UX and operations-queue rehearsals, plus
`synthetic_evidence.json` for the build rehearsal) and the onboarding manifest
`infra/onboarding/fixtures/synthetic_onboarding_manifest.json`. The latter uses
`SYN-`-prefixed source IDs, `M-`-prefixed MRNs, an obviously invented given/family
placeholder pair (a short token derived from the word "synthetic" and the word
"patient"), a fixed checksum and a `legacy-synthetic-emr` historical signature;
checksums and signatures are fixed placeholder values, never real attestation data.

## Reserved identifiers and never-use rules

These fixed values are **reserved for tests** and must never be used for real data
or copied into a non-test context:

- The repeated-digit tenant UUIDs and the stepped/reversed-step tenant UUIDs.
- The repeated-letter UUIDs used for facilities, patients, orders, aggregates and
  reports, and the `org-a`/`org-rad`-style textual tenant IDs.
- The `synthetic.`-prefixed and role-plus-suffix subjects, and every
  `X-Synthetic-Subject` value.
- The `synthetic-token-` token and the `synthetic-auth` profile name.
- The `SYNTHETIC`/`ORG-A` issuers and `partner-lab`/`legacy-synthetic-emr` source
  labels.

Real tenant IDs, real patient identifiers, real names, phone numbers, e-mail
addresses, credentials and real clinical payloads must never enter tests, fixtures,
logs or tracker notes (AGENTS.md, MEMORY.md). Two tenants sharing the same local
ID is a deliberate isolation test, not a data-quality problem.

## How to add a new fixture safely

1. Reuse an existing value or pattern from this catalogue before inventing a new
   one. Put shared web fixtures in `apps/staff-web/src/fixtures/synthetic.ts` or the
   relevant `apps/staff-web/src/features/<domain>/fixtures.ts`, API fixtures in the
   owning test class under `apps/api/src/test/java/com/aurevia/`, and
   mobile fixtures in the relevant `apps/patient-mobile/src/features/<domain>/`
   file. Keep the fixture next to the test that uses it.
2. Use a repeated-digit/letter UUID or a typed prefix plus a small number. Give a
   person-like field a `Synthetic`-prefixed Latin value and an Arabic "test"
   localisation, and never copy a real-looking name, phone number, e-mail or
   identifier into the file.
3. For an HTTP-level test, pass `X-Active-Organization` explicitly and, when a
   synthetic identity is needed, `X-Synthetic-Subject` under the `synthetic-auth`
   profile. Prove isolation with two tenants whose local record IDs are identical.
4. If a new table needs seed data, create it under a reserved Flyway version in
   `db/migrations` with row-level security, forced RLS and the non-owner runtime
   role (ARC-02); describe the seed shape here, not the literal values.
5. Do not weaken a secret scanner for convenience. If a deliberate lookalike is
   unavoidable, add the `synthetic-ok` marker to that one line with a justification
   in the review notes; never add a new exclusion or ignore comment.
6. Verify before committing: `python3 -I scripts/check-secrets.py`,
   `python3 -I scripts/check-docs.py` and the owning module's check script. Record
   the commands and their final lines in the worker notes under Test evidence.
