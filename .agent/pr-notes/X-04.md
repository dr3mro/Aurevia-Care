# X-04 — Generated OpenAPI clients and server-side authorization for every command

## Requirement and acceptance mapping

| Requirement | Acceptance evidence |
|---|---|
| PRD-06 | No authority argument exists in the generated client; the server derives every decision from active membership and one owning domain policy. Clinical decisions remain human acts in the owning services (documented limit, not silently claimed). |
| IAM-01, IAM-02 | `CommandAuthorization.authorize` re-resolves roles/membership on every call; deactivation or role removal denies the next call. Missing tenant/subject, unknown command, unavailable membership and unregistered owner all fail closed with recorded denials. |
| ARC-03, ARC-04 | 24 explicit POST commands in one catalog drive the OpenAPI contract and the client; every function requires an idempotency key and `If-Match`; compare-and-set stays with the owning service. |
| OPS-02 | Every outcome is recorded before the caller is answered with tenant, subject, correlation, action, reason, fresh roles and an injected-clock instant. A durable sink is to be supplied by the owning service's transaction. |

## Files (all inside the declared dispatch scope)

- `contracts/x_04/README.md`, `contracts/x_04/check.py`
- `contracts/x_04/generated/{openapi.yaml,client.ts,manifest.json}`
- `apps/api/src/main/java/com/aurevia/smartclinic/api/x_04/{CommandDefinition,CommandCatalog,CommandAuthorization,InMemoryDecisionSink,CommandClientGenerator}.java`
- `apps/api/src/test/java/com/aurevia/smartclinic/api/x_04/{CommandAuthorizationTest,CommandClientGeneratorTest}.java`

Reserved migration `V57` is intentionally unused; `db/migrations` is outside the scope and gaps are permitted. No route, controller or existing file was modified.

## Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -B -f apps/api/pom.xml -Pno-security,fast test` | BUILD SUCCESS; `Tests run: 1564, Failures: 0, Errors: 0, Skipped: 0` |
| JaCoCo `apps/api/target/site/jacoco/jacoco.xml` parsed after `mvn -q -B -f apps/api/pom.xml jacoco:report` | `com/aurevia/smartclinic/api/x_04`: INSTRUCTION 0 missed / 1509 covered; BRANCH 0/92; LINE 0/240; METHOD 0/38; CLASS 0/9. Bundle `jacoco:check` is intentionally not green in the fast tier because pre-existing integration-only classes are excluded; the integrator runs the full DB gate. |
| Generator: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test-compile`; `java -cp <classes+deps> com.aurevia.smartclinic.api.x_04.CommandClientGenerator contracts/x_04/generated` | Wrote three artifacts; `--check` on the same path exited 0 |
| `python3 -I contracts/x_04/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `node_modules/.bin/prettier --check contracts/x_04/generated/client.ts` and `npm run format:check` | Both pass; generated TypeScript is Prettier-clean |
| `python3 -I scripts/check-docs.py` | exit 0; `python3 -I scripts/check-secrets.py` exit 0 |
| `mvn -q -B -f apps/api/pom.xml -DskipTests -Dspotbugs.skip=false spotbugs:check` | Initially 8 findings: 2 useless `@SuppressFBWarnings` in `CommandAuthorization` (fixed by removal) and 6 pre-existing findings in `messaging.acknowledgment`, `messaging.conversations`, `messaging.governance`, `patient.identity`, `prescription.rx`. Re-run: 6 findings, **zero in x_04**. |
| `mvn -q -B -f apps/api/pom.xml -Pno-security verify` (attempted full gate) | 28 integration errors, all `Connection to 127.0.0.1:5432 refused`. PostgreSQL is not running in this worker; DB/integration verification is delegated to the integrator as instructed. |
| `python3 -I -m unittest discover -s scripts/tests -q` | 164 tests, 6 failures / 4 errors. Confirmed worker measurement artifact: `git ls-files -v opencode.json` → `S` (skip-worktree); `gen-opencode.py`'s `render()` equals `git show HEAD:opencode.json` and differs from the worktree CI profile. No script, workflow, agent or tracker file was touched by this story. |

## Defects found and resolved in this story

1. Path tokens start with `/`; the artifact-safe validator initially rejected every catalog path. Fixed to allow a leading `/` and covered by a unit test. Evidence: first fast run, 13 errors, all `path must be a bounded safe token`; second run green.
2. Generated TypeScript ended with an extra blank line and failed Prettier. Fixed in the generator (single trailing newline); Prettier now passes and drift check still matches.
3. Security review (simulated) found that a union of all policies could widen a command through a colliding action key (for example `APPOINTMENT_CHECK_IN` is claimed by two policies). Fixed by keying rules by owning policy name and asking exactly one owner; regression test `aCollidingActionInAnotherPolicyCannotWidenACommand` proves a second policy's allow cannot grant.
4. Security review found caller-supplied roles and a bare membership boolean. Replaced with a `MembershipResolver` port re-invoked on every call and a `ServerIdentity` that contains only server-derived identity; `membershipAndRolesAreReResolvedOnEveryCall` proves revocation on the next call.
5. Security review found unrecorded denials for malformed/unknown commands and NPE paths. All paths now record a decision before throwing a clean 401/403/422; covered by `malformedAndUnknownCommandsAreRecordedDenials` and `unauthenticatedIdentitiesAreDeniedAndRecorded`.
6. SpotBugs flagged two unnecessary suppression annotations in `CommandAuthorization`; removed.
7. Generated paths containing `{id}`/`{key}` templates were not callable. The generator now emits typed path-argument parameters and `encodeURIComponent` interpolation, with Prettier-conformant line wrapping; `check.py` verifies parameter parity and `pathParametersBecomeEncodedFunctionArguments` covers both wrapped and unwrapped forms.

## Decisions and explicitly deferred work

- **Scope discrepancy (decision).** My dispatch declares `contracts/x_04` and `apps/api/.../api/x_04`; TODO.md line 531 lists `contracts/openapi` and `apps/api/.../api/authorization`. I followed the dispatch, matching the X-01/X-02 sandbox convention. The orchestrator should reconcile the tracker entry; this worker may not edit TODO.md.
- **Not a global enforcement point (deferred, cross-boundary).** The gate is a reusable choke point, but no existing controller calls it in this scoped slice, and 80+ POST mappings are not proven to match the catalog. Wiring it into owning services and a POST-mapping ↔ catalog parity test need a separate story with edits outside this scope. The README states this plainly; no overclaim is made.
- **PRD-06 clinical decisions (deferred).** The gate does not replace attestation, revision checksums, signer checks or clinical sign-off. `ReleaseActionPolicy` still names `tenant_admin` among deciders; that requires clinical-owner approval before live enablement.
- **Durable audit (deferred).** `InMemoryDecisionSink` is a synthetic reference. The production OPS-02 sink must be an `AuditAppender`-backed adapter in the owning service's transaction.
- **Client body typing (deferred).** Bodies are `unknown`, validated server-side; closed per-command body types are a follow-up. `If-Match` and `Idempotency-Key` are already required arguments.
- **Tracker scope, PG unavailability and the six pre-existing SpotBugs findings** are recorded here as environment/inherited issues, not as X-04 defects; no out-of-scope file was changed to work around them.

## Evidence provenance

Tool evidence: all commands and results above were executed locally by this worker. Simulated-review evidence: bounded `senior-spring-security` and `senior-typescript` specialist reports; their actionable findings F2–F4 and the two SpotBugs-style pitfalls were implemented and retested. No human participant, clinician or patient evidence is claimed; all fixtures are synthetic and contain no identifiers or clinical data.
