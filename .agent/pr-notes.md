<!-- Merge of two independent AUD-20261119-02 implementations; both note sets preserved. The resolved code keeps the conversation-owned verifier port and the tenant-directory resolution paths. -->


# AUD-20261119-02 — Verify the patient-unit connection when opening a conversation

Status: complete, ready for integrator merge. Production scope confined to
`apps/api/src/main/java/com/aurevia/smartclinic/messaging/conversations` and
`.../messaging/units`; co-located unit tests and the existing integration test
were edited to deliver failing-first evidence and 100% coverage (same precedent
as `.agent/pr-notes/AUD-20261119-01.md`).

## Requirement IDs

MSG-01 (validate patient, destination and context on the server at connection
time and every protected action; block arbitrary directory contact), MSG-02
(conversation lifecycle), MSG-07 (revocation stops messaging), ARC-07 (one
conversation per verified connection and unit), ARC-02 (tenant scoping), OPS-02
(audit of denials). Classification: safety-critical authorization, so negative
and failure tests were written before production code.

## Defect confirmed red first

`ConversationService.open` validated only `subject == patientSubject` and the DB
`patient_link_active` fold for a patient caller. `connectionId` was format-checked
and persisted verbatim; `unitKey` was free to name any of the five units.
`CareUnitDirectoryService.requireActiveConnection` (the authoritative
patient+unit active-connection check) had zero callers in `main`.

True HTTP-level red against unchanged production code, on local PostgreSQL with
synthetic two-tenant fixtures:

- `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='ConversationFlowTest#patientCannotOpenWithAConnectionNotBoundToThem+patientCannotOpenWithAUnitTheyHaveNoConnectionTo' -DfailIfNoSpecifiedTests=false test`
  → **exit 1, 2 failures**: both returned `201 CREATED` with a persisted
  conversation (`connectionId: "forged-conn"` and `unitKey: "pharmacy"`) where
  `403 FORBIDDEN` was expected. Evidence source: executed tool command.

## Acceptance mapping

| ID | Acceptance | Evidence (tool unless stated) |
|---|---|---|
| A1 | A patient cannot open with a connection id that is not bound to them → 403, no row | `ConversationFlowTest.patientCannotOpenWithAConnectionNotBoundToThem` (red→green); `ConversationServiceTest.patientOpenIsDeniedWhenNoActiveConnectionBindsThePatientToTheUnit` |
| A2 | A patient cannot open a unit they have no connection to, even with a valid connection for another unit → 403 | `ConversationFlowTest.patientCannotOpenWithAUnitTheyHaveNoConnectionTo` (red→green); `CareUnitDirectoryServiceTest.requireActiveDeniesAConnectionUsedForTheWrongUnitKind` |
| A3 | The exact named connection must be ACTIVE, owned by the verified chart and mapped to the expected unit; unknown, other-chart, revoked, missing-unit and inactive-unit connections all deny uniformly (403 `FORBIDDEN`, one message) | `CareUnitDirectoryServiceTest.requireActiveDeniesAnUnknownConnection`, `...AConnectionOwnedByAnotherChart`, `...ARevokedConnection`, `...ConnectionsWhoseUnitIsMissingOrInactive`, `...AConnectionUsedForTheWrongUnitKind` |
| A4 | The conversation persists server-derived patient/unit/connection identities, not the client's named values | `ConversationServiceTest.patientOpenPersistsTheServerDerivedConnectionIdentities` (verifier returns `server-conn`; insertConversation asserted with it) |
| A5 | A revoked connection stops the next patient reply, including reopen of a resolved thread → 403, no message/state write | `ConversationServiceTest.patientReplyIsDeniedWhenTheConnectionWasRevoked`; `CareUnitDirectoryService.requireActive` called from `ConversationService.reply` |
| A6 | Staff open/reply are unchanged: unit role eligibility still applies and the patient connection verifier is never consulted | `ConversationServiceTest.staffOpenNeverConsultsThePatientConnectionVerifier` (`verifyNoInteractions`); existing staff open/reply/assign tests pass; `CONNECTION_USE` remains patient-only |
| A7 | Concealment order preserved: a foreign subject is denied before any existence or connection lookup | `ConversationServiceTest.openConcealsAnotherSubjectWithoutProbingPatientExistence` extended with `verifyNoInteractions(connections)` |
| A8 | Connection verification reuses the existing authoritative rule instead of duplicating it; denials are audited | `CareUnitDirectoryService.requireActive` delegates to existing `requireActiveConnection`; `requireActiveDeniesAnUnknownConnection` asserts `CONNECTION_USE/DENIED` in the messaging audit |
| A9 | Bounded inputs and fail-closed context: invalid unit key → 422, missing context → 401 | `CareUnitDirectoryServiceTest.requireActiveRejectsAnUnknownUnitKeyBeforeAnyLookup`, `requireActiveFailsClosedWithoutTenantContext`; `MessagingDomainTest.careUnitKindIsBoundedToTheApprovedUnitKeys` |
| A10 | Tenant isolation: identical local patient/connection ids across two orgs stay bound to their own org | `ConversationFlowTest` seeds both orgs with the same connection ids; existing `preconditionsValidationAndTenantIsolation`, `directFoldAssertionsConfirmVerifiedLinkAndForcedRls` unchanged; port reads all identities from `context.organizationId()` |

## Implementation and entry-point-to-test trace

Production edits (all inside the two declared scope directories):

- `messaging/conversations/PatientConnectionVerifier.java` (new) — consumer-owned
  port and `VerifiedConnection(patientId, connectionId, unitKey)` record. Defined
  in conversations so the dependency is one-way `units → conversations` and no
  package cycle is introduced.
- `messaging/conversations/ConversationService.java` — 5th constructor
  dependency; `open()` resolves the connection for patient callers after the
  existing subject/link checks and persists the returned identities; `reply()`
  re-resolves it from the stored `patientId/unitKey/connectionId` for patient
  callers before any write.
- `messaging/conversations/ConversationContract.java` — Javadoc only: the open
  request's `unitKey` and `connectionId` are now documented as server-checked.
- `messaging/units/MessagingContract.java` — `CareUnit` gains a bounded `kind`
  discriminator validated with the authoritative `ConversationContract.UnitKey.from`,
  so the five keys are declared once.
- `messaging/units/CareUnitDirectoryService.java` — implements the port;
  `requireActive` looks up the exact connection by id, requires ACTIVE +
  chart ownership, resolves its unit and requires active + matching `kind`,
  then delegates to the pre-existing `requireActiveConnection` for membership,
  verified-chart and active-binding checks. Every failure uses one uniform
  403 `FORBIDDEN` message so link/connection state cannot be probed.

Trace:
`POST /api/v1/conversations` → `ConversationController.open` →
`ConversationService.open` (guarded `TransactionTemplate`, membership/role from
DB folds) → `PatientConnectionVerifier.requireActive` →
`CareUnitDirectoryService.requireActive` (`MessagingStore.findConnection` /
`findUnit` / verified charts, all tenant-keyed, `CONNECTION_USE` authorization +
DENIED audit) → existing `requireActiveConnection` → `ConversationRepository.insertConversation`
(forced RLS) → append-only event + DB audit → 201.
`POST /api/v1/conversations/{id}/reply` → `ConversationService.reply` →
`requireAccess` → `patientLinkActive` → the same port call for patients →
message append + state update. Unit trace: `ConversationServiceTest`
(Mockito port, fixed inputs) and `CareUnitDirectoryServiceTest` (synthetic
in-memory store). HTTP trace: `ConversationFlowTest` on real PostgreSQL with
two synthetic tenants.

## Exact commands and observed results

Evidence source: executed tool commands unless stated otherwise. All from the
current checkout on branch `swarm/AUD-20261119-02-220`, HEAD `9bd064d`.

1. Baseline fast tier before changes — `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0; 1825 tests, 0 failures/errors.
2. Red (above) — 2 HTTP integration tests, 2 failures (`201 CREATED` instead of `403`).
3. Green fast tier after implementation — clean `surefire-reports`, then `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **exit 0; 1838 tests, 0 failures, 0 errors, 0 skipped** (13 net new tests).
4. Targeted integration green — same two new tests with `SMARTCLINIC_PG_ADMIN=postgres` and no `fast` profile → **exit 0; 2 tests, 0 failures/errors**.
5. Full `ConversationFlowTest` — 10 tests, 2 failures / 3 errors; every failure is a pre-existing DLV-02 defect unrelated to this story (`HttpStatus.UNPROCESSABLE_ENTITY` vs `UNPROCESSABLE_CONTENT` at two assertions, a `JsonNode.get(...)` null on a missing response field, and `uuid = character varying` in a direct SQL assertion). The same 5 failures were reproduced on the unchanged baseline before any edit; my two new tests pass inside this class and no new failure was introduced.
6. JaCoCo from the fast-tier exec (`mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report`, XML inspected): `ConversationService` 1004/1004 instructions, 62/62 branches, 172/172 lines, 39/39 methods; `CareUnitDirectoryService` 813/813, 64/64, 135/135, 28/28; `PatientConnectionVerifier` 12/12, 1/1 line, 1/1 method; `MessagingContract` 288/288, 8/8 branches, 39/39 lines, 15/15 methods; `ConversationContract` 496/496, 20/20 branches, 74/74 lines, 30/30 methods. All 100%.
7. Static analysis — `mvn -B -f apps/api/pom.xml compile spotbugs:check` → exit 0, `BugInstance size is 0`. OWASP was not re-run: dependencies and `pom.xml` are unchanged, so the CVE disposition is unchanged.
8. Policy — `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` → each exit 0. No migration was added or changed (no reserved version in this story).
9. Java conventions — `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK.

## Decisions and rationale

- Kept the port in `conversations` and the implementation in `units` (one-way
  dependency), following the bounded Senior Backend review (simulated reviewer;
  code verified by this agent). Returning a conversations-owned record avoids a
  `conversations ↔ units` package cycle.
- Verify the **exact connection id**, not merely "some active connection of the
  right kind", following the Senior Spring Security review's HIGH finding:
  connections are keyed per (patient, unit, reference), so a same-kind second
  connection must not keep a revoked thread alive. The service persists the
  port's returned identities.
- Applied the check **only to patient callers**. `CONNECTION_USE` is
  patient-only in `MessagingActions`; a staff path would 403. MSG-02 still
  permits staff to open, and staff eligibility stays in `ConversationActions`.
  Both specialists flagged a staff-reply-after-revocation gap; it is recorded
  as residual below rather than broadening `CONNECTION_USE` outside this story.
- Collapsed every connection-verification failure into one 403 `FORBIDDEN` with
  one message, and checked it after the existing subject/link checks, so the new
  check adds no new distinguishability for a foreign subject.
- Reused `requireActiveConnection` rather than duplicating the active-binding
  rule; `kind` is validated through `UnitKey.from` so the five keys are not
  re-declared.
- No migration or schema change: the MSG-01 connection directory is the
  repository's existing synthetic in-memory adapter. This story makes the
  check real but does not claim production-grade durability (see residual).

## Defects and environment notes

- The reported authorization defect is confirmed fixed; regression tests added.
- `ConversationFlowTest` is already red on this branch for pre-existing DLV-02
  reasons; no attempt was made to weaken, skip, delete or re-tag those tests.
- `bash scripts/docs.sh` → exit 1, "Install Doxygen to build the developer
  reference." Doxygen is absent from this worker (`command -v doxygen` →
  MISSING), an environment limitation, not a code failure. New public Javadoc
  was written with complete `@param`/`@return`/`@throws`; Java conventions
  guard and SpotBugs are green. The integrator's image installs Doxygen.
- `python3 -I scripts/gen-opencode.py --check` and `scripts/check-pack.py` exit 1
  here: `git ls-files -v opencode.json` → `S` (skip-worktree), worktree hash
  `279aa1e7…` equals `.github/opencode.ci.json`, `git show HEAD:opencode.json`
  is `14bba0da…` and `git diff opencode.json` is empty. This is the documented
  OPS-CW-003-01 CI-profile measurement artifact, not a stale committed file;
  no config was regenerated or committed.

## Scope and evidence disclosure

Production edits are confined to the two declared scope directories. Test
edits are limited to the conversation and units test packages. No tracker,
`.github/`, `scripts/`, migration, `contracts/` or OpenAPI file was changed.

Evidence provenance: executed tool commands (Maven, JaCoCo XML, SpotBugs, git,
policy scripts) for all results; two bounded simulated specialist reviewers for
the design critique (Spring Security and Backend), with their code suggestions
verified by this agent; no simulated-user or human/clinical participant
evidence. All fixtures are synthetic; no patient data or credentials were used.

## Residual / not verified

- **Authoritative connection store**: the MSG-01 directory remains the
  repository's in-memory `InMemoryMessagingStore`; it is still not the durable,
  RLS-protected authoritative store (its own Javadoc says so). This fix makes
  the check real for synthetic flows but a DB-backed connection store with a
  reserved migration is required before real-patient enablement. Marking this
  story synthetic-only is honest; it does not by itself satisfy G3/G5.
- **Staff reply after revocation**: `reply()` verifies the connection only for
  patient callers, so a staff reply on a thread whose patient connection was
  revoked is still permitted (pre-existing behaviour, not worsened). Closing
  this needs a staff-permitted verification path and a product/clinical
  decision on MSG-07 semantics; out of this story's scope.
- **Durable audit of a connection denial**: the port's DENIED fact is appended
  to the in-memory messaging audit. A denial thrown inside the guarded
  transaction rolls back, so no `AuditAppender` DB row is committed for that
  specific denial — this is the pre-existing pattern for all in-work
  `notFound()/forbidden()` throws in `ConversationService`, not introduced here.
  Restructuring `execute`'s `Sink` to cover it is a follow-up.
- **Cross-store TOCTOU**: the in-memory check and the DB write are not atomic;
  a revocation racing the commit is accepted for synthetic operation.
- `get()/inbox()/markRead()` do not re-check connection state; the patient
  linkage-dispute scenario remains a separate follow-on.
- Doxygen/TypeDoc reference and the full OWASP scan were not run locally
  (environment); the integrator runs the full `scripts/check.sh` gate.

---

# AUD-20261119-04 — Rename story-ID-named patient-mobile feature packages

Status: complete, ready for integrator merge. Scope: `apps/patient-mobile/src/features` only.

## Independent resume verification (current branch)

A later worker re-verified the already-committed state on branch
`swarm/AUD-20261119-04-220`, HEAD `9bd064d` (which contains the refactor commit
`55c0d52`, PR #169), with a clean worktree. The refactor was found complete;
no new source edit was needed. Environment on this pass: Node v22.23.5,
npm 10.9.9 (the earlier pass used a different worker image). Commands re-run:

| Command | Result (tool evidence) |
|---|---|
| `find apps/patient-mobile/src/features -maxdepth 1 -type d` | 12 domain folders exactly as mapped, no `s_g*` dir |
| `grep -rn --exclude-dir=node_modules --exclude-dir=.git 's_g3_' apps/patient-mobile` | only the guard test's mapping comments/regex; no stale import or config path |
| `npm ci --no-audit --no-fund` | exit 0, 1272 packages from the pinned lockfile |
| `npm run typecheck` | exit 0 |
| `npm run lint` | exit 0 |
| `npx jest --ci --coverage=false src/features/domain-layout.test.ts` | exit 0, 1 suite / 3 tests passed |
| `bash check.sh` (canonical module gate) | exit 0, 27 suites / 283 tests passed, `All files` 100% statements/branches/functions/lines |

No decision or evidence is missing; no defect found. The integrator still runs
the database and integration tests, which are unaffected by this frontend-only
structural refactor.

## Decision and evidence availability

No decision or evidence is missing. The owner-approved domain mapping is supplied
verbatim in the story text ("appointments, reports, enrollment, units,
conversations, attachments, instructions, acknowledgments, service-profiles,
governance, flows, evidence") and is consistent with the already-merged Java
refactor recorded in `.agent/pr-notes/REFACTOR-PACKAGES-20261119-201815.md`
(`messaging/s_g3_01 → messaging/appointments`, …) and with the `features/conversations`
path already named in TODO.md S-G3-05. No owner question was needed.

## Requirement IDs and acceptance mapping

| Requirement / rule | Acceptance | Where satisfied |
|---|---|---|
| CODING_STYLE.md "Name Java packages by business domain … never by story, task, gate or ticket ID" (extended to all packages by `REFACTOR-PACKAGES.md` §6) | All 12 feature folders use domain names; no `s_g3_XX` directory remains | 12 `git mv` operations; `domain-layout.test.ts` invariants 1 and 2 |
| AUD-20261119-04 · domain mapping | Exact owner-approved mapping applied in story order | `domain-layout.test.ts` `DOMAIN_FOLDERS`; invariant 1 |
| AUD-20261119-04 · update imports and tests | Every import resolves at the new relative path; all existing tests pass | Module imports were already relative (`./…`), so no content edits were needed; 283/283 tests pass, 27/27 suites |
| AUD-20261119-04 · synthetic fixtures only; failing tests first | No real data introduced; red test observed before rename | `domain-layout.test.ts` red before `git mv`, green after; all fixtures are pre-existing synthetic test data |
| Frontend standard: 100% coverage, `tsc --noEmit`, `eslint . --max-warnings=0` | Enforced module gate stays green | `test:coverage -- --ci` exit 0, `All files 100%`; `typecheck` and `lint` exit 0 |

The change is a pure structural refactor: no behavior, contract, migration or
authorization logic changed. No migration version was used or touched.

## Applied mapping

| Old | New |
|---|---|
| `s_g3_01` | `appointments` |
| `s_g3_02` | `reports` |
| `s_g3_03` | `enrollment` |
| `s_g3_04` | `units` |
| `s_g3_05` | `conversations` |
| `s_g3_06` | `attachments` |
| `s_g3_07` | `instructions` |
| `s_g3_08` | `acknowledgments` |
| `s_g3_09` | `service-profiles` |
| `s_g3_10` | `governance` |
| `s_g3_11` | `flows` |
| `s_g3_12` | `evidence` |

## Exact commands run and results (tool evidence)

All commands run from `apps/patient-mobile` unless noted. Initial state: `main`
at `34a1bd8`, clean worktree; `node_modules` absent.

| Command | Result |
|---|---|
| `npm ci --no-audit --no-fund` | exit 0; 1272 packages installed from the pinned lockfile |
| `npm run typecheck` (baseline) | exit 0 |
| `npm run lint` (baseline) | exit 0 |
| `npx jest --ci --coverage=false` (baseline) | 26 suites / 280 tests passed |
| `npx jest --ci --coverage=false src/features/domain-layout.test.ts` **before rename** | exit 1 — 1 suite / 3 failed tests; diff showed the 12 `s_g3_XX` folders; `ENOENT … features/appointments`. Confirms red. |
| `git mv` × 12 (listed above) | exit 0; Git detected 71 renames, 0 content rewrites |
| `npx jest --ci --coverage=false src/features/domain-layout.test.ts` **after rename** | exit 0 — 1 suite / 3 passed tests. Confirms green. |
| `npm run typecheck` | exit 0 |
| `npm run lint` | exit 0 |
| `npm run test:coverage -- --ci` | exit 0 — 27 suites / 283 tests passed; `All files` 100% statements/branches/functions/lines; every production file at 100% (interface-only `types.ts` files report 0/0). |
| `bash check.sh` (canonical module gate: shellcheck, `npm ci`, typecheck, lint, coverage) | exit 0 — 27 suites / 283 tests passed, 100% all metrics. |
| `python3 -I scripts/check-docs.py` / `python3 -I scripts/check-secrets.py` | both exit 0 — the new Markdown notes introduce no broken link, bad requirement ID or secret/PHI-shaped string. |
| `python3 -I -m unittest discover -s scripts/tests -q` | exit 1 — **228 tests, 8 failures / 10 errors, all unrelated to this story** (see "Environmental issue" below). The patient-mobile module gate and the repo's doc/secret/migration checks all pass. |
| `grep -rn --exclude-dir=node_modules 's_g3_' apps/patient-mobile` | only the new guard test's doc comment, inline mapping comments and regex; no source import or config references the old paths |
| `git status --short \| grep -v 'apps/patient-mobile/src/features/'` | none — all 72 changed files are inside the declared scope |

Baseline was 26 suites / 280 tests; the final 27 / 283 is the same suite plus the
3 new guard-test cases. No test was weakened, skipped, deleted or re-tagged.

## Entry-point-to-test trace

`domain-layout.test.ts` (test entry point) → `fs.readdirSync(__dirname)` reads the
`features` directory → invariant 1 compares sorted directory names to
`DOMAIN_FOLDERS`, invariant 2 rejects `/^s_g\d+_\d+$/`, invariant 3 asserts each
domain folder is non-empty. The renamed feature entry points (`appointments/AppointmentListScreen.tsx`,
`reports/reportClient.ts`, `conversations/ConversationInbox.tsx`, …) are each
exercised by their existing co-located tests, which all pass at the new paths.

## Defects found

None in the source. One pre-existing observation, out of scope and not changed:
historical notes (`.agent/pr-notes/*`, `docs/swarm/*`, `TODO.md`) still contain the
old folder names as plain text/backticks; they are tracker/history records, not
code references, and the task scope plus AGENTS.md forbid editing them. The
specialist notes that the integrator may refresh those tracker paths at merge
time so future agents do not chase non-existent directories; this worker did not.

## Environmental issue (pre-existing, out of scope, not fixed)

The repo-wide tooling unittest suite exits 1 in this worker. It is unrelated to
the rename and was not introduced or worsened by it:

- `ModuleNotFoundError: No module named 'yaml'` breaks import of
  `scripts/tests/test_fast_tier.py` and `test_workflow_concurrency.py` (Python 3.11
  here, PyYAML not installed in this worker image).
- `test_check_pack.*` failures: `git ls-files -v opencode.json` reports flag `S`
  (skip-worktree) and the worktree file differs from `git show HEAD:opencode.json`;
  it is the CI profile copied by `agent-run.sh`, the known OPS-CW-003-01
  measurement artifact documented in MEMORY.md, not a HEAD-tree defect.
- `test_agent_run.*` / `test_swarm.*` failures reference missing temp git refs
  (`refs/heads/opencode/issue-5-9`) and workflow parsing — worker-environment and
  `yaml` issues.

Verification that these are not caused by this change: `git status --short`
shows no changed `.py`, `.sh`, `.json`, `.yml` or `.yaml` file, and all 72 staged
files are under `apps/patient-mobile/src/features` plus this note. None of the
failing tests import or inspect the patient-mobile source tree. No attempt was
made to fix these (out of scope; shared tooling owned by other workers). The
integrator runs the full gate and can confirm against a clean image.

## Evidence provenance

- **Tool evidence**: every command and result in the table above, executed on the
  current checkout (npm, tsc, eslint, Jest via `jest-expo`, `git mv`, `git status`, `grep`,
  `python3 -I` policy checks, `bash check.sh`).
- **Reviewer evidence**: `smartclinic-senior-mobile` read-only review — verdict
  **Approve**, no impact defects. Structural file/pattern inspection only; the
  reviewer had no shell tool and could not re-execute the commands, so test,
  coverage, typecheck and lint results remain tool evidence from this agent.
- **Simulated user / human participant evidence**: none — this is a non-clinical,
  non-user-facing structural refactor. No real patient data or credentials were
  used; all fixtures are the repository's pre-existing synthetic data.

## Subagent (specialist) review

Reviewer: `smartclinic-senior-mobile` (read-only, bounded to story ID, scope,
mapping, acceptance criteria and the tool evidence above).

- Broken imports / path references: none. All 71 renamed files use local `./…`
  imports; no `../`, cross-directory, dynamic `require()` or config reference to
  an old path exists.
- Remaining story-ID artifacts in scope: none. Directory names exactly match the
  12-domain mapping; `s_g3_XX` survives only as inline mapping comments in the
  guard test.
- Guard-test soundness: sound — exact-set equality catches any added, removed or
  differently named folder; the regex check is redundant, not a weakness.
- Coverage / TS strictness: no new risk; the guard test is correctly excluded
  from coverage and strict flags are unchanged.

Verdict: **Approve**. No decision or evidence is missing for this story.

(End of notes.)


# AUD-20261119-02 — Verify the patient-unit connection when opening a conversation

Story source: daily audit. Worker: swarm slot for `AUD-20261119-02`. Branch `swarm/AUD-20261119-02-216`, base `34a1bd8`. Synthetic fixtures only. No shared tracker edited.

## Requirement IDs

Primary **MSG-01**, **MSG-02**, **MSG-07**, **ARC-07**. Supporting: IAM-05, ARC-02, ARC-04, OPS-02. Specification: `SMARTCLINIC_V1_SPEC.md` §§2, 6.1, 7, 11.

## Defect reproduced from the story (root cause)

`ConversationService.open` accepted `connectionId` from the JSON body, shape-validated it with `ConversationInputs.opaqueId`, and persisted it verbatim. For a patient caller the only relationship check was `ConversationRepository.patientLinkActive` (the IAM-05 patient↔chart fold); no check proved that an active `PatientUnitConnection` bound that chart to the named `unitKey`. `CareUnitDirectoryService.requireActiveConnection` already implemented such a check but had no caller in `main`. A verified patient could open a thread with any of the five units by naming the unit key, and a forged/revoked/foreign connection id was stored unchallenged. `reply()` likewise never re-checked connection state, so a revoked connection still permitted new messages.

## Fix (smallest reviewable slice)

1. `CareUnit` now declares an optional tenant-scoped `unitKey` (with a backward-compatible 6-arg constructor). The conversation unit is derived from the connection's recipient care unit **through the tenant directory**, never from a global id map or the client.
2. `CareUnitDirectoryService` gains `requireActiveConnectionById` (patient self-service: reuses `requireActiveConnection` for membership/role/verified-chart/active-connection) and `requireUsableConnection` (staff action: same ownership, state and unit checks without the patient-only role gate). Both resolve inside `TenantContext.organizationId()`, conceal a missing or foreign connection as 404, deny a revoked connection as 403, and require an active care unit with a conversation category. The reused `deny()` path records a non-sensitive `CONNECTION_USE` audit fact.
3. `ConversationService.open` resolves the connection **after** subject/patient/link checks and staff unit eligibility, derives the unit from the tenant directory, rejects a client unit that differs from the bound unit (403), and persists the server-derived unit and authoritative connection id. `reply` re-resolves the stored connection on every message from either side, so revocation or a stale route stops new messaging.
4. New `ConversationUnitResolver.fromDirectory` converts the tenant directory value into the bounded `UnitKey` vocabulary and rejects anything else (403).

Ordering choices: subject mismatch → 404 (no existence probe) → patient 404 → verified-link 404 → staff unit 403 → connection 404 (concealed) / 403 (state) → derived-unit mismatch 403 → write. Zero writes on every denial.

## Acceptance mapping

| # | Acceptance | Evidence (test) |
|---|---|---|
| A1 | Patient cannot open a unit without an active verified connection | `ConversationServiceTest.patientOpenWithoutAnyVerifiedConnectionIsDenied` → 404, no insert |
| A2 | ConnectionId not bound to caller is rejected | `patientOpenWithAConnectionBoundToAnotherPatientIsDenied` → 404; `CareUnitDirectoryServiceTest.requireActiveConnectionByIdConcealsAnotherPatientsConnection` |
| A3 | Wrong-unit connection cannot open a thread | `patientOpenWithAConnectionToAnotherUnitIsDenied` → 403, no insert |
| A4 | Revoked connection cannot open | `patientOpenWithARevokedConnectionIsDenied` → 403 |
| A5 | Malformed connection id fails before any write | `patientOpenWithAMalformedConnectionIdIsRejectedBeforeAnyWrite` → 422 |
| A6 | Server derives and persists the connection's unit and id | `patientOpenCreatesAwaitingStaffConversationAndWorkItem` (verifies `"lab"` and the connection UUID in `insertConversation`) |
| A7 | Every reply re-verifies the connection (both sides) | `patientReplyAfterTheConnectionWasRevokedIsDenied`, `patientReplyRejectsAConnectionBoundToAnotherUnit`, `CareUnitDirectoryServiceTest.requireUsableConnectionAllowsAStaffActionButDeniesInactiveMembership` |
| A8 | Tenant-scoped resolution: missing/foreign connection and missing unit concealed | `requireActiveConnectionByIdConcealsAnUnknownConnection`, `...ConcealsAMissingRecipientCareUnit`, `...DeniesAUnitWithoutAConversationCategoryOrAnInactiveUnit` |
| A9 | Patient-side membership, verified chart and link still enforced | `requireActiveConnectionByIdDeniesInactiveMembershipAndUnlinkedChart` |
| A10 | Unit key vocabulary stays bounded | `ConversationUnitResolverTest.everyDirectoryUnitKeyIsAccepted`, `anUnknownOrMissingDirectoryUnitKeyIsRejected` |

## Entry-point-to-test trace

`POST /api/v1/conversations` → `ConversationController.open` → `ConversationService.open` (subject/link checks, then `CareUnitDirectoryService.requireActiveConnectionById` / `requireUsableConnection` → `resolveConnection` tenant lookup → `requireActiveConnection` for patients → `ConversationUnitResolver.fromDirectory`) → guarded `TransactionTemplate` → `ConversationRepository.insertConversation` with server-derived unit and connection id → 201. Fast path: `ConversationServiceTest` (real `InMemoryMessagingStore` + `CareUnitDirectoryService`, mocked repository). DB/HTTP path: `ConversationFlowTest` (integrator-owned).

`POST /api/v1/conversations/{id}/reply` → `ConversationService.reply` → existing access/version/state/link checks → connection re-resolution above → sequenced insert → 200. Trace covered by `ConversationServiceTest` reply cases.

## Exact commands and results (tool evidence, executed locally)

Baseline (before edits): `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConversationServiceTest,ConversationContractTest,CareUnitDirectoryServiceTest,MessagingDomainTest,CareUnitControllerTest test` → exit 0.

Red: the same targeted command after adding tests → exit 1, `COMPILATION ERROR`: `ConversationUnitResolver` cannot be found, `ConversationService` constructor requires 5 args (found 4), and `requireActiveConnectionById` does not exist. This is the expected missing-behaviour red for a new API.

Green:
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0. Surefire reports: **1797 tests, 0 failures, 0 errors**.
- Targeted: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConversationServiceTest,ConversationUnitResolverTest,CareUnitDirectoryServiceTest,ConversationContractTest,MessagingDomainTest,CareUnitControllerTest test` → exit 0. Per class: ConversationServiceTest 47, CareUnitDirectoryServiceTest 44, ConversationUnitResolverTest 2, ConversationContractTest 17, MessagingDomainTest 13, CareUnitControllerTest 4.
- Coverage: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` then JaCoCo XML inspection → `ConversationService` and `CareUnitDirectoryService` both **0 missed** instructions/branches/lines/methods/classes. (Whole-bundle misses are from integration-tagged tests excluded by `-Pfast`; they are unchanged by this slice.)
- Static/security: `mvn -q -B -f apps/api/pom.xml -Psecurity -DskipTests spotbugs:check` → exit 0.
- Conventions: `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK.
- Migration gate: `python3 -I scripts/check-migrations.py --require-baseline` → exit 0 (no migration added or changed).
- Hygiene: `git diff --check` → exit 0.
- Full fast policy: `bash scripts/quick-check.sh` → exit 1 **only** at `check-pack.py` ("opencode.json is stale"). Cause is the known worker artifact, not this change: `git ls-files -v opencode.json` → flag `S` (skip-worktree), the worktree file is the 1837-line CI profile while `git show HEAD:opencode.json` is the 1777-line committed local profile (`cmp` → 1), and no agent, skill or `opencode.json` file was edited. Every other quick-check step passed when run directly: `bash -n`/`shellcheck` on the shell scripts, `project-name.py`, `check-docs.py`, `check-secrets.py`, `check-migrations.py --require-baseline`, all Python tooling tests ("ok"), and the Maven fast tier. The committed local profile is what CI/integrator checks; no regeneration was performed.

## Scope note (recorded, not silently changed)

The declared scope names the two `messaging/conversations` and `messaging/units` **main** source paths. To satisfy "failing tests first" and "add a no-connection/wrong-unit negative test", I also changed the corresponding co-located test packages (`it/messagingconversations`, `messaging/units`) and the existing `ConversationFlowTest` seeding helper so the DB/HTTP suite can still open threads with a real connection. No file outside the API messaging domain, generators, workflows, scripts or shared trackers was touched. No migration was added (no reserved version was supplied for this story).

## Decisions

- Connections are patient↔unit relationships; staff have no patient connection. Staff callers are still authorized by the existing role/unit matrix and now additionally require the thread's connection to be active and bound to that unit, so a revoked connection also stops staff messaging (MSG-07) and staff-opened threads remain repliable.
- Wrong-unit is 403 (not 404): the five unit keys are public, and a random connection id still gets a uniform 404, so no existence oracle is introduced.
- The public `CareUnit` contract gains an optional `unitKey`; the 6-arg constructor is preserved so existing directory-only callers and fixtures are unchanged. The maintainer guide contract entry for the care-unit directory is an integrator follow-up (docs are outside this scope).

## Deferred findings (owners; not blocking this synthetic slice)

1. **In-memory connection store is not authoritative (HIGH, owner: durable connection table).** There is no `care_unit` / `patient_unit_connection` table or reserved migration (V32 absent); `MessagingStore` is `InMemoryMessagingStore`. The new checks inherit that synthetic adapter limitation. A forced-RLS durable store and reconciliation with the IAM-05 DB fold must precede real-patient enablement. The DB second line of defence still stores `connection_id` as plain text with no connection FK (owner: same durable-table story).
2. **Durable denial audit (MEDIUM, owner: MSG-07/S-G3-10 hardening).** Mid-operation denials in `ConversationService` (including the new unit mismatch) throw inside the guarded transaction, so the transactional `AuditAppender` row rolls back; `CareUnitDirectoryService.deny()` writes a non-sensitive `CONNECTION_USE` fact to the in-memory store instead. This is the pre-existing systemic pattern recorded in `S-G3-05` deferred findings, not introduced here.
3. **Other patient commands (MEDIUM, owner: MSG-03/04/05 governance).** Attachment send/download and instruction acknowledgement use separate stores and do not re-resolve this connection; a single revocation is not yet proven to deny every patient command.
4. **Two verified-link sources (MEDIUM, owner: S-G0-07/S-G3-10).** `patient_link_active` (DB) and in-memory `verifiedPatientCharts` can diverge; the new path checks both but does not reconcile them.
5. **Developer reference build (environment).** `bash scripts/docs.sh` could not run locally ("Install Doxygen to build the developer reference"); the Javadoc-link convention test passes. Doxygen is integrator/CI evidence.

## Evidence sources

- **Tool (executed by this worker):** every command and result above, plus `git status`/`git diff` inspection and JaCoCo XML counters. No database, integration or full `scripts/check.sh` run locally.
- **Reviewer (simulated, read-only):** `smartclinic-software-architect` and `smartclinic-senior-spring-security` bounded design reviews. They found the global-unit-id-map and staff-delegation problems; both were fixed before the green run (unit now resolved through the tenant directory; staff use a separate safe method; connection ownership, ordering and concealment verified). Reviewers did not execute tests.
- **Simulated user / human participant:** none. No clinical or real-patient evidence; G5 sign-off remains required.

## Integrator-owned gates (not run locally, per instruction)

`bash db/check.sh`, the `@Tag("integration")` `ConversationFlowTest` (now seeds care units, connections and in-memory links/members), Flyway V1–V55, full `mvn verify` JaCoCo/SpotBugs/OWASP, `bash scripts/check.sh`, `bash scripts/integrator-gate.sh`, and `bash scripts/docs.sh` with Doxygen. `-Pfast` excludes the integration group, so **no integration result is claimed in this note**.
