# Finished tasks

Moved out of TODO.md by `swarm.py archive-todo`. Their ids still satisfy `depends`.

- [x] DLV-04 · Repair integration tests: clinical notes, result routing, access, documents, specimens and trending
  - pr: #203
  - attempts: 0
  - requirements: CLN-03, LAB-02, IAM-03, DOC-01
  - depends: DLV-03
  - scope: apps/api, db
  - details:
    Classes still red on `main` after DLV-01 (Delivery run 460, Tests run 2087, Failures 64, Errors 4) that no other DLV task lists: `ClinicalNoteRevisionIntegrationTest` (14, every one is a PostgreSQL `syntax error at or near "SELECTid"`: a query string loses the space between `SELECT` and `id`, probably a text-block or string concatenation; fix it at the source), `ResultRoutingIntegrationTest` (8: for example a review state compared as `unreviewed` but stored as `UNREVIEWED`, and an `ORDER_EXISTS` conflict on concurrent order registration), `AccessIntegrationTest` (6), `DocumentIsolationTest` (5), `SpecimenAccessionIsolationTest` (3) and `TrendingTest` (1, `invalidInputsFailAtTheBoundary`).
    Shared causes seen in the same run, which DLV-02 and DLV-03 also touch: the trigger function `public.set_link_actor()` assigns `NEW.created_by` on tables without that column (`record "new" has no field "created_by"`, 11 tests, also behind several classes above), and inserts into `duplicate_review` fail on a null `id`. If one of those is already fixed on `main` when you start, rebase and keep only what is still red. Start from a clean checkout of current `main`, run the real suite (`bash apps/api/check.sh`, PostgreSQL via `OREVIA_PG_ADMIN`), and reproduce each failure red first. Decide whether the test or the production code is wrong and fix the real cause. Never weaken, skip, delete or re-tag a test, and never exclude a class from the gate. Read `apps/api/target/surefire-reports` for the stack trace behind each failure. Do not edit migrations already applied on `main`; add a new migration only when the schema is genuinely wrong. Do NOT edit `.github/`, `scripts/` or shared trackers. Record the root cause per class, commands and results in `.agent/pr-notes/<story-id>.md`. Done means these classes pass in the full `mvn verify` run.

- [x] REFACTOR-PACKAGES-02 · Rename the remaining story-ID Java packages (`api/x_01`–`api/x_03`) to business-domain packages with no behavior change (dispatch-69).
  - pr: #208
  - attempts: 0
  - requirements: none (refactor of CODING_STYLE.md package naming)
  - depends: DLV-04
  - scope: apps/api, contracts
  - details:
    Owner decisions (2026-11-20, delegated to the orchestrator): the mapping below is approved; the mechanical update of every reference, including the files under `contracts/`, is allowed (option A); the stale Flyway path in `ChartEntryIntegrationTest` is already fixed on `main`; renaming the test database `orevia_s_g1_02_it` is not part of this task.
    Why it waits: it moves about 30 files in `apps/api`, the same scope as DLV-02 to DLV-04, so it runs only after main is green again. Do not start it while any integration suite is red.
    Mapping (main packages under `apps/api/src/main/java/com/aurevia`; the test mirrors under `apps/api/src/test/java/...` follow them). `api/x_04` no longer exists, so it is not part of this task.
    | Old | New |
    |---|---|
    | `api/x_01` | `api/contract` |
    | `api/x_02` | `events` |
    | `api/x_03` | `documents` (merge into the existing package; `DocumentInputs` becomes `ArtifactInputs`) |
    How: use `git mv` so history follows, change only `package` and `import` lines and the class rename above, and update every reference to the old paths: Java sources and tests, `contracts/x_01`, `contracts/x_02` and `contracts/x_03` (rename the folders to match the new names and fix the links to them), docs and generator or check scripts under `contracts/`. Keep each contract check green (run them), and do not change the generated output's content except for the renamed names. No behavior change: no logic edits, no test edits beyond imports and package lines, and never weaken, skip or delete a test.
    Read first: `.agent/pr-notes/REFACTOR-PACKAGES.md` (35-file move list, name collisions, near-duplicates, every old-path reference). Check for a collision before merging a package into an existing one.
    Done means: no `x_0` package or folder is left under `apps/api` or `contracts`, a search for the old paths finds nothing, and `mvn verify` with the integration tier, `bash apps/api/check.sh`, `db/check.sh` and the contract checks all pass. Record the commands and results in `.agent/pr-notes/<story-id>.md`.

- [x] AUD-20261119-03 · Expose required workflows through HTTP entry points instead of orphan services
  - pr: #236
  - evidence: PR #236; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/AUD-20261119-03.md; simulated review only
  - attempts: 1
  - scope: apps/api/src/main/java/com/aurevia/messaging, apps/api/src/main/java/com/aurevia/therapy, apps/api/src/main/java/com/aurevia/radiology
  - details:
    Several services implementing requirements marked [x] in TODO.md have no caller anywhere in apps/api/src/main/java (grep for each class name outside its own file returns zero): AttachmentService (MSG-03 upload/quarantine/scan/publish/download), PatientAppointmentService (PAT-01), ReleasedReportService (PAT-02), G3EvidenceService, plus TherapyService (PT-01) and ImagingService (RAD-01). No controller contains a multipart/@RequestPart upload endpoint, so patient document upload and mediated retrieval cannot occur at runtime; the patient app exercises these flows only with local fixtures. JaCoCo 100% is satisfied by unit tests of these classes, so the gate does not detect that no entry point exists. Expected fix: wire thin controllers (or otherwise expose each deliverable command through a real entry point) and add an HTTP-level test per workflow, or honestly mark the corresponding TODO entries pending instead of done.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261120-01 · Return HTTP 201 from patient registration and catalog entry creation, as the contract says
  - pr: #238
  - evidence: PR #238; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/AUD-20261120-01.md; simulated review only
  - attempts: 1
  - scope: apps/api/src/main/java/com/aurevia/web, apps/api/src/test/java/com/aurevia/web
  - depends: DLV-04
  - details:
    Problem (verified in the code): `contracts/openapi/generated/openapi.yaml` declares `'201'` for `registerPatient` (POST /api/v1/patients) and `defineCatalogEntry` (POST /api/v1/catalogs/{family}/entries), but both controllers answer 200.
    - `web/PatientController.java`, method `register`: ends with `return ResponseEntity.ok(created);` and its Javadoc already says "HTTP 201".
    - `web/CatalogController.java`, method `define`: ends with `return ResponseEntity.ok(created);`.
    Other controllers already do it right; copy their style, for example `web/StaffController.java` uses `ResponseEntity.created(URI.create("/api/v1/staff/" + created.subject())).body(created)`.

    Do this, in order:
    1. Find who relies on 200 today: `grep -rn "api/v1/patients\|catalogs/.*entries" apps/api/src/test apps/staff-web/src apps/patient-mobile/src`. Note every assertion that expects `HttpStatus.OK`/`isOk()`/`200` for these two POSTs.
    2. Change those assertions to expect 201 (`HttpStatus.CREATED` / `isCreated()`). Run the affected test class and watch it FAIL (the controller still says 200). This is your failing test.
    3. In `PatientController.register` replace the last line with `return ResponseEntity.created(URI.create("/api/v1/patients/" + created.id())).body(created);`. If `Patient` has no `id()` accessor, open `patient/Patient.java` and use the accessor that holds the patient identifier. Add `import java.net.URI;`.
    4. In `CatalogController.define` replace the last line with `return ResponseEntity.created(URI.create("/api/v1/catalogs/" + family + "/entries/" + created.code())).body(created);`. Use the real accessor name from `catalog` / `configuration` entry class (open `ConfigEntry.java` if unsure).
    5. Run the two test classes again; they must pass.
    6. Do NOT change the OpenAPI files: they are already right. Do NOT change any other controller.
    Done when: both POSTs return 201 with a `Location` header, no test expects 200 for them, and `bash scripts/worker-check.sh` is green.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` must pass for the classes you touched. Synthetic data only; write the failing test first; stay inside the declared scope; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] AUD-20261120-02 · Production authentication, part 1: a pure JWT-claims-to-TenantContext resolver with tests
  - pr: #211
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/web, apps/api/src/test/java/com/aurevia/web
  - depends: DLV-04
  - details:
    Problem (verified): there is no real authentication. `web/TenantContextInterceptor.java` accepts a request only when the Spring profile `synthetic-auth` is active and then trusts the headers `X-Synthetic-Subject` and `X-Active-Organization`. Without that profile every `/api/v1/**` request gets 401. `pom.xml` has no Spring Security dependency. PROJECT_V1_SPEC.md and README-SETUP.md require managed OIDC with server-side authorization. This task builds only the safe, testable core; part 2 (AUD-20261120-03) wires it in.

    Create `web/AuthenticatedPrincipal.java` (a Java `record`) with `String subject`, `Set<String> organizationIds` and `String issuer`.
    Create `web/ClaimsTenantResolver.java`, a plain class (no Spring annotations) with one public method:
    `TenantContext resolve(Map<String, Object> claims, String requestedOrganization, String correlationId)`.
    Rules, each one needs its own unit test in `ClaimsTenantResolverTest` (JUnit 5, no Spring, no database):
    1. `claims.get("sub")` must be a non-blank String, else throw `new ApiException(401, "UNAUTHENTICATED", "authentication required")` (class `com.aurevia.api.ApiException`).
    2. The claim `orgs` must be a List of Strings, each matching the UUID pattern used in `TenantContextInterceptor` (copy the regex, do not loosen it). A missing or empty `orgs` -> 401 as above.
    3. `requestedOrganization` (the value of the `X-Active-Organization` header) must be a UUID and must be IN the `orgs` list, otherwise throw `new ApiException(403, "ORGANIZATION_NOT_PERMITTED", "organization is not available to this subject")`. A malformed value -> `ApiException(422, "INVALID_TENANT_SELECTION", "an active organization must be selected")`.
    4. Return `new TenantContext(requestedOrganization, sub, TenantContext.PURPOSE, correlationId)`.
    5. The client can never choose an organization the token does not list: add a test with a valid UUID that is not in `orgs` and assert 403.
    Do NOT touch `TenantContextInterceptor`, `pom.xml` or any other file. Do NOT read tokens from the request in this class: it only sees already-validated claims.
    Done when: `ClaimsTenantResolverTest` has at least 7 tests (happy path, missing sub, blank sub, missing orgs, malformed org list, org not in token, malformed header) and passes.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` must pass for the classes you touched. Synthetic data only; write the failing test first; stay inside the declared scope; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] AUD-20261120-03 · Production authentication, part 2: validate OIDC bearer tokens and bind the tenant context
  - pr: #251
  - attempts: 1
  - scope: apps/api/pom.xml, apps/api/src/main/java/com/aurevia/web, apps/api/src/test/java/com/aurevia/web, apps/api/src/main/resources
  - depends: AUD-20261120-02
  - details:
    Needs AUD-20261120-02 (the `ClaimsTenantResolver` class) to be merged first.
    Goal: outside the `synthetic-auth` profile, `/api/v1/**` requires a valid OIDC JWT bearer token (mobile app and partners; the staff web cookie session comes later in READY-11); the synthetic header login keeps working only under `synthetic-auth` (all existing tests rely on it, do not break them).
    Steps:
    1. In `apps/api/pom.xml` add dependencies `org.springframework.boot:spring-boot-starter-oauth2-resource-server` and, with `<scope>test</scope>`, `org.springframework.security:spring-security-test`. The Spring Boot parent already manages the versions, so add NO `<version>` tag. Run `mvn -q -B -f apps/api/pom.xml -Pno-security -DskipTests compile` to confirm the build still compiles.
    2. Add `web/SecurityConfiguration.java` (`@Configuration`, `@EnableWebSecurity`) with two `SecurityFilterChain` beans:
       - `@Profile("synthetic-auth")`: `http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll())` so the existing interceptor stays the only gate, exactly as today.
       - `@Profile("!synthetic-auth")`: stateless sessions (`SessionCreationPolicy.STATELESS`), CSRF ignored ONLY for requests that carry an `Authorization: Bearer` header (browsers never attach that header by themselves; this chain serves mobile and partners), via `csrf.ignoringRequestMatchers(request -> { String h = request.getHeader("Authorization"); return h != null && h.startsWith("Bearer "); })`. The spec requires session cookies with CSRF for the staff web; READY-11 adds that browser chain, so do not turn CSRF off for anything else, `authorizeHttpRequests(a -> a.anyRequest().authenticated())`, `oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))`.
    3. In `src/main/resources/application.yml` add `spring.security.oauth2.resourceserver.jwt.issuer-uri: ${OREVIA_OIDC_ISSUER_URI:}` and `...jwt.audiences: ${OREVIA_OIDC_AUDIENCE:}`. Never put a real issuer, client id or secret in the repository. If the issuer is empty the application must fail to start outside `synthetic-auth` (fail closed): add a startup check in `SecurityConfiguration` that throws `IllegalStateException("OREVIA_OIDC_ISSUER_URI must be set")`.
    4. In `TenantContextInterceptor.preHandle`, replace the branch that returns 401 when `synthetic-auth` is not active: read `SecurityContextHolder.getContext().getAuthentication()`; if it is a `JwtAuthenticationToken`, call `new ClaimsTenantResolver().resolve(token.getToken().getClaims(), request.getHeader("X-Active-Organization"), correlation)` and `TenantContext.set(...)`; otherwise throw the same 401 as before. Keep the `synthetic-auth` branch byte-for-byte as it is.
    5. Add `TenantContextInterceptorJwtTest` with `MockMvc` and `SecurityMockMvcRequestPostProcessors.jwt()`: (a) no token -> 401; (b) token without `orgs` -> 401; (c) token with `orgs` containing the header value -> request reaches the controller; (d) header organization not in `orgs` -> 403.
    Do NOT add any user database, password login, API key or cookie session. Do NOT weaken the `synthetic-auth` profile test setup. Done when: the new test passes, every existing test still passes with `-Pno-security`, and the application refuses to start without an issuer outside `synthetic-auth`.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` must pass for the classes you touched. Synthetic data only; write the failing test first; stay inside the declared scope; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] AUD-20261120-04 · Add a database gate that fails when any tenant table lacks forced row-level security
  - pr: #204
  - attempts: 0
  - scope: db/check.sh
  - depends: DLV-04
  - details:
    Problem: tenant isolation depends on `FORCE ROW LEVEL SECURITY` plus a policy on `organization_id = public.app_org()` for every table that has an `organization_id` column. Migrations apply it inside loops (`FOREACH t IN ARRAY ...`), so nothing proves that a new table was not forgotten. Add one assertion to `db/check.sh`.
    Steps:
    1. Read `db/check.sh` fully. Find where it runs SQL assertions against the migrated database (look for `psql`, `assert`, or `fail` helper functions) and copy that exact helper style.
    2. Add this query (adjust only the helper call around it):
    ```
    SELECT c.relname
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped
    WHERE c.relkind = 'r'
      AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
    ORDER BY 1;
    ```
       The check must FAIL (non-zero exit, print the table names) when this query returns any row.
    3. Add a second query that fails when a table with `organization_id` has RLS enabled but no policy: join `pg_policies` on `schemaname = 'public' AND tablename = c.relname` and select tables with zero policies.
    4. Run `bash db/check.sh` (needs PostgreSQL; it works in the worker image). If it now fails, DO NOT silence it and DO NOT edit migrations. Instead list the failing tables in `.agent/pr-notes/<story-id>.md`, add each one to a clearly commented `EXCEPTIONS` array in `db/check.sh` ONLY if its migration header explains why it is global (for example `organization` itself or reference data), and for every other table write one line in the pr-notes saying "needs RLS" so a person can open a follow-up task. Never add an exception without that written reason.
    Done when: `bash db/check.sh` passes, the two new assertions run, and the notes list any table you had to except together with the reason.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` must pass for the classes you touched. Synthetic data only; write the failing test first; stay inside the declared scope; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] AUD-20261120-06 · Replace the in-memory delivery outbox store with a PostgreSQL-backed store
  - pr: #237
  - evidence: PR #237; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/AUD-20261120-06.md; simulated review only
  - attempts: 1
  - scope: apps/api/src/main/java/com/aurevia/events, apps/api/src/test/java/com/aurevia/events, apps/api/src/test/java/com/aurevia/it
  - depends: REFACTOR-PACKAGES-02
  - details:
    Problem (verified): `events/InMemoryDeliveryStore.java` (package `api/x_02` before REFACTOR-PACKAGES-02 renamed it to `events`) is annotated `@Repository`, so it is the production implementation of `DeliveryStore`. Events, receipts and dead letters are lost on every restart and are not shared between API instances. The durable tables already exist: `db/migrations/V55__durable_event_outbox.sql` creates `delivery_outbox`, `delivery_dead_letter`, `delivery_receipt`, `delivery_replay`, `delivery_link`, `delivery_audit`. This task makes the code use them. NO new migration.
    Steps:
    1. Read `DeliveryStore.java` (the interface) and `InMemoryDeliveryStore.java` (the behaviour to copy) completely. Read V55 to learn every column name and constraint. Read one existing JDBC repository for style, for example `patient/PatientRepository.java` (uses `NamedParameterJdbcTemplate`/`JdbcTemplate`, never string-concatenated SQL).
    2. Create `JdbcDeliveryStore implements DeliveryStore` in the same package, `@Repository`, constructor-injected `JdbcTemplate`. Implement every interface method with parameterised SQL. Keep tenant isolation: do not add an `organization_id` filter by hand if row-level security already filters (look at how other repositories rely on `public.app_org()`); inserts must still set `organization_id` the same way other repositories do.
    3. Remove `@Repository` from `InMemoryDeliveryStore` and keep the class only for unit tests (move it to `apps/api/src/test/java/.../api/x_02` if no main code uses it: check with `grep -rn InMemoryDeliveryStore apps/api/src`).
    4. Write an integration test class `JdbcDeliveryStoreIntegrationTest` under `apps/api/src/test/java/com/aurevia/it/` following the structure of an existing one (`it/patientchart/ChartEntryIntegrationTest.java` shows `@ActiveProfiles("synthetic-auth")` and the DB setup). Cover: store and read back an event; idempotent re-store of the same event id; a dead-lettered event; tenant A cannot read tenant B's event.
    5. Run `bash scripts/worker-check.sh`.
    Do NOT change `DeliveryStore` method signatures, the controllers or any migration. If the interface cannot be implemented with the V55 tables, STOP, write exactly which method and which missing column in `.agent/pr-notes/<story-id>.md`, and finish without code changes.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` must pass for the classes you touched. Synthetic data only; write the failing test first; stay inside the declared scope; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] AUD-20261120-08 · Add HTTP tests for the scheduling, audit and appointment endpoints that no test calls
  - pr: #210
  - evidence: PR #210; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/AUD-20261120-08.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/test/java/com/aurevia/it/scheduling, apps/api/src/test/java/com/aurevia/it/audit
  - depends: DLV-04
  - details:
    Controllers whose class name appears in no test: `scheduling/AppointmentController`, `web/SchedulingAdminController`, `web/AuditController` (path `/api/v1/audit-events`; one existing test, `it/AuditQueryTest.java`, may already cover reads - check before adding).
    Method for EVERY controller below (do not skip the first step):
    1. For each endpoint, find out whether a test already calls it: `grep -rn "<path fragment>" apps/api/src/test`. Controller class names are NOT referenced by name in most integration tests, so search by URL path (for example `/api/v1/audit-events`). Only write tests for endpoints that no test calls.
    2. Copy the request-building style of an existing integration test: open `apps/api/src/test/java/com/aurevia/it/exchangeresult/ResultRoutingIntegrationTest.java` and reuse the pattern of its `headers(subject, organization)` helper (headers `X-Synthetic-Subject`, `X-Active-Organization`, `Content-Type`), its `@ActiveProfiles("synthetic-auth")` class annotation, and its database setup. Put new classes in `apps/api/src/test/java/com/aurevia/it/<area>/` and mark them like the existing ones so they run in the integration tier.
    3. For each uncovered endpoint write four tests: (a) no `X-Synthetic-Subject` header -> 401; (b) missing or malformed `X-Active-Organization` -> 422; (c) a subject whose role may not perform the action -> 403 (roles and actions are in `authz/ActionPolicy.java`); (d) an allowed subject -> the success status shown in `contracts/openapi/generated/openapi.yaml` for that path.
    4. If a test reveals a real bug (wrong status, missing authorization), do NOT fix production code in this task: mark the test with `@Disabled("<one-line reason and the endpoint>")`, and list it in `.agent/pr-notes/<story-id>.md` so a person opens a follow-up.
    Do NOT change production code, migrations or the OpenAPI files.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` must pass for the classes you touched. Synthetic data only; write the failing test first; stay inside the declared scope; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] AUD-20261120-09 · Add HTTP tests for the finance, prescription, specialty and conversation endpoints that no test calls
  - pr: #214
  - attempts: 0
  - scope: apps/api/src/test/java/com/aurevia/it/finance, apps/api/src/test/java/com/aurevia/it/prescription, apps/api/src/test/java/com/aurevia/it/authz, apps/api/src/test/java/com/aurevia/it/messaging
  - depends: DLV-04
  - details:
    Controllers whose class name appears in no test: `finance/FinanceController`, `prescription/rx/RxController`, `prescription/PrescriptionController`, `prescription/DrugCatalogController`, `authz/specialty/SpecialtyController`, `messaging/conversations/ConversationController`. Some are exercised by existing flow tests (`RxTransmissionFlowTest`, `SpecialtyAuthorizationFlowTest`, `ConversationFlowTest`): step 1 below tells you which gaps remain.
    Method for EVERY controller below (do not skip the first step):
    1. For each endpoint, find out whether a test already calls it: `grep -rn "<path fragment>" apps/api/src/test`. Controller class names are NOT referenced by name in most integration tests, so search by URL path (for example `/api/v1/audit-events`). Only write tests for endpoints that no test calls.
    2. Copy the request-building style of an existing integration test: open `apps/api/src/test/java/com/aurevia/it/exchangeresult/ResultRoutingIntegrationTest.java` and reuse the pattern of its `headers(subject, organization)` helper (headers `X-Synthetic-Subject`, `X-Active-Organization`, `Content-Type`), its `@ActiveProfiles("synthetic-auth")` class annotation, and its database setup. Put new classes in `apps/api/src/test/java/com/aurevia/it/<area>/` and mark them like the existing ones so they run in the integration tier.
    3. For each uncovered endpoint write four tests: (a) no `X-Synthetic-Subject` header -> 401; (b) missing or malformed `X-Active-Organization` -> 422; (c) a subject whose role may not perform the action -> 403 (roles and actions are in `authz/ActionPolicy.java`); (d) an allowed subject -> the success status shown in `contracts/openapi/generated/openapi.yaml` for that path.
    4. If a test reveals a real bug (wrong status, missing authorization), do NOT fix production code in this task: mark the test with `@Disabled("<one-line reason and the endpoint>")`, and list it in `.agent/pr-notes/<story-id>.md` so a person opens a follow-up.
    Do NOT change production code, migrations or the OpenAPI files.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` must pass for the classes you touched. Synthetic data only; write the failing test first; stay inside the declared scope; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] READY-01 · Health endpoints /health/live and /health/ready (WEB-05)
  - pr: #253
  - attempts: 1
  - scope: apps/api/src/main/java/com/aurevia/web, apps/api/src/test/java/com/aurevia/web
  - depends: AUD-20261120-03
  - details:
    Spec rule WEB-05 in PROJECT_V1_SPEC.md section 8.1. These are the only routes outside `/api/v1`. `TenantContextInterceptor` is registered only for `/api/v1/**`, so these routes need no tenant or login headers.
    1. Create `web/HealthController.java`: `@RestController` with two `@GetMapping` methods.
       - `GET /health/live` returns `200` with body `{"status":"UP"}` and never touches the database.
       - `GET /health/ready` runs `SELECT 1` with the injected `JdbcTemplate`. On success `200 {"status":"UP"}`. On any `DataAccessException` return `503 {"status":"DOWN"}`. Never put exception text, host names, versions or tenant data in the body.
    2. Write `HealthControllerTest` (unit, standalone MockMvc, `JdbcTemplate` mocked with Mockito): live returns 200 without any header; ready returns 200 when `queryForObject` returns 1; ready returns 503 and the body contains no exception message when it throws.
    3. Write an integration test `HealthReadyIntegrationTest` tagged `@Tag("integration")` (copy the setup of any test under `apps/api/src/test/java/com/aurevia/it/`): ready returns 200 against the real test database and no tenant headers are needed.
    4. Add one line to `apps/api/README.md` listing the two routes.
    Do NOT register the new routes in the interceptor, do not add Spring Actuator, do not return build or version data.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` (API tasks) or `bash apps/staff-web/check.sh` (staff web tasks) must pass. Synthetic data only; write the failing test first; stay inside the declared scope; name folders and packages after what they hold, never a task ID; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] READY-02 · Security headers on every API response (WEB-02)
  - pr: #261
  - attempts: 1
  - scope: apps/api/src/main/java/com/aurevia/web, apps/api/src/test/java/com/aurevia/web, apps/api/src/main/resources
  - depends: READY-01
  - details:
    Spec rule WEB-02. Add one servlet filter that sets the headers; do not use Spring Security for this.
    1. Create `web/SecurityHeadersFilter.java`, `@Component`, extends `OncePerRequestFilter`, ordered first with `@Order(Ordered.HIGHEST_PRECEDENCE)`. For every response set: `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy: camera=(), microphone=(), geolocation=()`, `X-Robots-Tag: noindex, nofollow`. For paths starting `/api/` also set `Cache-Control: no-store`. When `request.isSecure()` is true also set `Strict-Transport-Security: max-age=31536000; includeSubDomains`.
    2. In `src/main/resources/application.yml` add `server.forward-headers-strategy: framework` so `isSecure()` is correct behind a load balancer.
    3. Write `SecurityHeadersFilterTest` (standalone MockMvc with the filter and a tiny test controller): every header above is present on `/api/v1/x`; `Cache-Control: no-store` is present for `/api/` and absent for `/health/live`; HSTS appears only on a secure request (`MockHttpServletRequestBuilder.secure(true)`).
    Do NOT weaken the policy to make a test pass, and do NOT add `unsafe-inline` or `unsafe-eval` anywhere.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` (API tasks) or `bash apps/staff-web/check.sh` (staff web tasks) must pass. Synthetic data only; write the failing test first; stay inside the declared scope; name folders and packages after what they hold, never a task ID; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] READY-06 · Staff web API transport with CSRF, timeouts and typed errors (WEB-03, WEB-09)
  - pr: #206
  - attempts: 0
  - scope: apps/staff-web/src/api
  - depends: DLV-04
  - details:
    Today `apps/staff-web/src/api/client.ts` only builds request descriptors and never sends a request. Add the transport; do not change the generated types in `src/api/generated`.
    1. Create `src/api/transport.ts` exporting `send(descriptor, options?)` that calls `fetch(descriptor.url, ...)` with `credentials: 'include'`, header `X-Correlation-Id` (a new `crypto.randomUUID()` per call unless the descriptor has one), JSON body, and an `AbortController` timeout of 15000 ms.
    2. For methods other than GET add header `X-XSRF-TOKEN` read from the `XSRF-TOKEN` cookie (`document.cookie`). If the cookie is missing, throw a `TransportError` with kind `csrf-missing` before sending.
    3. Map responses to a `TransportError` class with a `kind` field: 401 -> `session-expired`, 403 -> `forbidden`, 404 -> `not-found`, 409 or 412 -> `conflict`, 422 -> `invalid`, 428 -> `precondition-required`, 429 -> `rate-limited` (store `retryAfterSeconds` from the `Retry-After` header), other 5xx -> `server`, network failure -> `offline`, abort -> `timeout`. Keep `correlationId` (from the response header) and the server's machine-readable error code on the error. Never put the response body text into the message.
    4. Never retry automatically, except a GET once on `offline` or `timeout`. A POST is never retried.
    5. Tests with Vitest and a mocked `fetch` (see how other tests in `src` mock things): one test per mapping above, CSRF header present for POST and absent for GET, timeout aborts, a GET retries once and a POST does not.
    Do NOT store tokens or any response data in `localStorage` or `sessionStorage`, and do NOT add a dependency.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` (API tasks) or `bash apps/staff-web/check.sh` (staff web tasks) must pass. Synthetic data only; write the failing test first; stay inside the declared scope; name folders and packages after what they hold, never a task ID; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] READY-07 · Staff web state views and a global error boundary (WEB-09)
  - pr: #230
  - attempts: 1
  - scope: apps/staff-web/src/components, apps/staff-web/src/app, apps/staff-web/src/features, apps/staff-web/src/main.tsx, apps/staff-web/package.json, apps/staff-web/package-lock.json
  - depends: READY-06
  - details:
    Needs READY-06 (`TransportError` kinds). Spec rule WEB-09 and `docs/design/screens-and-navigation.md` section 4 list seven states every screen needs.
    1. Read an existing component under `src/components` and one screen (`src/features/scheduling/SchedulingScreen.tsx`) to copy the style and the message mechanism (`messages.ts` / `src/i18n`). All new text goes through the i18n messages in both English and Arabic.
    2. Create `src/components/StateViews.tsx` exporting `LoadingState`, `EmptyState`, `ErrorState`, `ForbiddenState`, `ConflictState`, `SessionExpiredState`. `ErrorState` shows the correlation ID and a Retry button; `ConflictState` has buttons Compare, Reload and Copy my changes (callbacks as props); `SessionExpiredState` has a Sign in button (callback prop). Use the tokens, never raw colours. Each has `role` and live-region semantics from `docs/design/accessibility-patterns.md`.
    3. Use an owner-approved error-boundary dependency through a function component in `src/app/ErrorBoundary.tsx`. Pin the dependency through the staff-web lockfile and document the dependency review. The boundary renders `ErrorState` with a generated correlation ID and logs only the correlation ID and error name with `console.error`, never props or state.
    4. Wrap the app in the boundary in `src/main.tsx`.
    5. Add `StateView.forTransportError(error)` helper mapping each `TransportError.kind` to the right state view.
    6. Tests: each view renders in English and Arabic (RTL), is keyboard reachable, the boundary catches a throwing child and shows the correlation ID, and the helper maps all kinds.
    Do NOT change existing screen logic in this task, and do NOT put patient data in any message.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` (API tasks) or `bash apps/staff-web/check.sh` (staff web tasks) must pass. Synthetic data only; write the failing test first; stay inside the declared scope; name folders and packages after what they hold, never a task ID; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] READY-08 · Staff web route code-splitting and a bundle-size budget (WEB-08)
  - pr: #207
  - attempts: 0
  - scope: apps/staff-web/src/App.tsx, apps/staff-web/src/app, apps/staff-web/scripts, apps/staff-web/check.sh, apps/staff-web/vite.config.ts
  - depends: DLV-04
  - details:
    Spec rule WEB-08: each route chunk at most 250 KB gzip.
    1. In `src/App.tsx` load each feature screen (scheduling, chart, admin) with `React.lazy(() => import(...))` inside one `Suspense` whose fallback is `LoadingState` from `src/components/StateViews` if READY-07 is merged, otherwise a plain accessible "Loading" text.
    2. Create `scripts/check-bundle-size.mjs`: run after `vite build`; read every `dist/assets/*.js`, gzip each with Node's `zlib.gzipSync`, print a table, and exit 1 when any file is larger than 250 KB (256000 bytes) gzipped. The limit is a constant at the top of the file.
    3. Call the script from `check.sh` right after the build step (find where `vite build` or `npm run build` runs there and add the call after it).
    4. Test `scripts/check-bundle-size.test.mjs` or a Vitest test that imports the size function and checks it rejects a 300 KB buffer and accepts a 10 KB one.
    5. Write the measured sizes in the pr-notes.
    Do NOT raise the limit, do NOT remove dependencies to make it pass without noting it, and do NOT add third-party scripts.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` (API tasks) or `bash apps/staff-web/check.sh` (staff web tasks) must pass. Synthetic data only; write the failing test first; stay inside the declared scope; name folders and packages after what they hold, never a task ID; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] READY-09 · Mark the staff web non-indexable (WEB-13)
  - pr: #200
  - attempts: 0
  - scope: apps/staff-web/index.html, apps/staff-web/public, apps/staff-web/tests
  - details:
    Spec rule WEB-13: authenticated applications are `noindex` and crawling is disallowed.
    1. In `apps/staff-web/index.html` add inside `<head>`: `<meta name="robots" content="noindex, nofollow">`.
    2. Create `apps/staff-web/public/robots.txt` with exactly two lines: `User-agent: *` and `Disallow: /`.
    3. Add a test in `apps/staff-web/tests` (look at the existing files there for style) that reads both files and fails when either rule is missing.
    Do NOT add analytics, tracking or any external script tag.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` (API tasks) or `bash apps/staff-web/check.sh` (staff web tasks) must pass. Synthetic data only; write the failing test first; stay inside the declared scope; name folders and packages after what they hold, never a task ID; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] READY-10 · Staff web navigation model from the screen map (design contract)
  - pr: #212
  - attempts: 0
  - scope: apps/staff-web/src/app
  - depends: DLV-04
  - details:
    Implements the navigation in `docs/design/screens-and-navigation.md` section 2 as data plus a component; screens that do not exist yet stay as routes to a "not available yet" page.
    1. Create `src/app/navigation.ts`: a typed array of items `{ id, route, group, labelKey, roles: Role[] }` copied from the table in section 2 (every row with a route that starts with `/` and is not under System). Export `navigationFor(role)` that returns only the items the role may use, grouped in the table's order. The `Role` type already exists or is created next to the existing role names in `src/app` / `src/features/admin/domain.ts`; reuse them.
    2. Update `src/app/AppShell.tsx` to render the left rail from `navigationFor(role)`. Labels come from the i18n messages in English and Arabic. Mirror the rail in RTL.
    3. Tests: reception sees Scheduling, Clinic and Patients but not Administration; tenant admin sees Administration; privacy auditor sees only the audit item and Account; no item appears twice; every label key exists in both languages.
    Do NOT add a route for a screen without a table row, and do NOT show items the role cannot use.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` (API tasks) or `bash apps/staff-web/check.sh` (staff web tasks) must pass. Synthetic data only; write the failing test first; stay inside the declared scope; name folders and packages after what they hold, never a task ID; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] READY-13 · List appointments by date range (GET /api/v1/appointments)
  - pr: #226
  - evidence: PR #226; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/READY-13.md; simulated review only
  - attempts: 0
  - requirements: ARC-03
  - scope: apps/api/src/main/java/com/aurevia/scheduling, apps/api/src/test/java/com/aurevia/scheduling, contracts/openapi
  - depends: DLV-04
  - details:
    Spec rule ARC-03: lists use cursor pagination with stable tenant-scoped ordering. Today this resource has only single-record reads, so the staff web cannot show a list. Copy the audit list: read `audit/AuditController.java`, `AuditService.java` (`query`, `parseCursor`), `AuditRepository.java`, `AuditPage.java`, and the resource files named below.
    1. Add `GET /api/v1/appointments` to `web/AppointmentController.java`. Query parameters: `cursor` (optional), `limit` (optional, default 50, at most 200; anything else -> `ApiException(422, "INVALID_LIMIT", ...)`) and `from` and `to` (both required ISO-8601 UTC instants; `to` must be after `from` and at most 31 days later, otherwise `ApiException(422, "INVALID_RANGE", ...)`), `resource` (optional resource code) and `state` (optional, one of the existing `AppointmentState` values).
    2. Order by scheduled start ascending. Use the row's unique id as the tie-break. The cursor is `<sortValue>:<id>` (the last row returned); reject any other shape with `ApiException(422, "INVALID_CURSOR", ...)`. `nextCursor` is null on the last page. Fetch `limit + 1` rows to know whether there is a next page.
    3. Authorization: reuse the action that the single-record read of the same resource uses (grep for it in `scheduling/AppointmentService.java`); add no role.
    4. Repository (`scheduling/AppointmentRepository.java` (table `appointment`, see `db/migrations/V8__scheduling.sql`)): parameterised SQL only. Tenant isolation comes from row-level security as in the existing queries; do not add manual organization filters unless the existing queries have them. Read the migration for the table's real column names; never guess them.
    5. Return a record `AppointmentPage(List<AppointmentView> items, String nextCursor)` in the same package as the view.
    6. Tests: service unit tests (bad cursor, bad limit, empty page, exactly `limit` rows, `limit + 1` rows gives a cursor); controller tests (no login 401, forbidden role 403, bad limit 422, ok 200); one integration test (tag `integration`, copy an existing one under `it/`) with two organizations proving organization B never sees organization A rows and that paging returns every row exactly once in order.
    7. Contract: read `contracts/README.md`. Add the new GET path to `contracts/openapi.yaml` by copying the nearest existing path entry, regenerate or update `contracts/openapi/generated/openapi.yaml` the way that README says, and run `python3 -I contracts/openapi/check.py`.
    Do NOT change existing endpoints, response shapes or migrations.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-14 · List staff (GET /api/v1/staff)
  - pr: #213
  - attempts: 0
  - requirements: ARC-03
  - scope: apps/api/src/main/java/com/aurevia/staff, apps/api/src/test/java/com/aurevia/staff, contracts/openapi
  - depends: DLV-04
  - details:
    Spec rule ARC-03: lists use cursor pagination with stable tenant-scoped ordering. Today this resource has only single-record reads, so the staff web cannot show a list. Copy the audit list: read `audit/AuditController.java`, `AuditService.java` (`query`, `parseCursor`), `AuditRepository.java`, `AuditPage.java`, and the resource files named below.
    1. Add `GET /api/v1/staff` to `web/StaffController.java`. Query parameters: `cursor` (optional), `limit` (optional, default 50, at most 200; anything else -> `ApiException(422, "INVALID_LIMIT", ...)`) and `status` (optional: `active` or `deactivated`, anything else 422).
    2. Order by subject ascending. Use the row's unique id as the tie-break. The cursor is `<sortValue>:<id>` (the last row returned); reject any other shape with `ApiException(422, "INVALID_CURSOR", ...)`. `nextCursor` is null on the last page. Fetch `limit + 1` rows to know whether there is a next page.
    3. Authorization: reuse the action that the single-record read of the same resource uses (grep for it in `staff/StaffService.java`); add no role.
    4. Repository (`staff/StaffRepository.java`): parameterised SQL only. Tenant isolation comes from row-level security as in the existing queries; do not add manual organization filters unless the existing queries have them. Read the migration for the table's real column names; never guess them.
    5. Return a record `StaffPage(List<StaffMember> items, String nextCursor)` in the same package as the view.
    6. Tests: service unit tests (bad cursor, bad limit, empty page, exactly `limit` rows, `limit + 1` rows gives a cursor); controller tests (no login 401, forbidden role 403, bad limit 422, ok 200); one integration test (tag `integration`, copy an existing one under `it/`) with two organizations proving organization B never sees organization A rows and that paging returns every row exactly once in order.
    7. Contract: read `contracts/README.md`. Add the new GET path to `contracts/openapi.yaml` by copying the nearest existing path entry, regenerate or update `contracts/openapi/generated/openapi.yaml` the way that README says, and run `python3 -I contracts/openapi/check.py`.
    Do NOT change existing endpoints, response shapes or migrations.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-15 · List facilities (GET /api/v1/facilities)
  - pr: #216
  - evidence: PR #216; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/READY-15.md; simulated review only
  - attempts: 0
  - requirements: ARC-03
  - scope: apps/api/src/main/java/com/aurevia/tenancy, apps/api/src/test/java/com/aurevia/tenancy, contracts/openapi
  - depends: DLV-04
  - details:
    Spec rule ARC-03: lists use cursor pagination with stable tenant-scoped ordering. Today this resource has only single-record reads, so the staff web cannot show a list. Copy the audit list: read `audit/AuditController.java`, `AuditService.java` (`query`, `parseCursor`), `AuditRepository.java`, `AuditPage.java`, and the resource files named below.
    1. Add `GET /api/v1/facilities` to `web/FacilityController.java`. Query parameters: `cursor` (optional), `limit` (optional, default 50, at most 200; anything else -> `ApiException(422, "INVALID_LIMIT", ...)`) and no filters.
    2. Order by facility code ascending. Use the row's unique id as the tie-break. The cursor is `<sortValue>:<id>` (the last row returned); reject any other shape with `ApiException(422, "INVALID_CURSOR", ...)`. `nextCursor` is null on the last page. Fetch `limit + 1` rows to know whether there is a next page.
    3. Authorization: reuse the action that the single-record read of the same resource uses (grep for it in `tenancy/FacilityService.java`); add no role.
    4. Repository (`tenancy/FacilityRepository.java`): parameterised SQL only. Tenant isolation comes from row-level security as in the existing queries; do not add manual organization filters unless the existing queries have them. Read the migration for the table's real column names; never guess them.
    5. Return a record `FacilityPage(List<Facility> items, String nextCursor)` in the same package as the view.
    6. Tests: service unit tests (bad cursor, bad limit, empty page, exactly `limit` rows, `limit + 1` rows gives a cursor); controller tests (no login 401, forbidden role 403, bad limit 422, ok 200); one integration test (tag `integration`, copy an existing one under `it/`) with two organizations proving organization B never sees organization A rows and that paging returns every row exactly once in order.
    7. Contract: read `contracts/README.md`. Add the new GET path to `contracts/openapi.yaml` by copying the nearest existing path entry, regenerate or update `contracts/openapi/generated/openapi.yaml` the way that README says, and run `python3 -I contracts/openapi/check.py`.
    Do NOT change existing endpoints, response shapes or migrations.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-16 · List orders (GET /api/v1/orders)
  - pr: #221
  - attempts: 0
  - requirements: ARC-03
  - scope: apps/api/src/main/java/com/aurevia/exchange/order, apps/api/src/test/java/com/aurevia/exchange/order, contracts/openapi
  - depends: DLV-04
  - details:
    Spec rule ARC-03: lists use cursor pagination with stable tenant-scoped ordering. Today this resource has only single-record reads, so the staff web cannot show a list. Copy the audit list: read `audit/AuditController.java`, `AuditService.java` (`query`, `parseCursor`), `AuditRepository.java`, `AuditPage.java`, and the resource files named below.
    1. Add `GET /api/v1/orders` to `exchange/order/OrderController.java`. Query parameters: `cursor` (optional), `limit` (optional, default 50, at most 200; anything else -> `ApiException(422, "INVALID_LIMIT", ...)`) and `status` (optional; use the existing workflow state values you find in `OrderView` and the migration, anything else 422).
    2. Order by creation time descending. Use the row's unique id as the tie-break. The cursor is `<sortValue>:<id>` (the last row returned); reject any other shape with `ApiException(422, "INVALID_CURSOR", ...)`. `nextCursor` is null on the last page. Fetch `limit + 1` rows to know whether there is a next page.
    3. Authorization: reuse the action that the single-record read of the same resource uses (grep for it in `exchange/order/OrderService.java`); add no role.
    4. Repository (`exchange/order/OrderRepository.java`): parameterised SQL only. Tenant isolation comes from row-level security as in the existing queries; do not add manual organization filters unless the existing queries have them. Read the migration for the table's real column names; never guess them.
    5. Return a record `OrderPage(List<OrderView> items, String nextCursor)` in the same package as the view.
    6. Tests: service unit tests (bad cursor, bad limit, empty page, exactly `limit` rows, `limit + 1` rows gives a cursor); controller tests (no login 401, forbidden role 403, bad limit 422, ok 200); one integration test (tag `integration`, copy an existing one under `it/`) with two organizations proving organization B never sees organization A rows and that paging returns every row exactly once in order.
    7. Contract: read `contracts/README.md`. Add the new GET path to `contracts/openapi.yaml` by copying the nearest existing path entry, regenerate or update `contracts/openapi/generated/openapi.yaml` the way that README says, and run `python3 -I contracts/openapi/check.py`.
    Do NOT change existing endpoints, response shapes or migrations.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-17 · List lab accessions (GET /api/v1/lab/accessions)
  - pr: #227
  - evidence: PR #227; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/READY-17.md; simulated review only
  - attempts: 0
  - requirements: ARC-03
  - scope: apps/api/src/main/java/com/aurevia/lab/accession, apps/api/src/test/java/com/aurevia/lab/accession, contracts/openapi
  - depends: DLV-04
  - details:
    Spec rule ARC-03: lists use cursor pagination with stable tenant-scoped ordering. Today this resource has only single-record reads, so the staff web cannot show a list. Copy the audit list: read `audit/AuditController.java`, `AuditService.java` (`query`, `parseCursor`), `AuditRepository.java`, `AuditPage.java`, and the resource files named below.
    1. Add `GET /api/v1/lab/accessions` to `lab/accession/LabAccessionController.java`. Query parameters: `cursor` (optional), `limit` (optional, default 50, at most 200; anything else -> `ApiException(422, "INVALID_LIMIT", ...)`) and `state` (optional, one of the `AccessionState` values, anything else 422).
    2. Order by creation time descending. Use the row's unique id as the tie-break. The cursor is `<sortValue>:<id>` (the last row returned); reject any other shape with `ApiException(422, "INVALID_CURSOR", ...)`. `nextCursor` is null on the last page. Fetch `limit + 1` rows to know whether there is a next page.
    3. Authorization: reuse the action that the single-record read of the same resource uses (grep for it in `lab/accession/LabAccessionService.java`); add no role.
    4. Repository (`lab/accession/LabAccessionRepository.java`): parameterised SQL only. Tenant isolation comes from row-level security as in the existing queries; do not add manual organization filters unless the existing queries have them. Read the migration for the table's real column names; never guess them.
    5. Return a record `AccessionPage(List<AccessionView> items, String nextCursor)` in the same package as the view.
    6. Tests: service unit tests (bad cursor, bad limit, empty page, exactly `limit` rows, `limit + 1` rows gives a cursor); controller tests (no login 401, forbidden role 403, bad limit 422, ok 200); one integration test (tag `integration`, copy an existing one under `it/`) with two organizations proving organization B never sees organization A rows and that paging returns every row exactly once in order.
    7. Contract: read `contracts/README.md`. Add the new GET path to `contracts/openapi.yaml` by copying the nearest existing path entry, regenerate or update `contracts/openapi/generated/openapi.yaml` the way that README says, and run `python3 -I contracts/openapi/check.py`.
    Do NOT change existing endpoints, response shapes or migrations.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-18 · List invoices (GET /api/v1/finance/invoices)
  - pr: #228
  - evidence: PR #228; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/READY-18.md; simulated review only
  - attempts: 0
  - requirements: ARC-03
  - scope: apps/api/src/main/java/com/aurevia/finance, apps/api/src/test/java/com/aurevia/finance, contracts/openapi
  - depends: DLV-04
  - details:
    Spec rule ARC-03: lists use cursor pagination with stable tenant-scoped ordering. Today this resource has only single-record reads, so the staff web cannot show a list. Copy the audit list: read `audit/AuditController.java`, `AuditService.java` (`query`, `parseCursor`), `AuditRepository.java`, `AuditPage.java`, and the resource files named below.
    1. Add `GET /api/v1/finance/invoices` to `finance/FinanceController.java`. Query parameters: `cursor` (optional), `limit` (optional, default 50, at most 200; anything else -> `ApiException(422, "INVALID_LIMIT", ...)`) and `status` (optional, one of the existing invoice states) and `patientId` (optional).
    2. Order by creation time descending. Use the row's unique id as the tie-break. The cursor is `<sortValue>:<id>` (the last row returned); reject any other shape with `ApiException(422, "INVALID_CURSOR", ...)`. `nextCursor` is null on the last page. Fetch `limit + 1` rows to know whether there is a next page.
    3. Authorization: reuse the action that the single-record read of the same resource uses (grep for it in `finance/FinanceService.java`); add no role.
    4. Repository (`finance/FinanceRepository.java`): parameterised SQL only. Tenant isolation comes from row-level security as in the existing queries; do not add manual organization filters unless the existing queries have them. Read the migration for the table's real column names; never guess them.
    5. Return a record `InvoicePage(List<the existing invoice view record in `FinanceContract.java` or the package> items, String nextCursor)` in the same package as the view.
    6. Tests: service unit tests (bad cursor, bad limit, empty page, exactly `limit` rows, `limit + 1` rows gives a cursor); controller tests (no login 401, forbidden role 403, bad limit 422, ok 200); one integration test (tag `integration`, copy an existing one under `it/`) with two organizations proving organization B never sees organization A rows and that paging returns every row exactly once in order.
    7. Contract: read `contracts/README.md`. Add the new GET path to `contracts/openapi.yaml` by copying the nearest existing path entry, regenerate or update `contracts/openapi/generated/openapi.yaml` the way that README says, and run `python3 -I contracts/openapi/check.py`.
    Do NOT change existing endpoints, response shapes or migrations.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-19 · Staff web router and application shell with role-based navigation
  - pr: #249
  - attempts: 1
  - requirements: UX-01, WEB-07, WEB-09
  - scope: apps/staff-web/src/app, apps/staff-web/src/App.tsx, apps/staff-web/tests/app
  - depends: READY-10, READY-07
  - details:
    Today `src/app/AppShell.tsx` is a demo page with synthetic content and no routing. Build the real shell described in `docs/design/screens-and-navigation.md` section 1 without a new dependency.
    1. Create `src/app/router.tsx`: `RouterProvider` (holds the current pathname from `window.location.pathname`, listens to `popstate`), `useRoute()` returning `{ path, params }`, `navigate(path)` using `history.pushState`, and `<Link to=...>` that renders an `<a href>` and intercepts plain left clicks (not ctrl/meta/shift, not new tab) to call `navigate`. `matchRoute(pattern, path)` supports `:param` segments and exact matches only; write it as a pure function.
    2. Create `src/app/routes.tsx`: an array `{ pattern, component }` where each component is loaded with `React.lazy`. Register only `/` (a "welcome" placeholder using `EmptyState`), `/design-preview` (the old demo content moved to `src/app/DesignPreview.tsx`, registered only when `import.meta.env.DEV` is true) and a catch-all not-found page. Later tasks add their own entries.
    3. Rewrite `AppShell` to match section 1 of the design doc: skip link first, header (product name, organization switcher, language toggle, theme toggle, user menu with a Sign out button that calls a prop `onSignOut`), left navigation rail built from `navigationFor(role)` (READY-10) using `<Link>` and `aria-current="page"` on the active item, `<main id="main" tabIndex={-1}>` showing the matched route, and a banner area above `main` (`role="status"`). The rail sits on the right in Arabic (use `insetInlineStart`, not `left`).
    4. The signed-in role comes from `useRole()` in `src/app/useRole.ts`. For now it returns the role named in `src/fixtures/synthetic.ts` and has a comment that READY-11's login will replace the source. Do not read a role from the URL or browser storage.
    5. Move focus to `main` after each navigation. Update `tests/app/AppShell.test.tsx` and add `tests/app/router.test.tsx`: `matchRoute` cases, `Link` navigates without a page load, back button works, unknown path shows the not-found page, the rail shows only the role's items, rail is mirrored in Arabic.
    Do NOT add react-router or any other router package, and do NOT delete the demo content (move it).

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-20 · Staff web data-loading hook and page wrapper (no cross-organization data)
  - pr: #248
  - attempts: 1
  - requirements: UX-01, WEB-09
  - scope: apps/staff-web/src/api, apps/staff-web/src/components, apps/staff-web/tests/api
  - depends: READY-06, READY-07
  - details:
    Every screen needs the same loading logic. Build it once (`docs/design/screen-recipe.md` sections 1 and 2).
    1. Create `src/api/useApi.ts` exporting `useApi<T>(load, deps)` where `load(signal)` is a function returning a promise. It returns `{ status: 'loading' | 'ready' | 'error', data, error, reload }`. It aborts the request with `AbortController` when the component unmounts, when `deps` change or when `reload` is called, and ignores a late response from an aborted call.
    2. The hook reads the active organization from the organization switcher state used in `src/app/OrgSwitcher.tsx` (find how it exposes the active organization; add a small React context there if none exists) and includes it in the dependency list. When the organization changes it sets `data` to `undefined` immediately so rows from the old organization are never shown.
    3. Create `src/components/ApiPage.tsx`: props `{ resource, children(data), isEmpty? }`. It renders `LoadingState` while loading, `EmptyState` when `isEmpty(data)` is true, `StateView.forTransportError(error)` on error, and `children(data)` when ready. A `session-expired` error calls the `onSessionExpired` prop of a small `SessionContext` (create it; the default does nothing).
    4. Tests with a fake `load`: loading then ready; error mapping for each `TransportError` kind; abort on unmount; late response ignored; organization change clears data before the new data arrives; `reload` refetches.
    Do NOT cache responses between organizations or write any response to browser storage.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-21 · Scheduling page: /schedule and /schedule/book on the real API
  - pr: #254
  - attempts: 1
  - requirements: CLN-01, CLN-02
  - scope: apps/staff-web/src/features/scheduling, apps/staff-web/src/app/routes.tsx, apps/staff-web/src/app/navigation.ts
  - depends: READY-19, READY-20, READY-13
  - details:
    Today `SchedulingScreen` works on synthetic in-memory appointments. Add a container that uses the real API (`docs/design/screen-recipe.md`). Needs `GET /api/v1/appointments` (READY-13).
    1. Read `apps/api/src/main/java/com/aurevia/scheduling/AppointmentView.java`, `BookingRequest.java` and `web/AppointmentController.java` and note the exact field names and endpoints: `GET /api/v1/appointments?from&to&resource&state&cursor`, `POST /api/v1/appointments`, `POST /api/v1/appointments/{id}/check-in`, `.../cancel`, `.../no-show`.
    2. Create `api.ts` (list, book, checkIn, cancel) and a mapper `toAppointment(view)` converting the API view to the existing `Appointment` domain type in `domain.ts`. Keep `domain.ts` unchanged.
    3. Create `SchedulingPage.tsx`: loads the day or week range with `useApi`, shows `Load more` while `nextCursor` exists, and passes data to `SchedulingScreen`. Change `SchedulingScreen` so commands call callbacks (`onCheckIn(id)`, `onBook(request)`) instead of mutating local state; after a command succeeds call `reload`. A 409 or 412 shows `ConflictState`.
    4. Register `/schedule` and `/schedule/book` in `routes.tsx`.
    5. Keep the existing tests passing (update them for the callback props) and add tests for each state, check-in success, check-in conflict, booking validation and the tenant switch.
    Do NOT change the backend and do NOT remove the fixtures (tests still use them).

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-22 · Chart page: /patients/:id/chart and the note editor on the real API
  - pr: #260
  - attempts: 1
  - requirements: CLN-03, CLN-05
  - scope: apps/staff-web/src/features/chart, apps/staff-web/src/app/routes.tsx
  - depends: READY-19, READY-20
  - details:
    Same approach as READY-21 for the chart (`docs/design/screen-recipe.md`).
    1. Read `clinical/notes/ClinicalNoteController.java`, `chart/record/ChartController.java` and the view records they return. Endpoints: `GET /api/v1/chart/records/patients/{patientId}/entries`, `GET /api/v1/chart/records/entries/{id}`, `POST /api/v1/clinical-notes`, `GET /api/v1/clinical-notes/{id}`, `PUT /api/v1/clinical-notes/{id}` (needs `If-Match`), `POST .../sign`, `.../amend`, `.../sign-addendum`, `.../entered-in-error`.
    2. Create `api.ts` and mappers to the existing domain types in `features/chart/domain.ts` (leave that file unchanged).
    3. Create `ChartPage.tsx` (loads entries, cursor paging if the API returns one) and `NotePage.tsx` for `/patients/:id/notes/:noteId` using the existing `NoteEditor`. Saving sends `If-Match` with the note version; a 409 or 412 shows `ConflictState` with Compare, Reload and Copy my changes, and the typed text is kept. Signing asks for confirmation naming the note and the effect, and shows the signer attestation text from the API.
    4. Show the patient name together with the opaque patient ID at the top of both pages. Unsaved edits ask before leaving the page.
    5. Register `/patients/:id/chart` and `/patients/:id/notes/:noteId`.
    6. Tests: each state, stale save keeps typed text, sign confirmation, entered-in-error banner (existing `EnteredInErrorBanner`), unsaved-changes guard.
    Do NOT store a note draft in browser storage.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-33 · Patient app: Expo entry point and app configuration
  - pr: #205
  - attempts: 0
  - requirements: PAT-01, UX-01
  - scope: apps/patient-mobile/index.ts, apps/patient-mobile/App.tsx, apps/patient-mobile/app.json, apps/patient-mobile/src/app, apps/patient-mobile/package.json
  - depends: DLV-04
  - details:
    `apps/patient-mobile/package.json` says `"main": "index.ts"` but the folder has no `index.ts`, no `App.tsx` and no `app.json`, so the app cannot start. Add the minimum Expo entry.
    1. Create `index.ts`: `import { registerRootComponent } from 'expo'; import App from './App'; registerRootComponent(App);`.
    2. Create `App.tsx` that renders `<RootNavigator />` from `src/app/RootNavigator.tsx` (create it now as a placeholder that shows the text from `src/app/messages.ts` key `appTitle` in English and Arabic; READY-34 replaces it with the tabs).
    3. Create `app.json` with `expo.name` and `expo.slug` taken from `project.json` (`displayName`, `repositorySlug`), `version` `0.1.0`, `orientation` `portrait`, `userInterfaceStyle` `automatic`, `ios.supportsTablet` false, and placeholder identifiers `ios.bundleIdentifier` and `android.package` of the form `com.example.aureviacare` with a comment in the pr-notes that the owner must replace them. Include no keys, tokens or URLs.
    4. Add `RootNavigator.test.tsx` (Jest, `@testing-library/react-native`) proving the title renders in both languages, and a test that reads `app.json` and fails if it contains `apiKey`, `token`, `secret` or `password`.
    Do NOT add a dependency and do NOT run `expo prebuild`.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-34 · Patient app: four-tab navigation (Appointments, Reports, Messages, Account)
  - pr: #234
  - evidence: PR #234; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/READY-34.md; simulated review only
  - attempts: 1
  - requirements: PAT-01, PAT-02, PAT-03, UX-01
  - scope: apps/patient-mobile/src/app, apps/patient-mobile/App.tsx
  - depends: READY-33
  - details:
    Implements `docs/design/screens-and-navigation.md` section 3. `@react-navigation/native` and `@react-navigation/bottom-tabs` are already dependencies.
    1. In `src/app/RootNavigator.tsx` create a bottom tab navigator with exactly these tabs in this order: Appointments (`AppointmentListScreen`), Reports (`ReleasedReportsScreen`), Messages (`ConversationInbox`), Account (a stack with `EnrollmentScreen`, `RecoveryScreen`, `SupportRequestScreen`, `LockoutScreen` reachable from an Account menu). Import the screens from their feature folders; read each screen's props first and pass what it needs from placeholder providers in `src/app/providers.ts` (keep them clearly named `placeholder...` with a comment that the real auth and API client replace them).
    2. Tab labels and accessibility labels come from `src/app/messages.ts` in English and Arabic. In Arabic call `I18nManager.forceRTL(true)` only through one helper `applyDirection(locale)` that is unit tested (the tab order mirrors automatically).
    3. Messages is a nested stack: conversation list, then `AttachmentReviewScreen` and `InstructionAcknowledgmentScreen` as pushed screens.
    4. Push notification text never contains clinical details: do not add notification code in this task.
    5. Tests: the four tabs exist in order, each tab renders its screen, labels in both languages, `applyDirection` behaviour, every tab has an accessibility label.
    Do NOT add authentication or network code; that is a later task for a person to approve.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT add a dependency, change files outside `scope`, or put patient data in logs, URLs or browser storage.

- [x] READY-35 · Persist service profiles in PostgreSQL (V59) instead of memory
  - pr: #247
  - attempts: 1
  - requirements: ARC-01, ARC-02, OPS-02
  - scope: apps/api/src/main/java/com/aurevia/messaging/service, apps/api/src/test/java/com/aurevia/messaging/service, db/migrations/V59__service_profiles.sql, apps/api/src/test/java/com/aurevia/it
  - depends: AUD-20261120-06
  - reserved: V59
  - details:
    Problem (verified in `docs/decisions/0003-persistence-of-in-memory-stores.md`): `InMemoryServiceProfileStore` is a `@Repository` that keeps all state in process memory, so every restart loses it and two API instances disagree. Replace it with PostgreSQL. This store owns migration V59 only.
    1. Read `messaging/service/ServiceProfileStore.java` (the interface; every method already takes the organization id) and `InMemoryServiceProfileStore.java` (the behaviour to copy) completely. Read `ServiceProfileService.java` to see which methods production code calls. Read one existing JDBC repository for style (`patient/PatientRepository.java`) and `db/migrations/V58*.sql` plus one earlier migration that creates a tenant table, to copy how tables get `organization_id`, forced row-level security and the `public.app_org()` policy.
    2. Create `db/migrations/V59__service_profiles.sql` with one table per aggregate the interface stores (name them after the business concept, no story ids). Every table: `organization_id` not null, primary key including it where identifiers are only unique per tenant, `created_at timestamptz not null default now()`, `ENABLE` and `FORCE ROW LEVEL SECURITY`, a policy `USING (organization_id = public.app_org())`, and GRANT to `orevia_app` exactly as the earlier migration does. Append-only facts get no UPDATE grant. The migration is additive only.
    3. Create `JdbcServiceProfileStore` in the same package, `@Repository`, constructor-injected `JdbcTemplate`, parameterised SQL only, implementing every interface method with the same semantics (same exceptions, same empty results).
    4. Remove `@Repository` from `InMemoryServiceProfileStore` and keep it only for unit tests (move it under `apps/api/src/test/java/com/aurevia/messaging/service` if production code no longer uses it: `grep -rn InMemoryServiceProfileStore apps/api/src/main`).
    5. Write `JdbcServiceProfileStoreIntegrationTest` under `apps/api/src/test/java/com/aurevia/messaging/service/it/` (tag `integration`; copy the setup of an existing class there): save then read back, update path if the interface has one, and tenant A cannot read tenant B rows with identical local ids.
    6. Run `bash scripts/worker-check.sh`. Also run the AUD-20261120-04 row-level-security gate if it exists.
    If the interface cannot be implemented with a clean table design, STOP and write exactly which method and why into `.agent/pr-notes/<task-id>.md`; make no code changes.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT change files outside `scope`, add patient data to logs, or edit any existing migration.

- [x] READY-36 · Persist care units in PostgreSQL (V60) instead of memory
  - pr: #252
  - attempts: 1
  - requirements: ARC-01, ARC-02, OPS-02
  - scope: apps/api/src/main/java/com/aurevia/messaging/units, apps/api/src/test/java/com/aurevia/messaging/units, db/migrations/V60__care_units.sql, apps/api/src/test/java/com/aurevia/it
  - depends: READY-35
  - reserved: V60
  - details:
    Problem (verified in `docs/decisions/0003-persistence-of-in-memory-stores.md`): `InMemoryMessagingStore` is a `@Repository` that keeps all state in process memory, so every restart loses it and two API instances disagree. Replace it with PostgreSQL. This store owns migration V60 only.
    1. Read `messaging/units/MessagingStore.java` (the interface; every method already takes the organization id) and `InMemoryMessagingStore.java` (the behaviour to copy) completely. Read `CareUnitDirectoryService.java` to see which methods production code calls. Read one existing JDBC repository for style (`patient/PatientRepository.java`) and `db/migrations/V58*.sql` plus one earlier migration that creates a tenant table, to copy how tables get `organization_id`, forced row-level security and the `public.app_org()` policy.
    2. Create `db/migrations/V60__care_units.sql` with one table per aggregate the interface stores (name them after the business concept, no story ids). Every table: `organization_id` not null, primary key including it where identifiers are only unique per tenant, `created_at timestamptz not null default now()`, `ENABLE` and `FORCE ROW LEVEL SECURITY`, a policy `USING (organization_id = public.app_org())`, and GRANT to `orevia_app` exactly as the earlier migration does. Append-only facts get no UPDATE grant. The migration is additive only.
    3. Create `JdbcMessagingStore` in the same package, `@Repository`, constructor-injected `JdbcTemplate`, parameterised SQL only, implementing every interface method with the same semantics (same exceptions, same empty results).
    4. Remove `@Repository` from `InMemoryMessagingStore` and keep it only for unit tests (move it under `apps/api/src/test/java/com/aurevia/messaging/units` if production code no longer uses it: `grep -rn InMemoryMessagingStore apps/api/src/main`).
    5. Write `JdbcMessagingStoreIntegrationTest` under `apps/api/src/test/java/com/aurevia/messaging/units/it/` (tag `integration`; copy the setup of an existing class there): save then read back, update path if the interface has one, and tenant A cannot read tenant B rows with identical local ids.
    6. Run `bash scripts/worker-check.sh`. Also run the AUD-20261120-04 row-level-security gate if it exists.
    If the interface cannot be implemented with a clean table design, STOP and write exactly which method and why into `.agent/pr-notes/<task-id>.md`; make no code changes.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT change files outside `scope`, add patient data to logs, or edit any existing migration.

- [x] READY-45 · Automated accessibility and end-to-end test harness for the staff web (WEB-10)
  - pr: #256
  - attempts: 1
  - requirements: WEB-10, UX-01
  - scope: apps/staff-web/e2e, apps/staff-web/package.json, package.json, package-lock.json, apps/staff-web/README.md
  - depends: READY-19
  - details:
    You MAY add exactly these devDependencies for this task: `@playwright/test` and `@axe-core/playwright` (use the versions `npm view <name> version` returns, pinned exactly, no caret). No other dependency.
    1. Create `apps/staff-web/e2e/playwright.config.ts` (baseURL from env `E2E_BASE_URL` default `http://127.0.0.1:5173`, projects chromium only, retries 1, trace on first retry, `webServer` runs `npm run dev` in `apps/staff-web`).
    2. Create `e2e/smoke.spec.ts` with synthetic data only: opens `/`, checks the page title and that the skip link is first in tab order; opens `/design-preview` in dev; runs axe (`AxeBuilder`) and fails on any violation of impact serious or critical; repeats with `?lang=ar` or the language toggle and asserts `document.dir === 'rtl'`; resizes to 768 px width and asserts no horizontal scroll.
    3. Add npm script `e2e` in `apps/staff-web/package.json`. Do NOT add it to `check.sh` (browsers are not installed in the worker image) and do NOT edit `.github/workflows`; write in the pr-notes that a person must add a CI job.
    4. Add a short section to `apps/staff-web/README.md`: how to run `npx playwright install chromium` and `npm run e2e`.
    5. Run `apps/staff-web/check.sh` (must still pass) and `python3 -I scripts/check-docs.py`.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT change files outside `scope`, add patient data to logs, or edit any existing migration.

- [x] READY-46 · Launch checklist document with the owner of every item that needs a person
  - pr: #202
  - attempts: 0
  - requirements: WEB-01, WEB-11, WEB-12
  - scope: docs/operations/production-readiness
  - details:
    Several launch items cannot be done by an agent (they need cloud access, a decision, or workflow permissions). Make them impossible to forget.
    1. Create `docs/operations/production-readiness/launch-checklist.md`. Start from `PROJECT_V1_SPEC.md` section 8.1 and the `README.md` next to it. One table row per item with columns: item, spec ID, owner (Infrastructure, Security, Product Owner, Clinical lead, Engineering), evidence required, status (open). Do not invent owners' names; use the role words above.
    2. Include at least these rows: managed TLS 1.2+ certificates and HSTS at the edge (WEB-01); WAF and DDoS protection (WEB-04); managed OIDC provider with staff MFA and mobile PKCE client registered (WEB-03); database backups with a restore drill and a recorded recovery time (OPS-04); secrets in a secret manager, none in images (WEB-12); CI actions pinned by commit SHA and base images by digest (WEB-12); dependency, image and secret scanning that blocks merges (WEB-12); SBOM and signed artifact promoted through environments (WEB-11); progressive rollout 5%, 25%, 100% with automatic rollback (WEB-11); E2E CI job using the READY-45 suite (WEB-10); penetration test with findings closed; status page and support page hosted separately (WEB-13); on-call rota and incident runbook; privacy notice and accessibility statement published; Product Owner and clinical sign-off per site (G5).
    3. Link each row to the existing runbook or evidence file if one exists under `docs/operations/` or `infra/` (check with `ls`); otherwise write `none yet`.
    4. Run `python3 -I scripts/check-docs.py` (links and requirement IDs must resolve).
    Do NOT claim any row is done.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT change files outside `scope`, add patient data to logs, or edit any existing migration.

- [x] READY-47 · Patient app API transport and secure token storage (no login screen yet)
  - pr: #235
  - attempts: 1
  - requirements: PAT-01, ARC-03
  - scope: apps/patient-mobile/src/api, apps/patient-mobile/tests/api
  - depends: READY-33
  - details:
    Mirror what READY-06 did for the staff web. `expo-secure-store` is already a dependency; add no other.
    1. Create `src/api/transport.ts`: `createTransport({ baseUrl, getToken, fetchImpl })` returning `get/post/put` that adds `Authorization: Bearer <token>`, `Accept-Language`, a fresh `X-Correlation-Id` (uuid from `crypto.randomUUID` or a small fallback), a 15 second timeout through `AbortController`, and JSON parsing. HTTPS only: reject any `baseUrl` not starting with `https://` (allow `http://localhost` only when `__DEV__`).
    2. Map failures to a typed `TransportError` with kind `network`, `timeout`, `unauthenticated` (401), `forbidden` (403), `conflict` (409, 412), `validation` (422, with the server's field errors) and `server`. Never put the response body or token into the error message or logs.
    3. Create `src/api/tokenStore.ts` using `expo-secure-store` (`setItemAsync`, `getItemAsync`, `deleteItemAsync`, key `session`, option `requireAuthentication` false for now) and expose `saveToken`, `loadToken`, `clearToken`. Never use AsyncStorage.
    4. Tests (mock `fetch` and `expo-secure-store`): headers present, timeout aborts, each status maps to the right kind, http base URL rejected, token never appears in error text, clearToken removes it.
    Do NOT build login in this task; PKCE needs the identity provider details from a person (see launch checklist).

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT change files outside `scope`, add patient data to logs, or edit any existing migration.

- [x] READY-48 · Patient app screens read their data from the API through the transport
  - pr: #250
  - attempts: 1
  - requirements: PAT-01, PAT-02, PAT-03
  - scope: apps/patient-mobile/src/features, apps/patient-mobile/src/app, apps/patient-mobile/tests
  - depends: READY-34, READY-47
  - details:
    Needs READY-34 and READY-47. Today the app tabs get placeholder providers.
    1. Read each screen's props (AppointmentListScreen, ReleasedReportsScreen, ConversationInbox) and the matching API controllers (`web/AppointmentController`, the released-report controller under `messaging/reports`, `messaging/conversations/ConversationController`).
    2. In `src/app/providers.ts` replace the `placeholder...` providers for those three screens with functions using the READY-47 transport: `GET /api/v1/appointments` is staff only, so for the patient use the patient endpoints you find with `grep -rn "patient" apps/api/src/main/java/**/web` (look for released reports and the conversation inbox); if a patient endpoint for appointments does not exist, keep the placeholder for appointments and write that gap into the pr-notes instead of inventing an endpoint.
    3. Every screen shows loading, empty, error, offline and session-expired states using the existing state components; on `unauthenticated` call a `onSessionExpired` callback that clears the token (READY-47) and navigates to a signed-out screen showing text from `messages.ts`.
    4. Tests: each state per screen with a fake transport; session-expired clears the token.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT change files outside `scope`, add patient data to logs, or edit any existing migration.

- [x] READY-49 · Add HTTP tests for the remaining controllers named in no test
  - pr: #217
  - evidence: PR #217; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/READY-49.md; simulated review only
  - attempts: 0
  - requirements: ARC-03, ARC-04
  - scope: apps/api/src/test/java/com/aurevia/web, apps/api/src/test/java/com/aurevia/it
  - depends: AUD-20261120-09
  - details:
    Run: for each file `*Controller.java` under `apps/api/src/main/java`, `grep -rl "<ControllerName>" apps/api/src/test` and list those with no hit, except the ones AUD-20261120-08 and -09 already cover (scheduling, audit, appointment, finance, prescription, specialty, conversation).
    For each remaining controller, add one test class (`<Name>HttpTest`) copying the structure of an existing controller test: no login gives 401; a role without the action gives 403; one valid call gives the documented status (200 or 201); one invalid body gives 422 with an error code; and where the controller lists, a bad cursor or limit gives 422. Use synthetic data and the `synthetic-auth` profile headers. Put the list of controllers you covered in `.agent/pr-notes/<task-id>.md`.
    Run `bash scripts/worker-check.sh`. Do NOT change production code; if a test reveals a bug, write the bug into `BUGS.md` (follow its format) and mark the test `@Disabled("see BUGS.md")`.

    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish by running the check named in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Do NOT change files outside `scope`, add patient data to logs, or edit any existing migration.

- [x] S-G0-01 · Tenant isolation foundation: membership-derived tenant context, forced RLS, non-owner runtime role, append-only audit (IAM-01, IAM-02, ARC-01, ARC-02, OPS-02). Evidence: `docs/archive/PROGRESS.md` G0-01 rows; 44 tests, JaCoCo 100%; green CI https://github.com/dr3mro/Aurevia-Care/actions/runs/44442227258 on SHA `f9bc461`; merged in `e1dc7cd`.
  - evidence: `docs/archive/PROGRESS.md` G0-01 rows; 44 tests, JaCoCo 100% on instruction/line/branch/method/class; `db/check.sh` policy assertions. CI https://github.com/dr3mro/Aurevia-Care/actions/runs/44442227258 on `f9bc461`; merged `e1dc7cd`

- [x] S-G0-02 · Staff lifecycle: invite and deactivate staff, immutable membership and role history, permission change on next protected request (IAM-02, IAM-01, OPS-02). Evidence: `docs/archive/PROGRESS.md` S-G0-02 rows; 81 tests, JaCoCo 100%; V4 append-only history and folded current membership; local security and DB gates green, CI pending.
  - aliases: CW-001
  - pr: #7
  - evidence: `docs/archive/PROGRESS.md` S-G0-02 rows; 81 tests, JaCoCo 100%; `db/check.sh` append-only and fold assertions; `scripts/check.sh` and `scripts/docs.sh` green. Green CI https://github.com/dr3mro/Aurevia-Care/actions/runs/44447432253 on `ea15d6c`; merged in `8d015b5`, main run 44448021668 attempt 2 green

- [x] S-G0-03 · Tenant configuration, catalogs, broader audit and infrastructure foundations (ADM-01, OPS-02, OPS-06). Configuration, catalog and audit-review slices done and CI-green (V5/V6, 158 tests, JaCoCo 100%); OPS-06 environment/rollout work remains as a separate infrastructure follow-on.
  - aliases: CW-002

- [x] S-G0-03b · query the tenant's append-only audit trail with stable cursor pagination, bounded filters and machine-readable errors, and audit each query itself
  - evidence: 158 tests incl. audit; JaCoCo 100%; green CI run 44466453912 on `4349263`

- [x] S-G0-03c · define, approve and read versioned tenant catalogs (diagnosis codes, partner directory) with named approval and effective dates, scoped to the active organization
  - evidence: V6 catalog fold, 158 tests, JaCoCo 100%, green CI 44468724537 on `99d586d`; catalog contract and maintainer trace updated

- [x] S-G0-03d · OPS-06 infrastructure foundations: migration change-safety gate with expand/contract, version sequencing and fail-closed baseline; environment accounts, signed builds and monitored rollout deferred to cloud/human-gated S-G5-07 (OPS-06).
  - agent: loop
  - pr: code in `6b9d730` (commit `a3f0eac`, externally auto-merged); tracker sync `4869b62`
  - evidence: `python3 -I scripts/check-migrations.py --require-baseline` exit 0 on V1–V7; 34 `test_check_migrations.py` cases OK, 122 total tooling tests OK (current source count); stdlib `trace` 100% line coverage of checker and tests; `bash scripts/check.sh`, `bash scripts/quick-check.sh`, `bash scripts/docs.sh` all exit 0. CI Delivery run 44477163896 on `6b9d730`: Policy and tooling, Frontend standards, Java and database verification, Developer reference, Verify and smoke image (amd64) all success. Three independent simulated reviewer reports (QA-G0-03d-*, PY-G0-03d-*, DS-OPS06-*) found real gaps (omitted DROP object types, string/comment ordering, same-line/split-line bypass, duplicate-version keying, unverified Forward-fix, fail-open) — all fixed and recorded in BUGS.md. No vacuous environment manifest. Evidence source: executed tool commands and CI for gate results; simulated reviewers for findings; no human or clinical evidence.

- [x] CW-003 · Concurrent-connection tenant isolation tests
  - requirements: ARC-02
  - scope: apps/api/src/test/java/com/aurevia/it
  - pr: #18

- [x] S-G0-05 · Patient registration, identity confirmation and likely-duplicate warnings (IAM-03). V7 append-only patient records, issuer-scoped MRN, normalized name search, non-linking duplicate warnings, `/api/v1/patients`; 184 tests at delivery, JaCoCo 100%, local gates green, code on `main`. Current source (2026-11-19): Surefire reports 191 Java tests, 0 failures/errors across the suite; V7 and `/api/v1/patients` present.

- [x] S-G0-06 · Same-tenant duplicate review and all-or-nothing chart consolidation (IAM-04).
  - pr: #160
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/patient/consolidation, apps/api/src/test/java/com/aurevia/it/patient/consolidation
  - reserved: V9
  - details:
    Implement Same-tenant duplicate review and all-or-nothing chart consolidation (IAM-04) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G0-07 · Patient identity proofing, verified chart linking and unlinking (IAM-05).
  - pr: #161
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/patient/identity, apps/api/src/test/java/com/aurevia/it/patient/identity
  - reserved: V10
  - details:
    Implement Patient identity proofing, verified chart linking and unlinking (IAM-05) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G0-08 · Emergency access and platform support access controls (IAM-06).
  - pr: #162
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/authz/emergency, apps/api/src/test/java/com/aurevia/it/authz/emergency
  - reserved: V11
  - details:
    Implement Emergency access and platform support access controls (IAM-06) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G1-01 · Facility, clinician and room/resource availability, recurrence, holds and transactional booking with history (CLN-01).
  - evidence: `db/migrations/V8__scheduling.sql`, the `scheduling` package (facility info, resources, availability, appointments) and `SchedulingBookingTest` are on main.
  - requirements: CLN-01, ARC-01, ARC-02, ARC-03, OPS-02
  - reserved: V8
  - details:
    Slice: facility time zone, scheduling resources (clinician/room), weekly availability windows, visit types with duration, temporary holds with expiry, transactional booking with a database-enforced single protected slot, atomic reschedule with version precondition, booked → checked in → in progress → completed plus cancelled/no-show/expired branches, weekly recurrence series (all-or-nothing) and append-only appointment history.
    Acceptance matrix: (A1) book inside availability; (A2) competing bookings yield exactly one winner and one 409 under real concurrency; (A3) room and clinician overlaps both blocked; (A4) hold occupies the slot, conversion is atomic, expiry releases it; (A5) reschedule is atomic, stale If-Match is 412, history keeps the previous slot; (A6) cancel/no-show require a reason and record actor; (A7) illegal transitions are 409; (A8) booking outside availability needs a recorded override; (A9) configured booking rules are enforced; (A10) two tenants with identical local identifiers stay isolated; (A11) a series rolls back fully on one conflict; (A12) role authorization and 401/403 outcomes; (A13) invalid input is 422.
    Out of scope for this slice: staff worklist/assignment/handoff (S-G1-02), clinical chart (S-G1-03), equipment resources and paid booking rules.

- [x] S-G1-02 · Identity verification, check-in, organization/facility worklist, assignment and handoff (CLN-02).
  - pr: #163
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/scheduling/checkin, apps/api/src/test/java/com/aurevia/it/scheduling/checkin
  - reserved: V12
  - details:
    Implement Identity verification, check-in, organization/facility worklist, assignment and handoff (CLN-02) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G1-03 · Longitudinal chart: problems, allergies, medications, vitals, source and verification states (CLN-03).
  - pr: #164
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/chart/record, apps/api/src/test/java/com/aurevia/it/chart/record
  - reserved: V13
  - details:
    Implement Longitudinal chart: problems, allergies, medications, vitals, source and verification states (CLN-03) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G1-04 · Versioned SOAP, operative, OB/GYN, perinatal and pediatric development templates (CLN-04).
  - pr: #38
  - evidence: PR #38; integrator ran scripts/check.sh on the merge result (tool evidence); heavy-check.yml re-verifies main after the push; notes in docs/swarm/S-G1-04.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/clinical/templates, apps/api/src/test/java/com/aurevia/it/clinical
  - reserved: V14
  - details:
    Implement Versioned SOAP, operative, OB/GYN, perinatal and pediatric development templates (CLN-04) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G1-05 · Draft to signed, amendments, signed addenda and entered-in-error with preserved originals (CLN-05).
  - pr: #165
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/chart/signing, apps/api/src/test/java/com/aurevia/it/chart/signing
  - reserved: V15
  - details:
    Implement Draft to signed, amendments, signed addenda and entered-in-error with preserved originals (CLN-05) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G1-06 · Follow-up suggestions with rule explanation and care-team tasks (CLN-06).
  - pr: #40
  - evidence: PR #40; integrator ran scripts/check.sh on the merge result (tool evidence); heavy-check.yml re-verifies main after the push; notes in docs/swarm/S-G1-06.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/scheduling/followup, apps/api/src/test/java/com/aurevia/it/schedulingfollowup
  - reserved: V16
  - details:
    Implement Follow-up suggestions with rule explanation and care-team tasks (CLN-06) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G1-07 · Original PDF/image associations and immutable, localized, versioned PDF rendering (CLN-07).
  - pr: #166
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/chart/documents, apps/api/src/test/java/com/aurevia/it/chart/documents
  - reserved: V17
  - details:
    Implement Original PDF/image associations and immutable, localized, versioned PDF rendering (CLN-07) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G1-08 · Clinical dictionaries, diagnosis/procedure catalogs and RTL mixed-script operation (PRD-05, UX-01).
  - pr: #167
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/catalog/dictionary, apps/api/src/test/java/com/aurevia/it/catalog/dictionary
  - reserved: V18
  - details:
    Implement Clinical dictionaries, diagnosis/procedure catalogs and RTL mixed-script operation (PRD-05, UX-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-01 · Order creation with authorization, snapshot, disclosure record and destination work item (XCH-01).
  - pr: #168
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/exchange/order, apps/api/src/test/java/com/aurevia/it/exchange/order
  - reserved: V19
  - details:
    Implement Order creation with authorization, snapshot, disclosure record and destination work item (XCH-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-02 · Separate workflow, transmission and clinical review states with human acknowledgment (XCH-02).
  - pr: #121
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/exchange/task, apps/api/src/test/java/com/aurevia/it/exchangetask
  - reserved: V20
  - details:
    Implement Separate workflow, transmission and clinical review states with human acknowledgment (XCH-02) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-03 · Accept, reject with reason, clarify, linked changes, cancellations and revocation (XCH-03).
  - pr: #122
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/exchange/response, apps/api/src/test/java/com/aurevia/it/exchange/response
  - reserved: V21
  - details:
    Implement Accept, reject with reason, clarify, linked changes, cancellations and revocation (XCH-03) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-04 · Result routing, identifier matching, reconciliation queue and correction versions (XCH-04).
  - pr: #123
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/exchange/result, apps/api/src/test/java/com/aurevia/it/exchange/result
  - reserved: V22
  - details:
    Implement Result routing, identifier matching, reconciliation queue and correction versions (XCH-04) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-05 · Critical-result verification, owner, due time, escalation attempts and read-back (XCH-05).
  - pr: #47
  - evidence: PR #47; integrator ran scripts/check.sh on the merge result (tool evidence); heavy-check.yml re-verifies main after the push; notes in docs/swarm/S-G2-05.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/exchange/critical, apps/api/src/test/java/com/aurevia/it/exchangeresult
  - reserved: V23
  - details:
    Implement Critical-result verification, owner, due time, escalation attempts and read-back (XCH-05) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-06 · Patient release authorization, timed/hold policies and withdrawn releases (XCH-06).
  - pr: #124
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/exchange/release, apps/api/src/test/java/com/aurevia/it/exchange/release
  - reserved: V24
  - details:
    Implement Patient release authorization, timed/hold policies and withdrawn releases (XCH-06) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-07 · Trending only comparable observations with units, ranges, time and qualifiers visible (XCH-07).
  - pr: #113
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/exchange/trend, apps/api/src/test/java/com/aurevia/it/exchangetrend
  - reserved: V25
  - details:
    Implement Trending only comparable observations with units, ranges, time and qualifiers visible (XCH-07) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-08 · Requisition accession, specimen identification, rejection and linked recollection (LAB-01).
  - pr: #50
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/lab/accession, apps/api/src/test/java/com/aurevia/it/lab
  - reserved: V26
  - details:
    Implement Requisition accession, specimen identification, rejection and linked recollection (LAB-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-09 · Numeric, qualified, text and coded observations, verification states and issued revisions (LAB-02).
  - pr: #51
  - evidence: PR #51; integrator ran scripts/check.sh on the merge result (tool evidence); heavy-check.yml re-verifies main after the push; notes in docs/swarm/S-G2-09.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/lab/result, apps/api/src/test/java/com/aurevia/it/lab
  - reserved: V27
  - details:
    Implement Numeric, qualified, text and coded observations, verification states and issued revisions (LAB-02) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G2-10 · Synthetic partner conformance fixtures including duplicates, corrections and partial outages (XCH-01–07, LAB-01, LAB-02).
  - pr: #52
  - evidence: PR #52; integrator ran scripts/check.sh on the merge result (tool evidence); heavy-check.yml re-verifies main after the push; notes in docs/swarm/S-G2-10.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/exchange/conformance, apps/api/src/test/java/com/aurevia/it/exchange
  - reserved: V28
  - details:
    Implement Synthetic partner conformance fixtures including duplicates, corrections and partial outages (XCH-01–07, LAB-01, LAB-02) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-01 · Patient appointment list, confirmation, generic push notifications and device revocation (PAT-01).
  - pr: #114
  - attempts: 0
  - scope: apps/patient-mobile/src/features/appointments, apps/api/src/main/java/com/aurevia/messaging/appointments
  - reserved: V29
  - details:
    Implement Patient appointment list, confirmation, generic push notifications and device revocation (PAT-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-02 · Released reports with revision and correction context, authorized downloads and protected local credentials (PAT-02).
  - pr: #54
  - evidence: PR #54; integrator ran scripts/check.sh on the merge result (tool evidence); heavy-check.yml re-verifies main after the push; notes in docs/swarm/S-G3-02.md; simulated review only
  - attempts: 0
  - scope: apps/patient-mobile/src/features/reports, apps/api/src/main/java/com/aurevia/messaging/reports
  - reserved: V30
  - details:
    Implement Released reports with revision and correction context, authorized downloads and protected local credentials (PAT-02) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-03 · Privacy-safe enrollment, recovery, lockout and identity-dispute support channel (PAT-03).
  - pr: #60
  - evidence: PR #60; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); heavy-check.yml re-verifies main after the push; notes in docs/swarm/S-G3-03.md; simulated review only
  - attempts: 0
  - scope: apps/patient-mobile/src/features/enrollment, apps/api/src/main/java/com/aurevia/messaging/enrollment
  - reserved: V31
  - details:
    Implement Privacy-safe enrollment, recovery, lockout and identity-dispute support channel (PAT-03) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-04 · Care unit directory and verified, connection-scoped patient-unit relationships (MSG-01, ARC-07).
  - pr: #61
  - evidence: PR #61; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); heavy-check.yml re-verifies main after the push; notes in docs/swarm/S-G3-04.md; simulated review only
  - attempts: 0
  - scope: apps/patient-mobile/src/features/units, apps/api/src/main/java/com/aurevia/messaging/units
  - reserved: V32
  - details:
    Implement Care unit directory and verified, connection-scoped patient-unit relationships (MSG-01, ARC-07) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-05 · Conversation lifecycle, unread counts, staff work queue, assignment and reopen states (MSG-02).
  - pr: #125
  - attempts: 0
  - scope: apps/patient-mobile/src/features/conversations, apps/api/src/main/java/com/aurevia/messaging/conversation
  - reserved: V33
  - details:
    Implement Conversation lifecycle, unread counts, staff work queue, assignment and reopen states (MSG-02) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-06 · PDF/JPEG/PNG attachments, sender binding, quarantine scans and clean-verdict access (MSG-03, ARC-06).
  - pr: #58
  - attempts: 0
  - scope: apps/patient-mobile/src/features/attachments, apps/api/src/main/java/com/aurevia/messaging/attachments
  - reserved: V34
  - details:
    Implement PDF/JPEG/PNG attachments, sender binding, quarantine scans and clean-verdict access (MSG-03, ARC-06) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-07 · Typed patient instructions with templates, revisions, withdrawal and units (MSG-04).
  - pr: #59
  - attempts: 0
  - scope: apps/patient-mobile/src/features/instructions, apps/api/src/main/java/com/aurevia/messaging/instructions
  - reserved: V35
  - details:
    Implement Typed patient instructions with templates, revisions, withdrawal and units (MSG-04) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-08 · Publication, dispatch, first view and revision-bound acknowledgment as separate facts (MSG-05).
  - pr: #126
  - attempts: 0
  - scope: apps/patient-mobile/src/features/acknowledgments, apps/api/src/main/java/com/aurevia/messaging/acknowledgment
  - reserved: V36
  - details:
    Implement Publication, dispatch, first view and revision-bound acknowledgment as separate facts (MSG-05) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-09 · Service hours, response targets, named owners, backup coverage and after-hours guidance (MSG-06).
  - pr: #127
  - attempts: 0
  - scope: apps/patient-mobile/src/features/service-profiles, apps/api/src/main/java/com/aurevia/messaging/service
  - reserved: V37
  - details:
    Implement Service hours, response targets, named owners, backup coverage and after-hours guidance (MSG-06) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-10 · Messaging audit, retention, legal hold, provenance and revocation effects (MSG-07).
  - pr: #98
  - attempts: 0
  - scope: apps/patient-mobile/src/features/governance, apps/api/src/main/java/com/aurevia/messaging/governance
  - reserved: V38
  - details:
    Implement Messaging audit, retention, legal hold, provenance and revocation effects (MSG-07) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-11 · iOS and Android patient flows with accessibility and RTL operation (PAT-01–03, MSG-01–07, UX-01).
  - pr: #80
  - attempts: 0
  - scope: apps/patient-mobile/src/features/flows, apps/api/src/main/java/com/aurevia/messaging/flows
  - reserved: V39
  - details:
    Implement iOS and Android patient flows with accessibility and RTL operation (PAT-01–03, MSG-01–07, UX-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G3-12 · Restore, downtime, load, accessibility and audit evidence (G3).
  - pr: #81
  - attempts: 0
  - scope: apps/patient-mobile/src/features/evidence, apps/api/src/main/java/com/aurevia/messaging/evidence
  - reserved: V40
  - details:
    Implement Restore, downtime, load, accessibility and audit evidence (G3) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G4-01 · Imaging order intake, urgency, accession, study association, signed interpretation and addenda (RAD-01).
  - pr: #82
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/radiology, apps/api/src/test/java/com/aurevia/it/radiology
  - reserved: V41
  - details:
    Implement Imaging order intake, urgency, accession, study association, signed interpretation and addenda (RAD-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G4-02 · Therapy episode, evaluation, goals, interventions, outcomes, signed progress and discharge (PT-01).
  - pr: #83
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/therapy, apps/api/src/test/java/com/aurevia/it/therapy
  - reserved: V42
  - details:
    Implement Therapy episode, evaluation, goals, interventions, outcomes, signed progress and discharge (PT-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G4-03 · Prescription draft, versioned drug catalog, alternatives and verified allergy checks (RX-01).
  - pr: #84
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/prescription, apps/api/src/test/java/com/aurevia/it/prescription
  - reserved: V43
  - details:
    Implement Prescription draft, versioned drug catalog, alternatives and verified allergy checks (RX-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G4-04 · Prescriber attestation, approved routing channel, transmission events and ambiguity reconciliation (RX-02).
  - pr: #85
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/prescription/rx, apps/api/src/test/java/com/aurevia/it/prescription
  - reserved: V44
  - details:
    Implement Prescriber attestation, approved routing channel, transmission events and ambiguity reconciliation (RX-02) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G4-05 · Itemized self-pay invoices, payments, refunds, receipts and daily reconciliation (FIN-01).
  - pr: #86
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/finance, apps/api/src/test/java/com/aurevia/it/finance
  - reserved: V45
  - details:
    Implement Itemized self-pay invoices, payments, refunds, receipts and daily reconciliation (FIN-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G4-06 · Specialty role permissions and end-to-end flow evidence (PRD-06).
  - pr: #87
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/authz/specialty, apps/api/src/test/java/com/aurevia/it/authz
  - reserved: V46
  - details:
    Implement Specialty role permissions and end-to-end flow evidence (PRD-06) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G5-05 · Capacity targets, pilot load profile and measured performance evidence (OPS-03).
  - pr: #88
  - attempts: 0
  - scope: infra/capacity, docs/operations/capacity
  - reserved: V47
  - details:
    Implement Capacity targets, pilot load profile and measured performance evidence (OPS-03) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G5-06 · Recovery targets, restore into clean environment and operational runbooks (OPS-04, OPS-05).
  - pr: #128
  - attempts: 0
  - scope: infra/recovery, docs/operations/recovery
  - reserved: V48
  - details:
    Implement Recovery targets, restore into clean environment and operational runbooks (OPS-04, OPS-05) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G5-07 · Separate environments, synthetic fixtures, signed builds, migration checks and progressive rollout (OPS-06).
  - pr: #129
  - attempts: 0
  - scope: infra/delivery-safety, docs/operations/delivery-safety
  - reserved: V49
  - details:
    Implement Separate environments, synthetic fixtures, signed builds, migration checks and progressive rollout (OPS-06) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G5-08 · Tenant export manifest, retention, legal hold, deletion and offboarding (OPS-07).
  - pr: #130
  - attempts: 0
  - scope: infra/tenant-lifecycle, docs/operations/tenant-lifecycle
  - reserved: V50
  - details:
    Implement Tenant export manifest, retention, legal hold, deletion and offboarding (OPS-07) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G5-09 · Onboarding dataset manifest, dry run, controlled batches and authorized sign-off (OPS-08).
  - pr: #111
  - attempts: 0
  - scope: infra/onboarding, docs/operations/onboarding
  - reserved: V51
  - details:
    Implement Onboarding dataset manifest, dry run, controlled batches and authorized sign-off (OPS-08) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G5-10 · English and Arabic, RTL, time zones, WCAG 2.2 AA and native screen-reader flows (UX-01).
  - pr: #115
  - attempts: 0
  - scope: infra/accessibility, docs/operations/accessibility
  - reserved: V52
  - details:
    Implement English and Arabic, RTL, time zones, WCAG 2.2 AA and native screen-reader flows (UX-01) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] S-G5-11 · Operational queues for unsigned work, unmatched reports, critical findings, failures and revoked accounts (ADM-02).
  - pr: #116
  - attempts: 0
  - scope: infra/ops-queues, docs/operations/ops-queues
  - reserved: V53
  - details:
    Implement Operational queues for unsigned work, unmatched reports, critical findings, failures and revoked accounts (ADM-02) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] X-01 · API versioning, idempotency, cursor pagination, correlation IDs and machine-readable errors (ARC-03, ARC-04).
  - pr: #101
  - attempts: 0
  - scope: contracts/x_01, apps/api/src/main/java/com/aurevia/api/x_01
  - reserved: V54
  - details:
    Implement API versioning, idempotency, cursor pagination, correlation IDs and machine-readable errors (ARC-03, ARC-04) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] X-02 · Durable events, outbox, deduplication, replay and dead-letter review (ARC-05).
  - pr: #102
  - attempts: 0
  - scope: contracts/x_02, apps/api/src/main/java/com/aurevia/api/x_02
  - reserved: V55
  - details:
    Implement Durable events, outbox, deduplication, replay and dead-letter review (ARC-05) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] X-03 · Upload validation, quarantine, mediated downloads and PDF binding to immutable revisions (ARC-06).
  - pr: #112
  - attempts: 0
  - scope: contracts/upload, apps/api/src/main/java/com/aurevia/api/upload
  - reserved: V56
  - details:
    Implement Upload validation, quarantine, mediated downloads and PDF binding to immutable revisions (ARC-06) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] X-04 · Generated OpenAPI clients and server-side authorization for every command (PRD-06).
  - pr: #155
  - attempts: 0
  - scope: contracts/openapi, apps/api/src/main/java/com/aurevia/api/authorization
  - reserved: V57
  - details:
    Implement Generated OpenAPI clients and server-side authorization for every command (PRD-06) against PROJECT_V1_SPEC.md.
    Synthetic fixtures only; failing tests first; 100% coverage;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] DLV-01 · Repair integration tests: patient chart, chart entry and exchange task
  - agent: swarm-1
  - attempts: 0
  - requirements: CLN-03, CLN-05, ARC-02
  - scope: apps/api, db
  - details:
    Classes: `ChartRecordIntegrationTest` and `ChartEntryIntegrationTest` (`seedPatient` inserts a `patient_record` with neither `dob` nor `dob_unknown_reason`, which violates the V7 CHECK that requires exactly one), `ExchangeTaskIsolationTest` (duplicate key on `exchange_task_pkey` in `storageCannotForgeAReviewedTaskOrMoveTwoChannels`), `CheckInWorklistIntegrationTest` (`worklistOrdersByScheduledTimeAndConfiguredPriority` expects 2 rows but sees 9, so check for leaked state between tests).
    Delivery on `main` has been red since about 23:36 on 2026-11-19: the `Java and database verification` job fails in `mvn verify` (run 44658087355, commit 3cd3443: Tests run 2012, Failures 62, Errors 7). Every failure is a PostgreSQL integration test (`@Tag("integration")`). Pull requests only run the fast tier, so these suites never ran before merge. Start from a clean checkout of current `main` and run the real suite (`bash apps/api/check.sh`, with PostgreSQL via `OREVIA_PG_ADMIN`), not only the fast tier. Reproduce each failure red first, find whether the test or the production code is wrong, and fix the real cause. Never weaken, skip, delete or re-tag a test, and never exclude a class from the gate. Read the surefire reports in `apps/api/target/surefire-reports` for the server stack trace behind each 404/500. Do not edit migrations that are already applied on main; add a new migration only when the schema is genuinely wrong. Do NOT edit `.github/`, `scripts/` or shared trackers. Record the root cause per class, commands and results in `.agent/pr-notes/<story-id>.md`. Done means the classes below pass in the full `mvn verify` run.

- [x] DLV-02 · Repair integration tests: patient identity, consolidation and messaging
  - pr: #196
  - attempts: 0
  - requirements: IAM-03, IAM-04, IAM-05, MSG-07
  - depends: DLV-01
  - scope: apps/api, db
  - details:
    Classes: `IdentityProofingIsolationTest` (500 on `/api/v1/patient-identity/links/claim`; `invalidInputsReturnUnprocessableEntity` compares HttpStatus `UNPROCESSABLE_ENTITY` with `UNPROCESSABLE_CONTENT`, a Spring 7 rename, so compare the numeric value 422; `expiredInvitationIsUnusable` violates `account_invitation_check`), `ConsolidationIsolationTest` (404 on `/api/v1/duplicate-reviews`, 500 when creating a review), `ConversationFlowTest` (`uuid = character varying` in a test SQL statement, and missing JSON fields in responses).
    Likely shared root cause (Delivery run #449 on S-G1-05): the trigger function `public.set_link_actor()` assigns `NEW.created_by`, but it is attached to tables that have no `created_by` column (`account_chart_link`, `duplicate_review`), so every INSERT fails with `record "new" has no field "created_by"` (about 26 tests). Two migrations from parallel workers appear to define or redefine the same function name for different tables. Find which migration last replaced `set_link_actor()`, give each table its own correctly-named function, and do not edit already-applied migrations: add a new one. Also expect separate defects in `ClinicalNoteRevisionIntegrationTest`, `ResultRoutingIntegrationTest`, `AccessIntegrationTest`, `DocumentIsolationTest`, `SpecimenAccessionIsolationTest`, `TrendingTest` and the `attested -> ambiguous` prescription transition; fix them after the classes listed here.
    Delivery on `main` has been red since about 23:36 on 2026-11-19: the `Java and database verification` job fails in `mvn verify` (run 44658087355, commit 3cd3443: Tests run 2012, Failures 62, Errors 7). Every failure is a PostgreSQL integration test (`@Tag("integration")`). Pull requests only run the fast tier, so these suites never ran before merge. Start from a clean checkout of current `main` and run the real suite (`bash apps/api/check.sh`, with PostgreSQL via `OREVIA_PG_ADMIN`), not only the fast tier. Reproduce each failure red first, find whether the test or the production code is wrong, and fix the real cause. Never weaken, skip, delete or re-tag a test, and never exclude a class from the gate. Read the surefire reports in `apps/api/target/surefire-reports` for the server stack trace behind each 404/500. Do not edit migrations that are already applied on main; add a new migration only when the schema is genuinely wrong. Do NOT edit `.github/`, `scripts/` or shared trackers. Record the root cause per class, commands and results in `.agent/pr-notes/<story-id>.md`. Done means the classes below pass in the full `mvn verify` run.

- [x] DLV-03 · Repair integration tests: prescription transmission
  - pr: #198
  - attempts: 0
  - requirements: RX-02, IAM-01, ARC-02
  - depends: DLV-02
  - scope: apps/api, db
  - details:
    Class: `RxTransmissionFlowTest` (500 on `/api/v1/prescriptions/{id}/transmit` with no `submissionId`; STALE_VERSION where FORBIDDEN is expected for non-prescriber attestation; tenant concealment returns 200 instead of 404; RENEW_REQUIRES_TRANSMISSION where INVALID_TRANSITION is expected). Treat each as a possible production defect, since authorization and tenant isolation must fail closed.
    Delivery on `main` has been red since about 23:36 on 2026-11-19: the `Java and database verification` job fails in `mvn verify` (run 44658087355, commit 3cd3443: Tests run 2012, Failures 62, Errors 7). Every failure is a PostgreSQL integration test (`@Tag("integration")`). Pull requests only run the fast tier, so these suites never ran before merge. Start from a clean checkout of current `main` and run the real suite (`bash apps/api/check.sh`, with PostgreSQL via `OREVIA_PG_ADMIN`), not only the fast tier. Reproduce each failure red first, find whether the test or the production code is wrong, and fix the real cause. Never weaken, skip, delete or re-tag a test, and never exclude a class from the gate. Read the surefire reports in `apps/api/target/surefire-reports` for the server stack trace behind each 404/500. Do not edit migrations that are already applied on main; add a new migration only when the schema is genuinely wrong. Do NOT edit `.github/`, `scripts/` or shared trackers. Record the root cause per class, commands and results in `.agent/pr-notes/<story-id>.md`. Done means the classes below pass in the full `mvn verify` run.

- [x] FIX-IMAGES-01 · Prepared-image readiness must not depend on workflow run history
  - pr: #193
  - attempts: 0
  - requirements: OPS-06
  - scope: .github/workflows, scripts
  - details:
    Problem: the `images` step in the `plan` job of `.github/workflows/swarm.yml` reads `gh run list --workflow agent-image.yml` and `swarm-images.yml` with `--limit 1` and treats anything other than a successful latest `swarm-images.yml` run as "not ready". When Actions run history is empty (history cleaned, runs expired or deleted) there is no latest run, so `ready` is false and every wave is skipped with "Prepared images are rebuilding or not green", forever, even though the worker images exist in GHCR. As of 2026-11-20 the repository has no `agent-image.yml` or `swarm-images.yml` runs at all.
    Fix: block a wave only when a build is queued or in progress, or when the newest completed run failed. When there is no run at all, decide by whether the slot images exist: check `ghcr.io/<owner>/<repo>-agent-<slot>:latest` for each slot in `.github/swarm/slots.json` (`docker manifest inspect` or the packages API; the plan job already has `packages: read` through the workflow permissions, add it if missing) and start the wave when they exist. If they do not exist, dispatch `agent-image.yml` once (it chains into `swarm-images.yml`) and skip the wave with a clear message. Put the decision in `scripts/swarm.py` as a small tested function (for example `images_ready(runs, manifests_exist)`) instead of shell in the workflow, and add unit tests for: no runs and images present, no runs and images missing, build in progress, latest build failed, latest build succeeded.
    Do NOT touch `apps/api`, `db/` or trackers. Repair PRs may change workflow and script files. Record evidence in `.agent/pr-notes/<story-id>.md`.

- [x] CW-004 · Staff web foundation: Vite/React/TS scaffold, design system and generated client
  - requirements: PRD-06, UX-01, ARC-03
  - scope: apps/staff-web
  - pr: #19

- [x] CW-005 · Patient mobile foundation: Expo/React Native/TS scaffold and accessible shell
  - requirements: PAT-01, PAT-02, PAT-03, UX-01
  - scope: apps/patient-mobile
  - pr: #17

- [x] CW-006 · Bilingual clinical design language and token contract documentation
  - pr: #28
  - requirements: UX-01, PRD-05
  - scope: docs/design
  - details:
    Bilingual clinical design-language documentation, in `docs/design` only. No application code. Produce a maintainer-facing design system specification for the staff web and patient app: color and typography tokens, spacing scale, light/dark theming, RTL mirroring rules, local-name and mixed-script display guidance, accessible focus states and WCAG 2.2 AA patterns for forms, tables, dialogs and critical-result banners. Include a token naming contract that CW-004 and CW-005 can adopt later, with a short mapping to requirement IDs (UX-01, PRD-05). Keep every Markdown link valid and every requirement ID defined in the specification. Do NOT touch `apps/`, `db/`, `contracts/`, `.github/`, `scripts/`, root-level `check.sh`, or any shared tracker. Write the requirement IDs and decisions to `.agent/pr-notes/<story-id>.md`.

- [x] CW-007 · Staff web React appointment and worklist screens (synthetic fixtures)
  - pr: #30
  - requirements: CLN-01, CLN-02, UX-01
  - depends: CW-004
  - scope: apps/staff-web/src/features/scheduling
  - details:
    ReactJS staff-web scheduling screens, in `apps/staff-web/src/features/scheduling` only. Depends on CW-004's scaffold, so reuse its React/Vite/TS setup, component set, theme, i18n bundles and generated OpenAPI client types. Build synthetic-only screens: a day/week appointment worklist ordered by scheduled time, a booking form with protected-resource conflict hint, check-in and assignment controls, and visit-state badges (booked → checked in → in progress → completed, plus cancelled/no-show). Use local fixture data with two tenants sharing the same local IDs to prove the UI never merges tenants; no real endpoints and no clinical data. Keep strict TypeScript, accessible labels and RTL-correct layout, and 100% coverage with Vitest. Do NOT touch `apps/api`, `db/`, `contracts/`, `apps/staff-web/check.sh`, `.github/`, `scripts/`, or shared trackers. Record requirement IDs, commands, results and decisions in `.agent/pr-notes/<story-id>.md`.

- [x] CW-008 · Staff web React chart and document screens (synthetic fixtures)
  - pr: #156
  - attempts: 0
  - requirements: CLN-03, CLN-05, CLN-07, UX-01
  - depends: CW-004
  - scope: apps/staff-web/src/features/chart
  - details:
    ReactJS staff-web chart and document screens, in `apps/staff-web/src/features/chart` only. Depends on CW-004's scaffold. Build synthetic-only screens: a longitudinal chart view (problems, allergies, medications, vitals with units), a SOAP note draft with draft/signed states and an entered-in-error banner, and a document list showing PDF/image associations with version and language. Never allow editing a signed revision in the UI; show read-only state and an amendment action instead. Use local fixtures with two tenants sharing local IDs; no real endpoints or patient data. Strict TypeScript, accessible labels, RTL-correct layout and 100% Vitest coverage. Do NOT touch `apps/api`, `db/`, `contracts/`, `apps/staff-web/check.sh`, `.github/`, `scripts/`, or shared trackers. Record evidence in `.agent/pr-notes/<story-id>.md`.

- [x] CW-009 · Staff web React audit and access-review screens (synthetic fixtures)
  - pr: #157
  - attempts: 0
  - requirements: ADM-02, OPS-02, UX-01
  - depends: CW-004
  - scope: apps/staff-web/src/features/admin
  - details:
    ReactJS staff-web administration and audit screens, in `apps/staff-web/src/features/admin` only. Depends on CW-004's scaffold. Build synthetic-only screens: an organization/facility configuration editor with version and effective-date display, a staff membership and role-grant history view (grantor, effective dates, deactivation shown as append-only events), and an audit-event list with actor, purpose, scope, correlation ID, outcome and a chain-verified badge. Show permission-denied and concealment states from local fixtures; no real endpoints, credentials or patient data. Strict TypeScript, accessible labels, RTL-correct layout and 100% Vitest coverage. Do NOT touch `apps/api`, `db/`, `contracts/`, `apps/staff-web/check.sh`, `.github/`, `scripts/`, or shared trackers. Record evidence in `.agent/pr-notes/<story-id>.md`.

- [x] IMP-05 · Auto-merge repair PRs that touch workflow/script files. `guard(pr, repair)` now bypasses the protected-path block for watchdog repairs (`autofix` label or `FIX-` title, detected by `is_repair()`); ordinary story PRs still get `needs-human`. Integrator passes `repair` in `integrate-batch`; CLI `guard --repair` added. Fast gate, tracker protection and the 3-attempt stop remain. `docs/SWARM.md`, watchdog comment and PR body updated.
  - evidence: `test_swarm.py` red first (4 errors) then 61/61 OK; 128 total tooling tests OK; `python3 -I scripts/swarm.py guard --help` shows `--repair`. Evidence source: executed tool commands; no human or clinical evidence. No live repair PR existed to observe end-to-end (no open swarm PRs at verification).

- [x] S-FIX-ACTIONS-01 · Restore green Actions: SpotBugs exposure findings, Doxygen link/return-type errors, and the PR fast tier's fragile `*IsolationTest` exclusion.
  - agent: loop
  - evidence: `AppointmentView` now uses the repo's two-part `List.copyOf` defensive copy (red `AppointmentViewTest` → green); `SchedulingAdminController` constructor carries the house `@SuppressFBWarnings(EI_EXPOSE_REP2)`; Doxygen `{@link}` targets replaced with `{@code}` and `@return` added to the four request records — `scripts/docs.sh` exit 0. Fast tier now tags all 8 `@SpringBootTest` suites `@Tag("integration")` and runs `-Pno-security,fast` (`excludedGroups=integration`) in both `delivery.yml` and `scripts/quick-check.sh`; `scripts/tests/test_fast_tier.py` red first (11 failures) then green, 132 total Python tests OK, `mvn -Pno-security,fast test` ran with no DB. Full `scripts/check.sh` exit 0 (JaCoCo 100%, real PostgreSQL integration green). `gen-opencode.py --check` green; GitHub Workflow skill note updated. Evidence source: executed commands; simulated reviewer plans; no human or clinical evidence. Cancelled queued swarm runs were GitHub superseding stale queued requests (`cancel-in-progress: false` protects the running wave) — no code change.

- [x] S-FIX-SWARM-01 · User directive: set every swarm slot model to `opencode-go/hy4-flash-free`, and add `queue: max` to the `agent-swarm` concurrency group so queued waves are not superseded by newer queued requests.
  - evidence: `scripts/tests/test_swarm.py` assertion red then green (61/61); `scripts/tests/test_workflow_concurrency.py` queue assertion red then green (3/3); 133 total tooling tests OK; model ID verified with `opencode models`; all ten entries in `.github/swarm/slots.json` now carry the requested model. Evidence source: executed tool commands and user directive; no human or clinical evidence.

- [x] S-FIX-SWARM-02 · Make the swarm's definition of done real: full integrator gate, 15-file PR guard, honest status, Java convention guards, failed claims return to `[ ]`, and 10 workers per wave.
  - agent: loop
  - evidence: `swarm-integrator.yml` now runs `scripts/check.sh` with a PostgreSQL service, NVD cache and `OREVIA_PG_ADMIN`; worker keeps the fast gate for feedback. `guard()` labels PRs over `MAX_PR_FILES=15` as `needs-human`; `release()` publishes the task claim to `[ ]` on any failed/no-PR result via `release_claim()`; `reclaim()` also frees claims whose open PR is `needs-human` (planner fetches labels). `agents` default raised to 10 in `swarm.yml`, repair waves still `workers=1`. `agent-run.sh` now says "Fast check". New guards: `scripts/tests/test_java_conventions.py`, `test_swarm.py` (integrator gate, wave size, size guard, claim release), `test_workflow_concurrency.py` (queue). Red first then green; 141 Python tests OK, `scripts/check.sh`, `quick-check.sh`, `docs.sh`, `gen-opencode.py --check` all exit 0. Evidence source: executed tool commands; simulated reviewer plans; no human or clinical evidence. Live integrator/Delivery CI not yet observed on the pushed SHA.

- [x] S-FIX-WATCHDOG-01 · Rename the failed-main repair workflow to `main-repair.yml`, and add `fix-pr-watchdog.yml` triggered by FIX-/autofix pull requests.
  - agent: loop
  - evidence: `main-watchdog.yml` removed; `main-repair.yml` keeps the failed-run `workflow_run` trigger; `fix-pr-watchdog.yml` triggers only on `pull_request` (`opened/synchronize/reopened/labeled`), runs the full `scripts/check.sh` with PostgreSQL/NVD, dispatches `swarm-integrator.yml` on green and `swarm.py fail()` on red. Neither workflow has a `cron:` trigger. `BUDGET_WORKFLOWS` and `docs/SWARM.md` updated. Guards red first then green: 70 swarm tests, 144 total tooling tests OK; `scripts/check.sh`, `quick-check.sh`, `docs.sh`, `gen-opencode.py --check` exit 0; both YAMLs parse with the intended triggers. Evidence source: executed tool commands; no human or clinical evidence. Live PR event not yet observed.

- [x] S-FIX-WORKER-01 · Three-hour swarm worker budget with cooperative finish and automatic restart.
  - agent: loop
  - evidence: `swarm.yml` worker `timeout-minutes: 185` and `AGENT_TIMEOUT: 175m` (10 min slack for bundling); `task_text()` states the 175-minute deadline and that a new worker takes the next task; `DEFAULT_MONTHLY_MINUTES` raised to 10000 (≈5 full 10-worker waves). Guards red first then green: 73 swarm tests, 147 total tooling tests OK; `scripts/check.sh`, `quick-check.sh`, `docs.sh`, `gen-opencode.py --check` exit 0. Restart is the existing `next` job → `swarm.py continue`. Deadline is cooperative; the hard `timeout` still ends a runaway agent and frees the claim. Evidence source: executed tool commands; no human or clinical evidence. No live 3-hour run observed yet.

- [x] S-FIX-PUBLISH-01 · Fix swarm publish failure on workflow-file changes: grant `workflows: write` to the publish and integrator jobs, and recreate the local `swarm-incoming` ref before each bundle fetch.
  - agent: loop
  - evidence: observed failure from `swarm/CW-007-40`/`CW-007-41` — GitHub App refused `.github/workflows/*`, then later slots hit non-fast-forward on `swarm-incoming`. `workflows: write` is NOT a valid GitHub Actions permission scope and made both workflows invalid, so it was removed. Final fix: `publish()` deletes the local `swarm-incoming` ref before each bundle and detects workflow-file changes, releases the claim and skips the push instead of crashing. Guards red first then green: 76 swarm tests, 150 total tooling tests OK; all 8 workflow YAMLs parse; `check.sh`, `quick-check.sh`, `docs.sh`, `gen-opencode.py --check` exit 0. No `SWARM_TOKEN` secret exists; a person must apply workflow changes or supply a token with workflow scope. Evidence source: observed CI errors plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-FRONTEND-01 · Restore the Frontend standards gate after scheduling tests added Node filesystem imports.
  - agent: loop
  - evidence: reproduced check run 135471273322 locally (`npm run typecheck` failed TS2307/TS7006); added `@types/node@22.19.0` and `"node"` to root `tsconfig.json`; made the scheduling RTL test resolve its feature directory by `import.meta.url`; root Vitest excludes React `.tsx` tests owned by staff-web; `bash scripts/frontend-check.sh` exit 0 with staff-web 103 tests, patient-mobile 0 tests, root 23 tests. Evidence source: local CI gate and GitHub Actions job log 135471273322.

- [x] IMP-03 · Stop concurrency-cancelling swarm delivery runs. `delivery.yml` (per-ref group, `cancel-in-progress: false`), `heavy-check.yml`, `agent-image.yml` and `swarm-images.yml` now queue; `swarm.yml`, `swarm-integrator.yml`, `main-watchdog.yml` and `opencode-team.yml` already queued. README-SETUP.md updated.
  - evidence: `scripts/tests/test_workflow_concurrency.py` (red first: 5 failures) now green — parses every `.github/workflows/*.yml`, asserts each declares a non-empty group and `cancel-in-progress: false`, and locks delivery to a per-ref group. `scripts/quick-check.sh` exit 0, 125 tooling tests OK, all eight workflow YAMLs parse. GitHub Workflow reviewer (simulated) found that a SHA-scoped group would remove serialization and allow unbounded parallel full verification; that was reverted to a per-ref group. Its heavy-check SHA-attribution finding is deferred as IMP-04 / CW-IMP03-01. `check.sh`/`docs.sh` remain red on pre-existing G1 SpotBugs and Doxygen defects, unrelated to this change. Evidence source: tool commands plus simulated reviewer; no human or clinical evidence.

- [x] IMP-04 · Heavy-check SHA attribution (owner: `.github/workflows/heavy-check.yml`, `scripts/swarm.py`): queued heavy checks check out `main` at execution time, but the watchdog passes the trigger-time `headSha` to the repair worker, so a delayed run can cause a repair on the wrong commit. Pin the checkout to the trigger SHA, or record the executed SHA as a step output and prefer it in `repair_task`. Tracked as CW-IMP03-01 in BUGS.md.
  - evidence: obsolete, `.github/workflows/heavy-check.yml` was removed when Delivery took over the full tier on main (PR #158), so there is no heavy-check SHA to attribute.

- [x] IMP-01 · Delivery skill note: inside a cloud worker the worktree `opencode.json` is the CI profile under `skip-worktree`, so policy checks judge that profile, not HEAD truth. Added a warning to step 1 of `.opencode/skills/orevia-delivery/SKILL.md` (use `git show HEAD:opencode.json` or state the profile). Closes the instruction gap behind the false OPS-CW-003-01 finding.
  - evidence: skill note verified by file read; `python3 -I scripts/gen-opencode.py` regenerated both profiles with no diff (skills are not in the generated config); `scripts/check.sh` and `check-pack.py` green. Evidence source: tool.

- [x] IMP-02 · Pin the worker CLI and image: `ARG OPENCODE_VERSION=2.3.2` in `.github/agent-image/Dockerfile`; workflow dispatch default and fallback both `2.3.2`; publish step prints and uploads the resolved image digest. Closes OPS-CW-003-02 (base `:latest` tag still floats; digest is recorded on every build).
  - evidence: `scripts/tests/test_agent_image.py` 4/4 OK (red before the pin, green after); full `scripts/check.sh`, `quick-check.sh`, `docs.sh` exit 0; YAML inspected; workflow publishes `agent-image-published` digest artifact. The push rebuilt the image on `89d3ad2` (Agent image run 44478095775, check `build` success), so the pinned CLI and digest-recording step were exercised in CI. Evidence source: tool commands plus CI; no human or clinical evidence.

- [x] S-PLAN-01 · One-week execution plan at one wave per hour: all 59 executable open tasks mapped into 7 waves below; swarm fallback cron changed from every 3 hours to hourly.
  - evidence: programmatic coverage check (59 tasks, no duplicates, no unscheduled open task); `scripts/tests/test_swarm.py` hourly-cron guard red then green; 148 tooling tests OK; `check.sh`, `quick-check.sh`, `docs.sh`, `gen-opencode.py --check` exit 0. S-G0-04 remains blocked and unscheduled. Evidence source: executed tool commands; no human or clinical evidence.

- [x] S-FIX-REPAIR-01 · `main-repair.yml` now runs only on `pull_request` events for `FIX-`/`autofix` PRs; the `fix-pr-watchdog.yml` duplicate was deleted. No failed-run or cron trigger.
  - evidence: observed `Main repair` run `44491531055` was a `workflow_run` event, which the user rejected. Guards red first then green: 76 swarm tests, 150 total tooling tests OK; all 8 workflow YAMLs parse with `main-repair.yml` triggered by `pull_request` only; budget workflow list reduced to six. Evidence source: `gh run list` plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-SWARM-TOKEN-01 · Use the added `SWARM_TOKEN` secret for workflow-file pushes: publish sets `SWARM_WORKFLOW_PUSH` when the secret exists, and `publish()` allows `.github/workflows/*` changes only with that flag.
  - evidence: `gh api repos/dr3mro/Aurevia-Care/actions/secrets` lists `SWARM_TOKEN` updated 2026-11-19T09:26:24Z. Guards red first then green: 77 swarm tests, 151 total tooling tests OK; all 8 workflow YAMLs parse. Evidence source: GitHub API plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-INTEGRATOR-01 · Run the integrator only for successful swarm waves. Failed/cancelled waves publish no PR, but `workflow_run: completed` still triggered three runs per batch. Added a `pick` job guard plus regression test.
  - evidence: `gh run list --workflow=swarm-integrator.yml` showed two failure-triggered runs (`44496897170`, `44496943529`) and one success-triggered run (`44496999654`) within 41 seconds. Guards red then green: 81 swarm tests, 155 total tooling tests OK; all 8 workflow YAMLs parse. Evidence source: GitHub run API plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-EMPTY-WAVE-01 · Swarm waves had zero workers: `plan_workers()` requires a `scope` field and all 62 open spec stories lacked one; the only two scoped tasks were at `attempts: 3`. GitHub therefore rendered the raw `${{ matrix.slot }}` for a skipped matrix job. Added distinct `scope`, `reserved: V9…V57` and `details` to 49 dispatchable stories, cleared stale attempt counters, made the worker job name static (`Swarm worker`), and added a regression test asserting at least 10 dispatchable tasks.
  - evidence: `plan_workers(10, slots, TODO.md, [])` now returns 10 story tasks (was 0); PR #31/#32 merge re-added duplicate `S-FIX-FRONTEND-01`, removed. Guards red then green: 158 tooling tests OK, `check-docs.py`/`check-secrets.py`/`gen-opencode.py --check` green, all 8 workflow YAMLs parse, `quick-check.sh` exit 0. Evidence source: executed tool commands and GitHub run API; no human or clinical evidence.

- [x] S-FIX-FLAKY-01 · `scripts/check.sh` failed intermittently: `test_publish_twice_does_not_reuse_a_stale_incoming_ref` pushed a second bundle to an already-existing branch, so within the same second the commit SHA matched (no-op, pass) and across seconds it was a non-fast-forward (fail). Second bundle now uses a distinct branch, preserving the stale-ref test deterministically.
  - evidence: isolated runs showed `check.sh` pass/fail alternating; five consecutive full `check.sh` runs exit 0 after fix; 158 tooling tests OK. Evidence source: executed tool commands; no human or clinical evidence.

- [x] S-FIX-TEAM-PUSH-01 · OpenCode team's `publish` step failed when an agent changed `.github/workflows/*`: `github.token` (App) lacks workflow scope. Added `PUSH_TOKEN` in `agent-run.sh` `github_git()` (falls back to `GH_TOKEN`) and wired `secrets.SWARM_TOKEN` only to that variable, keeping `gh` CLI on the App token because a classic PAT without `read:org` breaks GraphQL calls.
  - evidence: run 44503284412 job 135488337891 showed `remote rejected ... workflow .github/workflows/swarm.yml without workflows permission`. New test `test_push_token_overrides_gh_token_for_git_only` passes; 8 agent-run tests, 159 total tooling tests OK; `check-docs.py`/`check-secrets.py` green. Evidence source: GitHub Actions log plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-BRANCH-ID-01 · All 10 swarm workers failed at publish with `refusing unexpected branch name 'swarm/S-G0-06-64'`: `task_id_of()` only recognised one-hyphen IDs (`CW-001`), while new spec stories use multi-hyphen IDs (`S-G0-06`). Fixed the parser to strip only the trailing numeric run suffix; added a regression test covering `CW-001`, `S-G0-06`, `S-G1-02`, `X-01`, `FIX-444`.
  - evidence: user-supplied CI log named all 10 branches; local check confirms each now maps to its task ID; 160 tooling tests OK across three consecutive runs, `quick-check.sh`, `check-docs.py`, `check-secrets.py`, `gen-opencode.py --check` green. Evidence source: CI log plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-WAVE-AMP-01 · Failed workers released claims via `update_todo()`, which committed `TODO.md` with `SWARM_TOKEN`; every such push triggered another `swarm.yml` wave, producing 12 queued waves from 10 failures. Tracker-only commits now carry `[skip ci]`; stale queued waves were cancelled. Added a regression test.
  - evidence: `gh run list --workflow=swarm.yml` showed 12 pending/in-progress waves; all queued ones cancelled; 161 tooling tests OK. Evidence source: GitHub run API plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-TEMP-1WORKER-01 · Wave `44509359962` publish failed on transient `HTTP 502 Bad Gateway` from `gh pr create` after all 10 branches pushed and 10 PRs created. Temporarily set swarm worker default to 1, added a bounded retry (3 attempts) for 502/503/504 in `gh()`, then observed wave `44516061744` (SHA `344b482`) complete with `Publish results: completed success`. Restored `default: '10'` / `inputs.workers || '10'` and the ten-worker test. Note: that publish was empty (worker produced no commits), so the retry path was not exercised end-to-end; the permanent 502 retry remains in place.
  - agent: loop
  - evidence: run 44509359962 job 135527003974 log (`gh pr create ... HTTP 502`); run 44516061744 publish success. Retry guard tests (`test_gh_retries_transient_api_errors`, `test_gh_does_not_retry_permanent_errors`) pass; 164 tooling tests OK; `check-docs.py`/`check-secrets.py`/`gen-opencode.py --check`/`quick-check.sh` green. Evidence source: GitHub Actions logs plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-MIG-GAP-01 · Delivery PR policy failed with `migration version gap: expected V9, found V17`: each of 10 parallel workers owns a reserved version (V9…V57) and its branch contains only V1–V8 plus its own migration, so absolute contiguity from V1 is impossible. Removed the contiguity rule; duplicate versions remain an error. Updated README and decision 0002.
  - evidence: run 44514680043 job 135527480395 log. Guards red then green: `test_gapped_versions_are_allowed`, `test_duplicate_version_is_an_error_even_with_gaps`; 35 migration tests, 164 total tooling tests OK. Evidence source: GitHub Actions log plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-APT-01 · Developer reference job failed on transient Ubuntu mirror 503s while `apt-get install doxygen`. Added a bounded 3-attempt retry with backoff in `delivery.yml` and a guard test.
  - evidence: run 44521340509 job 135550448273 log shows `503 Service Unavailable` for `doxygen_1.9.8...deb`, `libfmt9`, `libxapian30`. 165 tooling tests OK; all workflow YAMLs parse; `check-docs.py`/`check-secrets.py`/`gen-opencode.py --check` green. Evidence source: GitHub Actions log plus executed tool commands; no human or clinical evidence.

- [x] S-VERIFY-10WORKER-01 · Confirmed a full 10-worker wave runs end-to-end after restoring the default. Run `44521340515` (SHA `f3e3776`): `Plan the wave`, all 10 workers (3 fix + 7 story), `Publish results` and `Start the next wave` all `completed/success`. The 502 retry and migration-gap fix hold at scale.
  - evidence: `gh run view 44521340515 --json jobs` output above; no failures on any HEAD checks during the watch window. Evidence source: GitHub Actions run API; no human or clinical evidence.

- [x] S-FIX-INTEGRATOR-RACE-01 · Integrator run `44530039145` failed after a 14-minute full gate: its single `HEAD:main` push was rejected because `[skip ci]` tracker commits advanced `main` meanwhile. `integrate_batch()` now retries the push up to 3 times with `fetch` + `rebase origin/main` + re-gate, aborting the rebase on conflict. `update_todo()` already had this retry.
  - evidence: run 44530039145 job 135580706334 log shows `! [rejected] HEAD -> main (fetch first)` at `authed_git("push", "origin", "HEAD:main")`. New tests `test_integrate_batch_retries_when_main_advanced` and `test_integrate_batch_aborts_rebase_on_conflict` pass; 167 tooling tests OK; `check-docs.py`/`check-secrets.py`/`gen-opencode.py --check` green. Evidence source: GitHub Actions log plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-INTEGRATOR-REBASE-01 · Integrator `44540737335` (SHA `e9ecdb8`, with the push-retry fix) still failed: retry used `git rebase origin/main`, which needs a committer identity (`fatal: empty ident name`) and replays PR merge commits. Replaced with `git reset --soft origin/main` (keeps the already-gated tree staged), `git checkout origin/main -- TODO.md docs/swarm` to adopt fresh trackers, re-apply `record_merge`, re-gate, push. No commit replay.
  - evidence: run 44540737335 job 135618147530 log shows `fatal: empty ident name` during `Rebasing (1/4)`. Updated tests `test_integrate_batch_retries_when_main_advanced` / `test_integrate_batch_restores_trackers_on_retry`; 167 tooling tests OK; `check-docs.py`/`check-secrets.py`/`gen-opencode.py --check`/`quick-check.sh` green, all workflow YAMLs parse. Evidence source: GitHub Actions log plus executed tool commands; no human or clinical evidence.

- [x] S-FIX-JAVADOC-01 · Delivery `Developer reference` failed on `bc2667b`/`0d8e389` after worker PRs merged: Doxygen `WARN_AS_ERROR` reported 13 Javadoc errors in merged code (`LabPolicyCatalog`, `LabTypes`, `ReleasedReportService`, `TemplateService`). Root cause of recurrence: the integrator's gate `scripts/check.sh` does not run `scripts/docs.sh`, and Delivery's `docs` job is skipped on `pull_request` events — so broken Javadoc only surfaces after merge.
  - Fix: (a) repaired all Javadoc — record-level `@return`, full `@param` on compact constructors, no inner-method `@param` (Doxygen merges them into the record constructor doc), documented enum constructor, `{@code Actor}` instead of an unresolvable `{@link}`. (b) Systemic: added `scripts/integrator-gate.sh` = `check.sh` + `docs.sh`, wired as the integrator `GATE` with a Doxygen install step (with apt retry). Guard test added.
  - evidence: run 44544369924 job 135631117346 log; local `bash scripts/docs.sh` exit 0 after fixes; 168 tooling tests OK, `check.sh`/`quick-check.sh`/`check-docs.py`/`check-secrets.py`/`gen-opencode.py --check`/shellcheck green, all workflow YAMLs parse. Evidence source: GitHub Actions log plus executed tool commands; no human or clinical evidence.

- [x] AUD-20261119-01 · Constrain patient released-revisions endpoint to the verified patient chart link
  - pr: #174
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/exchange/release, apps/api/src/main/java/com/aurevia/messaging/reports
  - details:
    PatientReleasedRevisionsController exposes GET /api/v1/patients/{patientId}/released-revisions and passes the path patientId straight into ReleaseService.visibleRevisions (PatientReleasedRevisionsController.java:38-40). That method only calls authorize(context, RELEASE_ACCESS_QUERY, patientId) (ReleaseService.java:333-356), which checks active membership plus a role in {clinician, tenant_admin, reception, privacy_auditor} (ReleaseActionPolicy.java:33-50); it never verifies that the authenticated subject is linked to that patientId. Any receptionist or privacy auditor can therefore enumerate released report revision IDs for any patient by supplying its UUID, while a verified patient (role 'patient') is denied 403. The correct patient-facing service ReleasedReportService, which checks a verified + active chart link and release state on every list/download (messaging/reports/ReleasedReportService.java:62-129, 143-150), has no caller in main outside its own Javadoc/test references, so PAT-02 patient self-access is unreachable at runtime. Expected fix: route the endpoint through ReleasedReportService (or add equivalent verified-link and self-only checks to ReleaseService.visibleRevisions), drop reception/privacy_auditor from the patient-scoped read policy, and add negative tests for a staff reader requesting another patient's revisions and for a patient reading their own.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261119-02 · Verify the patient-unit connection when opening a conversation
  - pr: #175
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/messaging/conversations, apps/api/src/main/java/com/aurevia/messaging/units
  - details:
    ConversationService.open (ConversationService.java:76-105) accepts patientId, patientSubject, unitKey and connectionId from the JSON body. For a patient caller it only checks subject == patientSubject and repository.patientLinkActive (a patient-to-chart link); connectionId is merely format-validated at line 80 and persisted at line 99 with no check that an active PatientUnitConnection binds this patient to that unit. A verified patient can thus open a thread with any of the five units (clinic/lab/radiology/pt/pharmacy) just by naming the unitKey, violating MSG-01's requirement to validate patient, destination and context on the server at connection time and every protected action and to block arbitrary directory contact. The integration test ConversationFlowTest.java:331-343 even opens with unitKey 'lab' and a fixed 'conn-1' without proving a connection. CareUnitDirectoryService.requireActiveConnection (messaging/units/CareUnitDirectoryService.java:209-220) already implements the needed check but a repo-wide grep shows zero callers in main. Expected fix: call that check on open/reply using server-derived connection and unit identity, reject client-supplied connectionId values not bound to the caller, and add a no-connection/wrong-unit negative test.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261119-04 · Rename story-ID-named patient-mobile feature packages
  - pr: #169
  - attempts: 0
  - scope: apps/patient-mobile/src/features
  - details:
    All 12 directories under apps/patient-mobile/src/features are named s_g3_01 through s_g3_12 (story IDs), for example features/s_g3_02/credentials.ts and features/s_g3_06/attachments.test.tsx. CODING_STYLE.md requires business-domain naming and the earlier refactor already removed Java s_g* packages; this mobile remainder is called out as out of scope in .agent/pr-notes/REFACTOR-PACKAGES.md section 6 but is not a tracked TODO entry. Expected fix: agree an owner-approved domain mapping (appointments, reports, enrollment, units, conversations, attachments, instructions, acknowledgments, service-profiles, governance, flows, evidence), git mv the folders and update imports and tests.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261120-07 · Inventory every in-memory store and write the persistence decision record
  - pr: #197
  - attempts: 0
  - scope: docs/decisions
  - details:
    Problem (verified): production code has many in-memory implementations whose data disappears on restart. Spring beans (`@Repository`): `api/x_02/InMemoryDeliveryStore`, `messaging/service/InMemoryServiceProfileStore`, `messaging/units/InMemoryMessagingStore`, `messaging/flows/InMemoryFlowPolicyStore`, `messaging/governance/InMemoryMessagingGovernanceStore`, `messaging/acknowledgment/InMemoryInstructionFactStore`. Other in-memory classes (check whether anything wires them): `clinical/templates/InMemoryTemplateStore`, `api/authorization/InMemoryDecisionSink` (authorization decisions), `api/x_01/IdempotencyLedger.InMemoryIdempotencyLedger`, `api/x_03/InMemoryDocumentStore`, `messaging/attachments/InMemoryAttachmentStore`, `messaging/enrollment/InMemoryPatientAccessStore`, `messaging/reports/InMemoryReportReleaseStore`, `messaging/instructions/InstructionStore.InMemory`, `therapy/InMemoryTherapyStore`. This task writes ONLY a document; no Java.
    Create `docs/decisions/0003-persistence-of-in-memory-stores.md` from `docs/decisions/template.md` (keep every heading, set Status: proposed, Date: today's date in YYYY-MM-DD, Requirement IDs: ARC-01, ARC-02, OPS-02).
    Fill the Context section with a table, one row per class above, with these columns:
    | Class | Interface it implements | Is it a Spring bean (`@Repository`/`@Component`)? | Who injects the interface (grep `private final <Interface>`) | Durable table that already exists (grep `db/migrations`) or "none" |
    Get each answer from the code with `grep`; never guess. Add a short Decision section that orders the work: first stores that are Spring beans and already have tables, then stores that need a new migration (use reserved versions V59 and up, one per store), then stores nobody injects (remove them or mark them orphans for AUD-20261119-03).
    Add in Consequences: one line stating that restart loses data for every `@Repository` in the table, and one line naming `InMemoryDecisionSink` and `IdempotencyLedger` as security-relevant (audit decisions and replay protection must be durable before production).
    Then run `python3 -I scripts/check-docs.py` and fix any complaint (heading style, links).
    Do NOT change Java, SQL or other docs. Done when: the file exists, every row in the table is filled from real grep output, and `check-docs.py` passes.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` must pass for the classes you touched. Synthetic data only; write the failing test first; stay inside the declared scope; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] READY-12 · Production-readiness evidence checklist for WEB-01 to WEB-13
  - pr: #201
  - attempts: 0
  - scope: docs/operations/production-readiness
  - details:
    Create `docs/operations/production-readiness/README.md` that turns spec section 8.1 into an evidence checklist a person can sign. Documentation only: no code, no workflow changes.
    1. Start with a heading `# Production readiness evidence`, a short paragraph saying which spec section it implements, and the line `Status: draft, owners to complete`.
    2. Add one table with columns `Requirement | Evidence needed | Produced by | Owner | Status | Link`. One row per requirement WEB-01 to WEB-13 (read each row in `PROJECT_V1_SPEC.md` section 8.1). `Evidence needed` quotes the "Verify with ..." part in your own words. `Produced by` is one of `CI`, `Infrastructure owner`, `Security reviewer`, `Product owner`. Leave `Owner` as `unassigned`, `Status` as `not started`, `Link` empty. Use the repository facts: WEB-02, WEB-05, WEB-06 and WEB-09 are implemented by tasks READY-02, READY-01, READY-05 and READY-07; WEB-03 by READY-11; WEB-04 by READY-03 and READY-04; WEB-08 by READY-08; WEB-13 by READY-09; WEB-01, WEB-10, WEB-11 and WEB-12 need an infrastructure or CI change that workers cannot make (workers cannot edit `.github/workflows`).
    3. Add a section `Needs a person` listing exactly: WAF and TLS setup, certificate automation, SHA-pinning CI actions, end-to-end browser suite and its CI job, penetration test, progressive-rollout tooling.
    4. Run `python3 -I scripts/check-docs.py` and fix every complaint (links, unknown requirement IDs).
    Do NOT invent evidence, links or owners, and do NOT mark any row done.

    Run the checks yourself before you finish: `bash scripts/worker-check.sh` (API tasks) or `bash apps/staff-web/check.sh` (staff web tasks) must pass. Synthetic data only; write the failing test first; stay inside the declared scope; name folders and packages after what they hold, never a task ID; record what you did and the command output in `.agent/pr-notes/<story-id>.md`.

- [x] AUD-20261120-10 · Attachment event history returns every event in the tenant, not the requested file's trail
  - pr: #220
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/messaging/attachments, apps/api/src/test/java/com/aurevia/messaging/attachments
  - details:
    AttachmentService.events(actor, id) in apps/api/src/main/java/com/aurevia/messaging/attachments/AttachmentService.java:293 returns store.events(actor.organizationId()).stream().toList() - the entire tenant-wide attachment event log - after checking visibility only for the one file named by id. Its sibling view() at line 273 correctly filters with fileId.equals(fact.attachmentId()). AttachmentEvent.detail (AttachmentContract.java:349-351) carries scanner detail and void/rejection free text, so a caller authorized for one file receives other files' event details across conversations in the same tenant (MSG-07, ARC-07). InMemoryAttachmentStore.events(String organizationId) at line 66 confirms the store query is per-organization, so this is a cross-file/cross-conversation leak within a tenant, not a cross-tenant one. The existing test twoTenantsWithIdenticalLocalIdentifiersStayIsolated in AttachmentAcceptanceTest.java:428-431 only asserts every returned event has the caller's organizationId, so it never catches the missing per-file filter and 100% line coverage still passes. The service currently has no HTTP controller (already tracked as TODO AUD-20261119-03), so the leak is latent until that entry point is wired. Expected fix: filter the store events by attachmentId in events() exactly as view() does, and add a test that creates two attachments in one tenant and asserts events(a.id()) contains only a's events.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261120-11 · Reserved migration V59 is already used by the DLV-03 repair; READY-35 will collide
  - pr: #218
  - evidence: PR #218; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/AUD-20261120-11.md; simulated review only
  - attempts: 0
  - scope: db/migrations
  - details:
    db/migrations/V59__rx02_ambiguous_transition.sql already exists (added by the DLV-03 repair; its header is 'Change: expand' and .agent/pr-notes/DLV-03.md records V59 as consumed). However TODO.md line 817 says the persistence tasks reserve V59 to V66, and READY-35 (lines 821-830) reserves V59 and tells the worker to create db/migrations/V59__service_profiles.sql. When that worker runs, Flyway will see two migrations with version 59 and scripts/check-migrations.py will fail with 'duplicate migration version V59', so the first persistence story cannot go green until the tracker is corrected. Expected fix by the tracker owner: reassign READY-35..READY-42 to unused versions (for example V60..V67) and update each reserved: field, the 'V59 to V66' sentence, the V59__service_profiles.sql path in READY-35, and the proposed V59..V64 mapping in docs/decisions/0003-persistence-of-in-memory-stores.md before dispatching READY-35.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261120-12 · Patients cannot read instruction content: the only instruction reads deny the patient role
  - pr: #219
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/messaging/instructions, apps/api/src/test/java/com/aurevia/messaging/instructions
  - details:
    GET /api/v1/patient-instructions (InstructionController.java:120-123, listForPatient) and GET /api/v1/patient-instructions/{threadId} (line 109) both go through InstructionService.execute with action InstructionActions.READ. InstructionActions.java:40-41 grants READ only to staffSender roles plus tenant_admin and privacy_auditor; 'patient' is not in that set, so a verified patient gets 403 on every instruction-content read, even though the endpoint Javadoc says 'visible to a verified patient' and MSG-04/MSG-05/PAT-02 require patients to receive and acknowledge instructions. The only patient-reachable instruction route, /api/v1/instructions/{id}/facts served by InstructionFactService.facts, returns dispatch/view/acknowledgment fact metadata but never the instruction title or body. Every other messaging action matrix in main (ConversationActions, ServiceProfileActions, MessagingActions, InstructionFactActions) explicitly grants the patient role; this one does not. Also, listForPatient takes any patientId from the query string and filters by staffAssignedToUnit after authorization, so simply adding the role would still return nothing for a patient. Expected fix: add a patient-authorized read branch that binds the caller to the chart link/connection (for example allow READ for 'patient' and resolve patientId from the authenticated subject rather than the request parameter), keep the staff branch, and add negative and positive tests for patient self-read and wrong-patient denial.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261120-13 · Document link accepts order or result subjects without verifying they exist
  - pr: #222
  - evidence: PR #222; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/AUD-20261120-13.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/documents, apps/api/src/test/java/com/aurevia/documents
  - details:
    DocumentService.link in apps/api/src/main/java/com/aurevia/documents/DocumentService.java:75-112 validates subjectType as chart/order/result (DocumentInputs.java:62-66) but only checks target existence for CHART: 'if (CHART.equals(subjectType) && !repository.chartExists(subjectId))' at line 91. DocumentRepository.java:74 exposes only chartExists, so there is no existence check for the order and result cases. An authorized caller can therefore create an original-document association bound to a non-existent order or result identifier, leaving orphan clinical artifacts that violate ARC-01's tenant-owned key model and CLN-07's 'link original PDFs and images to chart, order or result'. Expected fix: add RLS-bounded SELECT EXISTS checks for order and result targets (or a single subjectExists method covering all three types) and return 404 when absent, with a test per subject type asserting the rejection.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261120-14 · Wire the generated command authorization gate into production write paths
  - pr: #229
  - evidence: PR #229; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/AUD-20261120-14.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/api/authorization, contracts/openapi
  - details:
    The X-04 server-side authorization control is inert in production. CommandAuthorization plus its 24-command CommandCatalog, generated OpenAPI contract and generated TypeScript client are never invoked by a request: `grep -rln CommandAuthorization apps/api/src/main/java` returns only CommandAuthorization.java and InMemoryDecisionSink.java; `new CommandAuthorization(` appears only at CommandAuthorization.java:359 inside the test-only factory `synthetic(...)`; `contracts/openapi/generated/client.ts` is imported by no source under apps/staff-web/src or apps/patient-mobile/src. contracts/openapi/README.md itself records that wiring the gate and a POST-mapping-to-catalog parity test are unowned follow-on work, and TODO has no entry for it (READY-41 only adds a durable DecisionSink). The documented IAM-01/PRD-06/ARC-03/ARC-04/OPS-02 gate and its decision ledger therefore never run; each controller relies solely on its own policy and no command yields an authorization decision. Expected fix: provide CommandAuthorization as a Spring bean with the durable sink from READY-41, call it from each owning write endpoint for the 24 catalog commands, and add a build-failing parity test that fails CI when a catalog command has no production caller.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261120-15 · Remove the duplicate legacy chart slice and its unhardened write path
  - pr: #225
  - evidence: PR #225; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/AUD-20261120-15.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/chart, apps/api/src/main/java/com/aurevia/patient/chart
  - details:
    Two complete 11-class chart implementations coexist: chart/record (delivered by S-G1-03) and patient/chart, whose own comment at chart/record/ChartController.java:23 calls it 'a legacy chart slice'. Both expose live HTTP routes and write the same public.chart_entry table: POST /api/v1/chart/records/entries vs /api/v1/chart/entries, and GET .../patients/{patientId}/entries on each prefix. They have diverged in clinical-safety hardening. chart/record/ChartService.java:292-297 bounds free text before audit/catalog keys and normalizes blank reasons; chart/record/ChartRepository.java:178 sets `clinical_author = COALESCE(clinical_author, :actor)` on verification so a recorder-open entry gains an accountable clinical author. The patient/chart equivalents have neither: its ChartRepository.verify leaves clinical_author null and its ChartService has no requireBounded/normalizedReason. A record verified through the legacy route therefore violates CLN-03's separate clinical author and can hit DB CHECK failures on oversized text, with 100% coverage still passing because both slices are tested independently. Expected fix: keep one contract, delete or deprecate the legacy package and routes, consolidate the hardening, and add an integration test that verifies a null-clinical-author entry through the surviving public route.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.

- [x] AUD-20261120-16 · Verify order and result subjects in document render and rendered-version reads
  - pr: #224
  - evidence: PR #224; integrator ran scripts/integrator-gate.sh on the merge result (tool evidence); Delivery re-verifies main after the push; notes in docs/swarm/AUD-20261120-16.md; simulated review only
  - attempts: 0
  - scope: apps/api/src/main/java/com/aurevia/documents, db/migrations
  - details:
    AUD-20261120-13 added RLS-bounded subjectExists checks to DocumentService.link, but the sibling paths are still unguarded. DocumentService.java:235 (render) repeats `if (CHART.equals(subjectType) && !repository.chartExists(subjectId))`, and renderedVersions at line 356 has no existence check at all, although both accept subjectType chart/order/result (DocumentInputs.java:62-66). V17__document_associations.sql:84-107 stores rendered_document.subject_id as a bare uuid with no FK to an order or result table. A clinician can therefore create an immutable rendered PDF bound to a non-existent order/result UUID when sourceAssociationId is omitted, or list renders for a bogus subject, producing exactly the orphan artifact link now rejects (ARC-01, CLN-07, XCH-01/XCH-04). Expected fix: call the existing subjectExists in render and renderedVersions, return 404 for a missing order/result target, and add a test per subject type asserting rejection without lowering any coverage threshold.
    Found by the daily audit. Synthetic fixtures only; failing tests first;
    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.
