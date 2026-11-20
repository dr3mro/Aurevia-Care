# X-04 — Generated OpenAPI clients and server-side authorization for every command (PRD-06)

This is attempt 3 of X-04. The prior attempt delivered the same behavior under the
story-ID paths `contracts/x_04` and `com.aurevia.smartclinic.api.x_04`. This branch
delivers it at the canonical **business-domain** paths named by the tracker scope:
`contracts/openapi` and `com.aurevia.smartclinic.api.authorization`. The old paths
are not retained, so there is exactly one command catalog and one authorization
gate. No behavior was duplicated.

## Requirement and acceptance mapping

| Requirement | Acceptance implemented | Entry point → test |
|---|---|---|
| PRD-06 | The generated client has no role/permission/scope argument; authority is re-derived server-side from the policy that owns each action. No diagnosis, treatment or release decision is derived here. | `CommandClientGenerator.typeScriptClient` → `CommandClientGeneratorTest.everyCommandAppearsInContractAndClient`; gate Javadoc limits in `CommandAuthorization` |
| IAM-01, IAM-02 | `CommandAuthorization.authorize` re-invokes `MembershipResolver` on every call; deactivation, role change, unavailable membership and unknown owner deny the next request; all malformed/unauthenticated paths record a denial before answering. | `CommandAuthorization.authorize` → `CommandAuthorizationTest.membershipAndRolesAreReResolvedOnEveryCall`, `clientClaimedAuthorityNeverGrantsAPermission`, `unauthenticatedIdentitiesAreDeniedAndRecorded`, `malformedAndUnknownCommandsAreRecordedDenials`, `unavailableMembershipAndUnregisteredOwnerFailClosed` |
| ARC-03, ARC-04 | One catalog of 24 explicit POST commands drives contract + client; every generated function requires `Idempotency-Key` and `If-Match`; 401/403/422/428 contract responses; compare-and-set stays with the owning service. | `CommandCatalog.synthetic`, `CommandClientGenerator` → `CommandAuthorizationTest.catalogIsImmutableUniqueAndUnknownCommandsFailClosed`, `CommandClientGeneratorTest.manifestCarriesDeterministicChecksumsAndEveryCommand`, `pathParametersBecomeEncodedFunctionArguments` |
| OPS-02 | Every outcome (allow and deny, including a failed sink) is handled with tenant, subject, correlation, action, stable reason, fresh roles and an injected-clock instant; a durable sink belongs to the owning service transaction. | `CommandAuthorization.record`, `InMemoryDecisionSink` → `allowedDecisionCarriesOps02FieldsAndSkipsDisallowedRoles`, `sinkFailureFailsClosedWithAMachineReadableError`, `decisionLedgerCopiesClearsAndRejectsNull` |

Reserved migration **V57 is intentionally unused**: `db/migrations` is outside the
declared scope and the migration gate permits gaps. No route, controller, table or
migration was added.

## Files (all inside the declared scope)

Moved (with edits) from `contracts/x_04` / `api/x_04`:

- `contracts/openapi/README.md`, `contracts/openapi/check.py`
- `contracts/openapi/generated/{openapi.yaml,client.ts,manifest.json}`
- `apps/api/src/main/java/com/aurevia/smartclinic/api/authorization/{CommandDefinition,CommandCatalog,CommandAuthorization,InMemoryDecisionSink,CommandClientGenerator}.java`
- `apps/api/src/test/java/com/aurevia/smartclinic/api/authorization/{CommandAuthorizationTest,CommandClientGeneratorTest}.java`

The only remaining literal `x_04` strings in scope are traceability prose (README
note, OpenAPI info title, digest-domain separators in generator/check.py); no Java
package or generated FQN uses a story ID.

## Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -B -f apps/api/pom.xml -Pno-security,fast test` (tests moved first, production still old package) | **RED**, exit 1: `COMPILATION ERROR`, many `cannot find symbol … com.aurevia.smartclinic.api.authorization.CommandAuthorization/CommandCatalog/...` |
| Same command after moving production classes and updating FQNs | **GREEN**, exit 0, `Tests run: 1685, Failures: 0, Errors: 0, Skipped: 0` (12 + 7 tests in the relocated package) |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast test -Dtest=CommandAuthorizationTest` after adding the three hardening tests | **RED**, exit 1: `Tests run: 14, Failures: 2, Errors: 1` — malformed inert claim gave `NullPointerException`, inactive-membership decision had `rolesUsed=[]`, sink failure gave `IllegalStateException` |
| Same command after the three fixes | **GREEN**, exit 0, `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` |
| Final `mvn -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0, `Tests run: 1687, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + parse `apps/api/target/site/jacoco/jacoco.xml`, package `com/aurevia/smartclinic/api/authorization` | **100%**: INSTRUCTION 0/1545, BRANCH 0/96, LINE 0/251, METHOD 0/38, CLASS 0/9 missed/covered. Every class and nested record/interface is covered. |
| `mvn -B -f apps/api/pom.xml -Pfast -DskipTests -Djacoco.skip=true -Dspotbugs.skip=false -Dowasp.skip=true compile spotbugs:check` | Exit 1 for **5 pre-existing** findings outside scope (messaging.acknowledgment, messaging.conversations, patient.identity, prescription.rx ×2); **zero findings in `api.authorization`**. Earlier no-compile invocations analysed stale classes; the fresh-compile run is authoritative. |
| `java -cp apps/api/target/classes com.aurevia.smartclinic.api.authorization.CommandClientGenerator contracts/openapi/generated` then `--check` | Regenerated `client.ts` header and `manifest.json` generator FQN/clientSha256; `--check` exit 0 |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `npm ci --ignore-scripts --no-audit --no-fund`; `node_modules/.bin/prettier --check contracts/openapi/generated/client.ts`; `npm run format:check` | Prettier check and whole-repo format check both pass |
| `python3 -I scripts/check-docs.py`; `python3 -I scripts/check-secrets.py` | Both exit 0 (moved README links retargeted and valid) |
| `python3 -I scripts/gen-opencode.py --check` | Exit 1 — **pre-existing worker measurement artifact**, not caused by this story: `render()` equals `git show HEAD:opencode.json` and differs only from the worktree CI profile (`git ls-files -v opencode.json` → `S`, skip-worktree). No agent, skill, `opencode.json` or CI config file was touched. |

Database and Spring integration tests were **not** run locally: no PostgreSQL is
reachable from this worker. Per the worker directive the integrator runs
`scripts/check.sh` / `bash db/check.sh` on the merge result. The full JaCoCo bundle
gate is intentionally not claimed here; the per-class figures above are the
authoritative new-code evidence.

## Defects found and fixed in this story

1. **Prior attempt delivered at story-ID paths.** Relocated all production, test and contract files to the business-domain scope; no duplicate implementation remains.
2. **Stale generated artifacts after the FQN change.** `client.ts:1` and `manifest.json` still named the old generator FQN. Regenerated from the relocated generator; `--check` and `check.py` now pass. `check.py` alone cannot detect this (it validates checksums/parity/forbidden tokens, not the generator field), so the `--check` gate is the authority.
3. **README links to dead `api/x_04` paths.** Retargeted all three to `api/authorization`; `check-docs.py` is green. Added a note distinguishing the catalog-generated `contracts/openapi/generated/openapi.yaml` from the X-01 staff-API `contracts/openapi.yaml`.
4. **Security review H-1 (fail-closed gap):** a null element in the inert `claimedPermissions` list threw from `List.copyOf` inside the record constructor, before `authorize()` could record a decision (unstructured 500). Fixed by filtering nulls into a local list and applying `List.copyOf`; `malformedInertClaimsCannotBypassDecisionRecording` covers it.
5. **Security review H-2 (incomplete OPS-02 evidence):** `MEMBERSHIP_INACTIVE` recorded an empty role snapshot although fresh roles were available. Now records `member.activeRoles()`; asserted in `membershipAndRolesAreReResolvedOnEveryCall`.
6. **Security review H-3 (undefined audit-failure semantics):** a throwing decision sink escaped as a raw runtime exception. `record()` now fails closed with `500 DECISION_NOT_RECORDED`; `sinkFailureFailsClosedWithAMachineReadableError` covers it.
7. **SpotBugs iterations on H-1:** an initial discard-the-parameter fix triggered `IP_PARAMETER_IS_DEAD_BUT_OVERWRITTEN`; a stream/`toList()` variant triggered `EI_EXPOSE_REP` (and the first two runs analysed stale bytecode). Final local-list + `List.copyOf` form is clean on a fresh compile.

## Decisions and explicitly deferred work

- **Scope is authoritative.** My dispatch names `contracts/openapi` and `api/authorization`; that is what is delivered. The tracker's `REFACTOR-PACKAGES-02` entry still describes the earlier proposed mapping as pending owner approval — the integrator/owner owns reconciling that entry. I did not edit TODO.md, MEMORY.md, WORKSPACE.md, BUGS.md, PROJECT.md or docs/swarm.
- **Not a global enforcement point (cross-boundary).** The gate is a reusable choke point, but no existing controller calls it and the 80+ POST mappings are not proven to match the catalog. Wiring it into owning services and a build-failing POST-mapping ↔ catalog parity test need a separate story with edits outside this scope. The README states this plainly.
- **Standalone drift commands are not wired into shell scripts (reviewer F5, partially closed).** `CommandClientGenerator --check` and `contracts/openapi/check.py` are still not invoked by `scripts/check.sh`, `apps/api/check.sh` or the Delivery policy job (editing `scripts/`/`.github/` is out of scope). The continuation below adds `CommandClientGeneratorTest.committedArtifactsMatchAFreshGeneration`, which runs the same drift check inside `mvn test`, so a stale catalog/client/manifest now fails the build-failing Java tier and CI. Wiring the two standalone commands into the shell gate remains an optional follow-on.
- **PRD-06 clinical decisions remain human acts.** The gate does not bind a revision checksum or replace attestation/sign-off; `ReleaseActionPolicy` naming `tenant_admin` among release deciders remains unchanged and needs clinical-owner approval before live enablement.
- **Durable audit (deferred).** `InMemoryDecisionSink` is a synthetic reference; OPS-02 durability belongs to an `AuditAppender`-backed adapter in the owning service's transaction.
- **Client body typing (deferred).** Bodies remain `unknown` and are validated server-side; `If-Match` and `Idempotency-Key` are already required arguments.

## Evidence provenance

Tool evidence: every command and result above was executed locally by this worker
on this branch. Reviewer evidence: two bounded simulated specialists
(`smartclinic-senior-backend`, `smartclinic-senior-spring-security`) returned
findings F1–F5 and H-1–H-3; actionable findings were reproduced red, fixed and
retested. No human participant, clinician or patient evidence is claimed; all
fixtures are synthetic and contain no patient data or credentials.

---

## Continuation — hardening pass (same branch, before commit)

Three further bounded specialist reviews were run on the committed attempt-3
code: `smartclinic-senior-spring-security`, `smartclinic-senior-backend` and
`smartclinic-senior-typescript`. All were **simulated reviewers** with source
access only; they executed no tools. Every actionable finding was reproduced
red, fixed and retested by this worker. No human or clinical evidence is claimed.

### Additional requirement coverage

| Requirement | Acceptance added | Entry point → test |
|---|---|---|
| PRD-06, IAM-01, OPS-02 (SEC-1) | A throwing `MembershipResolver` or `ActionRule` now records a `MEMBERSHIP_UNAVAILABLE` / `POLICY_UNAVAILABLE` decision and returns a machine-readable 403 instead of leaking a raw runtime exception with no audit row. A failed sink still yields 500 `DECISION_NOT_RECORDED` without double recording. | `CommandAuthorization.authorize` → `CommandAuthorizationTest.collaboratorFailuresAreRecordedAndReturnedAsMachineReadableErrors` |
| OPS-02 (BE-2) | An untrusted `commandId` on the `UNKNOWN_COMMAND` path is trimmed, truncated to 128 chars and stripped of control characters before it enters the append-only ledger; the 422 outcome is unchanged. | `CommandAuthorization.auditableCommandId` → `CommandAuthorizationTest.untrustedCommandIdentifiersAreBoundedBeforeTheyAreAudited` |
| PRD-06, ARC-03 (BE-1) | `CommandDefinition.id` must be a legal TypeScript identifier; path parameters must be legal non-reserved, non-repeated identifiers with balanced braces; `CommandCatalog` rejects duplicate routes. The synthetic catalog is unchanged. | `CommandDefinition`, `CommandCatalog` → `CommandAuthorizationTest.definitionsRejectTokensThatCannotBeGeneratedSafely`, `catalogRejectsDuplicateRoutes` |
| ARC-03, ARC-04 (SEC-2) | The generated contract documents the 500 `DecisionNotRecorded` fail-closed outcome alongside 201/401/403/422/428. | `CommandClientGenerator.openApiYaml` → `CommandClientGeneratorTest.contractDocumentsTheFailClosedAuditOutcome` |
| PRD-06, ARC-03 (TS-1/TS-3) | The offline gate rejects `actor`, `userRole`, `Role :`, `grantedScopes`, `claimedPermissions` and `X-Actor` case-insensitively; verifies path encoding and the four required arguments **per generated function**; exposes a testable `verify(directory)` API. | `contracts/openapi/check.py` → `contracts/openapi/test_check.py` (7 synthetic cases) |
| PRD-06, ARC-03 (CI drift) | Artifact/catalog drift now fails the Java unit tier, not just a manual command. | `CommandClientGeneratorTest.committedArtifactsMatchAFreshGeneration` |

### Exact commands and results (continuation; tool evidence)

| Command | Result |
|---|---|
| `python3 -I -m unittest discover -s contracts/openapi -p 'test_*.py' -q` before the gate fix | **RED**, 7 failures: authority variants, authority header, per-function encoding and required-argument regressions all passed the old gate |
| Same command after the `check.py` hardening | **GREEN**, 7 tests OK; `python3 -I contracts/openapi/check.py` → `x-04 drift check OK: 24 commands` |
| `mvn -o -B -f apps/api/pom.xml -Pno-security,fast test -Dtest=CommandAuthorizationTest` after adding two tests, before the fix | **RED**, 2 failures: raw `IllegalStateException` escaped the resolver path; 10,001-char commandId was persisted verbatim |
| Same command after the fix | **GREEN**, 18 tests OK |
| `… -Dtest=CommandAuthorizationTest` after adding validation tests, before the fix | **RED**, 2 failures: unsafe tokens and duplicate routes were accepted |
| Same command after the fix | **GREEN**, 18 tests OK |
| `… -Dtest=CommandClientGeneratorTest` after adding the 500 test, before the fix | **RED**, 1 failure: no 500 response in the contract |
| Same command after the fix | **GREEN**, 8 tests OK |
| Tamper `contracts/openapi/generated/manifest.json`, run `-Dtest=CommandClientGeneratorTest#committedArtifactsMatchAFreshGeneration`, then restore | **RED** (`contracts/openapi/generated is stale`) then **GREEN** after restore — the drift gate is build-failing in the Java tier |
| `mvn -o -B -f apps/api/pom.xml -Pno-security,fast test` (final full fast tier) | **GREEN**, `Tests run: 1786, Failures: 0, Errors: 0, Skipped: 0` |
| `mvn -o -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + parse jacoco.xml for `…/api/authorization` | **100%**: INSTRUCTION 0/1706, BRANCH 0/116, LINE 0/291, COMPLEXITY 0/100, METHOD 0/42, CLASS 0/9 missed/covered |
| `mvn -o -B -f apps/api/pom.xml -Pfast -DskipTests -Djacoco.skip=true -Dspotbugs.skip=false -Dowasp.skip=true clean compile spotbugs:check` | **GREEN**, `BugInstance size is 0`, BUILD SUCCESS — zero static findings |
| `CP=… java -cp "$CP" …CommandClientGenerator contracts/openapi/generated` then `--check`; `python3 -I contracts/openapi/check.py` | Regenerated `openapi.yaml` + `manifest.json` (500 response); both gates exit 0 |
| `node_modules/.bin/prettier --check contracts/openapi/generated/client.ts`; `npm run format:check` | Both pass (client.ts unchanged; repo-wide format clean) |
| `python3 -I scripts/check-docs.py`; `python3 -I scripts/check-secrets.py` | Both exit 0 after README/notes updates |
| `python3 -I -m unittest discover -s scripts/tests -q` | 203 tests, 8 failures + 8 errors — **all pre-existing** (`check_pack` stale worktree `opencode.json` under skip-worktree, `agent_run`, `swarm`, `fast_tier`/`workflow_concurrency` loaders); none reference `api/authorization` or `contracts/openapi`. Verified identical failure set before and after this change. |

### Environment notes (not code defects)

- The local Maven cache initially lacked the Surefire JUnit-Platform provider
  and JUnit-Platform 1.12.2/6.0.3 artifacts; Maven Central returned 404 for all
  external URLs in this sandbox. I populated the missing JARs/POMs into
  `/opt/m2` from the reachable Google-hosted Maven mirror and ran Maven with
  `-o`. No repository file was changed by this environment repair.
- `bash scripts/quick-check.sh` and `python3 -I scripts/check-pack.py` fail
  locally because the worktree `opencode.json` is the CI profile under the
  `skip-worktree` flag (`git ls-files -v opencode.json` → `S`) and differs from
  `git show HEAD:opencode.json`. This is the documented dispatch-69 / IMP-01
  measurement artifact, pre-existing and out of scope; the committed profile is
  untouched. Doxygen is not installed locally, so `scripts/docs.sh` could not
  be re-run; Javadoc was kept Doxygen-safe (`{@code}`, full `@param`/`@return`).
- Database/Spring integration tests were not run locally (the integrator owns
  them). Reserved migration V57 remains unused; no table, route or controller
  was added.

### Deferred (with rationale, not defects)

- TS reviewer finding 2 (run `tsc --noEmit` on the generated client from the
  Python gate) is deferred: it would couple the offline Python gate to a Node
  toolchain and add a config file that no current build script invokes. The
  client is Prettier-clean, has no authority argument, and its identifiers are
  now validated in Java; a follow-on can wire a real `tsc` gate when a build
  script owns it.
- Global POST-mapping ↔ catalog parity, per-controller use of the gate, durable
  OPS-02 sink, and human clinical sign-off remain the same out-of-scope
  follow-ons recorded above.
