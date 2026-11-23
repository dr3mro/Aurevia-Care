# IAM-03 clause 2 — normalize search while preserving original spelling

## Clause

Normalize search while preserving original spelling.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.patient.clauses.NormalizeSearchWhilePreservingOriginalSpellingTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/NormalizeSearchWhilePreservingOriginalSpellingTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, using the `synthetic-auth`
profile and two synthetic tenants, following the fixtures and database setup of the
neighbouring `authz.clauses` acceptance tests. Synthetic names and MRNs only.

Test methods (one positive rule proof, three negative refusals):

- `searchNormalizesInputAndPreservesOriginalSpelling` — a receptionist registers a
  patient with the original spelling `José Álvarez`, then searches with ASCII,
  lower-cased and punctuated variants (`Jose Alvarez`, `jose alvarez`,
  `Jose-Alvarez`). Each query matches the same record, and both the HTTP response
  and the durable row read back under forced row-level security keep `displayName`
  as `José Álvarez` while `normalizedName` is `jose alvarez`. This proves both
  halves of the clause: the search input is folded for matching and the original
  spelling is never overwritten.
- `searchIsRefusedForWrongRole` — a `tenant_admin` with active membership but no
  `PATIENT_READ` permission is denied (403).
- `searchIsScopedToTheActiveTenant` — a patient whose normalized name exists only
  in organization A is invisible to an organization B search (empty result),
  proving forced RLS scopes the normalized search.
- `searchRejectsInvalidName` — a name containing a forbidden character is refused
  (422) before the search runs.

Production classes the test exercises:

- HTTP entry point: `com.aurevia.smartclinic.web.PatientController` (`GET /api/v1/patients`,
  `POST /api/v1/patients`).
- Domain service: `com.aurevia.smartclinic.patient.PatientService` (`normalize`, `search`,
  `register`, `authorize`).
- SQL boundary: `com.aurevia.smartclinic.patient.PatientRepository` (`search`, `insert`,
  `current_patients` fold).
- Value types: `com.aurevia.smartclinic.patient.Patient`,
  `com.aurevia.smartclinic.patient.PatientView`.
- Authorization and tenant scope: `com.aurevia.smartclinic.authz.ActionPolicy`,
  `com.aurevia.smartclinic.tenant.TenantContext`,
  `com.aurevia.smartclinic.web.TenantContextInterceptor`.

Where the clause is implemented today (survey result):

- `apps/api/src/main/java/com/aurevia/smartclinic/patient/PatientService.normalize(String)`
  decomposes with NFD, strips marks, lower-cases with `Locale.ROOT` and collapses
  non-letter/digit runs to spaces — folding only the search form.
  `PatientService.search(String)` normalizes the query and delegates to the
  repository, auditing the search. `PatientService.register(...)` stores the
  original `displayName` and the folded `normalizedName` side by side.
- `apps/api/src/main/java/com/aurevia/smartclinic/patient/Patient.java` carries both
  `displayName` (original spelling, preserved for display) and `normalizedName`
  (used only for search).
- `apps/api/src/main/java/com/aurevia/smartclinic/patient/PatientRepository.search(String)`
  matches `normalized_name LIKE :name || '%'` in `public.current_patients()` and maps
  back the original `display_name`.
- Schema: `db/migrations/V7__patient_registration.sql` defines `display_name` and
  `normalized_name`, the `patient_name_idx (organization_id, normalized_name,
  display_name)` index, and the `current_patients()` effective-version fold with
  forced row-level security.
- Contract: the patient routes (`GET /api/v1/patients` with the `name` query
  parameter and `POST /api/v1/patients` for registration) are currently baselined
  in `contracts/openapi.yaml` rather than declared in the primary
  `contracts/openapi.yaml`; a generated form also exists at
  `contracts/openapi/generated/openapi.yaml`. Promoting these routes into the
  primary contract is outside this clause-test task.

## Gap

None
