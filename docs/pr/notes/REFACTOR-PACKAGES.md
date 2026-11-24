# REFACTOR-PACKAGES — dispatch-69

**Status: inventory complete. No file has been moved or edited.** Dispatch-69 rule 2
requires the owner to approve the old-to-new mapping before any `git mv`.

Working tree at inventory: `main`, HEAD `fcf7f76`, clean, shallow clone (`git log` shows
one commit). Evidence source for this file: executed `find`/`grep`/`git` commands and file
reads; no reviewer, simulated user or human participant evidence.

## 1. Inventory of story-ID packages

The earlier refactor (`.agent/pr-notes/REFACTOR-PACKAGES-20261119-201815.md`, merged) already
renamed every `g0/s_g0_*` … `g4/s_g4_*` and `messaging/s_g3_*` package, plus their test
mirrors. Verified on the current tree: no package directory matches `s_g*`, `g[0-4]`, and no
class/interface/enum/record name contains a story ID.

| Package (main) | Story | Classes | What the code does |
|---|---|---|---|
| `com.aurevia.api.x_01` | X-01 · API versioning, idempotency, cursor pagination, correlation IDs, machine-readable errors (ARC-03, ARC-04) | 8: `ApiVersion`, `ApiErrorCode`, `Cursor`, `PageRequest`, `IdempotencyKey`, `IdempotencyLedger`, `RequestDigest`, `ByteParts` | Wire-contract primitives: accepted API versions, stable error codes, tenant-scoped cursor paging, idempotency keys/ledger, canonical request digests |
| `com.aurevia.api.x_02` | X-02 · Durable events, outbox, deduplication, replay, dead-letter review (ARC-05) | 7: `DurableEventContract`, `DurableEventService`, `DeliveryStore`, `InMemoryDeliveryStore`, `DeliveryActions`, `DeliveryInputs`, `DeliveryController` | Outbox/delivery state machine, consumer receipts and dedup, dead-letter review, delivery HTTP endpoints |
| `com.aurevia.api.x_03` | X-03 · Upload validation, quarantine, mediated downloads, PDF binding to immutable revisions (ARC-06) | 8: `DocumentContract`, `DocumentInputs`, `DocumentActions`, `DocumentGuard`, `DocumentStore`, `InMemoryDocumentStore`, `QuarantineService`, `PdfBindingService` | Upload validation, private quarantine and scan verdicts, mediated metadata-only downloads, async PDF jobs bound to immutable revisions |
| `com.aurevia.api.x_04` | X-04 · Generated OpenAPI clients and server-side authorization for every command (PRD-06) | 5: `CommandCatalog`, `CommandDefinition`, `CommandAuthorization`, `InMemoryDecisionSink`, `CommandClientGenerator` | 24-command catalog driving the OpenAPI contract + typed client; per-command authorization decision with fresh membership |

Test mirrors: `api/x_01` (`ApiContractPrimitivesTest`, `IdempotencyLedgerTest`),
`api/x_02` (`DeliveryDomainTest`, `DurableEventServiceTest`), `api/x_03`
(`DocumentArtifactTest`), `api/x_04` (`CommandAuthorizationTest`,
`CommandClientGeneratorTest`) — 7 files.

Story titles were read from `TODO.md` and `.agent/pr-notes/X-03.md` / `X-04.md`.
`docs/swarm/X-01.md` … `X-04.md` do not exist (that directory holds `S-G*` notes only).

## 2. Proposed mapping

| Old package | New package | Reason |
|---|---|---|
| `com.aurevia.api.x_01` | `com.aurevia.api.contract` | The classes are the versioned HTTP contract itself (version, error codes, paging, idempotency, digest). Alternative: merge into the existing `api` root next to `ApiException`/`ApiErrorHandler`/`IfMatch`. |
| `com.aurevia.api.x_02` | `com.aurevia.events` | Durable event delivery/outbox is its own platform domain (ARC-05); it is not partner `exchange`, and `db/migrations/V55__durable_event_outbox.sql` already names the concept `durable_event_outbox`. |
| `com.aurevia.api.x_03` | `com.aurevia.documents` (merge) | `documents` already owns original-document associations and PDF rendering (CLN-07, V17). ARC-06 upload/quarantine/binding is the same domain. `.agent/pr-notes/X-03.md` already records this convergence as the intended follow-up. |
| `com.aurevia.api.x_04` | `com.aurevia.api.authorization` | API-command authorization + generated client. This is also the path TODO X-04 already declares as its scope, so no tracker edit is needed. Alternative: `authz.command`. |

| Old test package | New test package |
|---|---|
| `…aurevia.api.x_01` | `…aurevia.api.contract` |
| `…aurevia.api.x_02` | `…aurevia.events` |
| `…aurevia.api.x_03` | `…aurevia.documents` |
| `…aurevia.api.x_04` | `…aurevia.api.authorization` |

Totals: 28 main files + 7 test files = 35 moved with `git mv`, plus package/import edits.

## 3. Simple-name collisions

Every target simple name was counted across `apps/api/src` (main + test). One collision:

| Name | Existing | Moved | Resolution |
|---|---|---|---|
| `DocumentInputs` | `documents/DocumentInputs` (CLN-07 chart-document commands) | `api/x_03/DocumentInputs` (ARC-06 upload/quarantine/binding commands) | Rename the moved class to **`ArtifactInputs`**: its contract type is already `DocumentContract.Artifact`/`PdfBinding`/`MediatedDownload`, and the existing `documents.DocumentInputs` fits its own package name better. |

All other names (`Cursor`, `PageRequest`, `DocumentStore`, `DocumentContract`, `DocumentGuard`,
`DocumentActions`, `CommandAuthorization`, …) occur exactly once in the tree.

## 4. Near-duplicates listed, not merged (dispatch-69 rule 4)

- `documents/PdfRenderer` renders PDF bytes; `api/x_03/PdfBindingService` binds an already
  rendered PDF to an immutable revision and gates release. Adjacent, different responsibility.
- `documents/DocumentPolicy` (chart association/authorization for originals) vs
  `api/x_03/DocumentGuard` (upload/scan/release role table and audit facts).
- `documents/DocumentRepository` (JDBC, tenant-scoped rows) vs `api/x_03/DocumentStore`
  (in-memory artifact store boundary).
- Carried over from the earlier run and still separate: `ClinicalNoteRevision` /
  `TemplateNoteRevision`, `TemplateActor` / `PatientAccessActor`, `AccessionCommands` /
  `CheckInCommands`, `LabObservation` / `TrendObservation`.

## 5. References outside the packages that name the old paths

| File:line | Current text | Proposed text | Note |
|---|---|---|---|
| `contracts/x_04/generated/manifest.json` | `"generator": "com.aurevia.api.x_04.CommandClientGenerator"` | `…api.authorization.CommandClientGenerator` | **Blocked by dispatch-69 rule 5 ("do not touch contracts").** Changing the generator's `GENERATOR` literal makes `CommandClientGenerator --check` and `contracts/x_04/check.py` fail until the two generated artifacts are regenerated. |
| `contracts/x_04/generated/client.ts:1` | `// Generated by com.aurevia.api.x_04.CommandClientGenerator.` | same new FQN | Same regeneration as above. |
| `contracts/x_04/README.md:4,8,10,49,50` | `api/x_04/…` paths and `java -cp … com.aurevia.api.x_04.CommandClientGenerator` | new FQN | Same rule-5 block. |
| `contracts/x_03/document-artifacts.md:6` | `com.aurevia.api.x_03` | `com.aurevia.documents` | Same rule-5 block. |
| `contracts/x_01/README.md:5,84,86,87,89` | `…/api/x_01` path and `x_01` labels | `api/contract` | Same rule-5 block. |
| `contracts/x_02/README.md:4` | `…/api/x_02` path | `com.aurevia.events` | Same rule-5 block. |
| `apps/api/src/test/java/com/aurevia/it/patientchart/ChartEntryIntegrationTest.java:76` | Flyway location `filesystem:src/test/java/com/aurevia/it/s_g1_03/migrations` | `…/it/patientchart/migrations` | Leftover from the earlier refactor: the SQL file now lives at `it/patientchart/migrations/V13__longitudinal_chart.sql` and `db/migrations` has no V13, so the current location resolves to nothing. Correcting it changes which migrations apply — a behavior change that must be verified against PostgreSQL. |
| `apps/api/src/main/java/com/aurevia/radiology/Interpretation.java:89` | comment `the g1.s_g1_05 SHA-256 utility` | `the clinical.notes SHA-256 utility` | Comment only. |
| `apps/api/src/test/java/com/aurevia/it/schedulingcheckin/CheckInWorklistIntegrationTest.java:57,64,88,89,114` | database `aurevia_s_g1_02_it` | `aurevia_checkin_it` | Optional: a database name, not a package/folder/class. Test-only. |

No Spring component scan, `@Import`, `@Bean`, `application*.yml`, `pom.xml` or `scripts/`
reference names these packages: `grep -rn 'com\.aurevia\.aurevia\.api\.x_0' apps/api/src`
returns only files inside the four packages themselves, and the app relies on the default
`@SpringBootApplication` scan of `com.aurevia.**`, so a package move cannot
un-register `@RestController DeliveryController`, `@Service DurableEventService` or
`@Component DeliveryActions`.

## 6. Out of scope for this story

- `apps/patient-mobile/src/features/s_g3_01` … `s_g3_12` (mobile folders, not Java).
- Test mirrors named `it/exchangeorder`, `it/exchangetask`, `it/messagingconversations`,
  `it/patientchart`, `it/schedulingcheckin`, … — domain words, not story IDs. Optional
  follow-up to nest them under a package path that mirrors main.

## 7. Commands run for this inventory

| Command | Result |
|---|---|
| `git status --porcelain` | clean |
| `find apps/api/src -type d` | four story-ID package dirs (`api/x_01`–`api/x_04`), no `s_g*`/`g[0-4]` |
| `grep -rn --include=*.java -E '\b(x_0[0-9]\|s_g[0-9])' apps/api/src` | matches only inside those packages; no cross-package imports |
| simple-name count per target class (`find … -name <Name>.java`) | one collision: `DocumentInputs` |
| `grep -rn 'ComponentScan\|@Import\|spring.factories' apps/api/src/main/java` | no explicit scan configuration |
| `grep -rn 'x_0[0-9]' docs/ scripts/ .github/` | no matches |
| `grep -rn 's_g[0-5]_[0-9]\+'` (repo-wide, excluding `.git`) | leftovers in the two Java files and the `it/schedulingcheckin` database name above, plus historical `.agent` notes and `infra/`/`docs/operations/` directories that are not Java packages |
| `pg_isready` / `psql` / listening-port check | no PostgreSQL on this workstation; integration tests cannot run locally |

## 8. Decisions needed before any move (dispatch-69 rule 2 and rule 5)

1. **Mapping OK?** Accept the four target packages in §2 (or pick the listed alternatives).
2. **Contracts conflict.** Rule 3 says update every reference to the old paths; rule 5 says do
   not touch `contracts/`. Renaming `x_04` and `x_01`–`x_03` leaves five contract files with
   dead FQNs/paths, and the generated artifacts only match the generator if its `GENERATOR`
   literal is updated. Options: **(A)** allow the mechanical update of
   `contracts/x_0*/README.md` + regeneration of `contracts/x_04/generated/{client.ts,manifest.json}`
   (no contract semantics change, `contracts/x_04/check.py` re-verified) — recommended;
   **(B)** leave `contracts/` untouched and keep the old FQN strings, recording the stale
   identity as a BUGS.md entry; **(C)** exclude `x_04` from this refactor.
3. **Stale Flyway location** in `ChartEntryIntegrationTest`: fix the path (changes which
   migrations apply, needs a PostgreSQL run to prove no behavior change) or report it as a
   defect and leave it.
4. **Database name** `aurevia_s_g1_02_it`: rename or leave.
