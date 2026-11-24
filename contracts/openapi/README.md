# X-04 — Generated command clients and server-side authorization (PRD-06)

Synthetic-only cross-cutting slice. The authoritative command catalog is
[CommandCatalog.java](../../apps/api/src/main/java/com/aurevia/api/authorization/CommandCatalog.java).
The committed [OpenAPI contract](generated/openapi.yaml),
[typed TypeScript client](generated/client.ts) and
[checksum manifest](generated/manifest.json) are generated from that one model by
[CommandClientGenerator.java](../../apps/api/src/main/java/com/aurevia/api/authorization/CommandClientGenerator.java).
The server gate is
[CommandAuthorization.java](../../apps/api/src/main/java/com/aurevia/api/authorization/CommandAuthorization.java).
Requirement text is in [PROJECT_V1_SPEC.md](../../.opencode/config/PROJECT_V1_SPEC.md);
the shared wire conventions are in the [X-01 contract](../contract/README.md).

This slice adds no route, controller, database table or migration; reserved
version `V57` is intentionally unused (gaps are allowed by the migration gate).

## Obligation map

| Obligation | Requirement | Owner in this slice | Status |
|---|---|---|---|
| Clinical users decide and attest; the platform makes no autonomous clinical decision | PRD-06 | Generated client has no authority argument; server gate delegates to the owning domain policy | Coarse gate only — it does not replace the owning service's attestation, revision or signer checks |
| Deny access without active membership, required permission and domain authorization | IAM-01, IAM-02 | `CommandAuthorization.authorize`: fresh `MembershipResolver` per call, single owning policy per command | Explicit allow or deny; unknown commands, owners and roles fail closed |
| Explicit POST commands with preconditions and machine-readable errors | ARC-03, ARC-04 | Catalog of 24 commands, generated `If-Match` and `Idempotency-Key` arguments, 401/403/422/428 contract responses | Compare-and-set remains with the owning service |
| Auditable outcomes for sensitive actions | OPS-02 | `Decision` carries tenant, subject, correlation, action, reason, roles and clock instant; every path records before answering | Durable sink must be supplied by the owning service's transaction |

## What the generated client cannot do

Every generated function has the fixed shape
`fn(ctx, idempotencyKey, ifMatch, body, transport)`. It accepts identity and
request metadata, never a role, permission, scope or actor. `ctx` describes the
caller for header construction only; it is **not** an authority claim. The
`body` is opaque (`unknown`) and must be validated at the server boundary.
`X-Synthetic-Subject` is a synthetic-auth-only header and is not production
authentication. A transport that injects headers cannot grant authority: the
server re-derives membership and roles from the active organization on every
protected request.

The catalog binds each command to exactly one owning policy. A colliding
action key in another policy cannot widen that command, because `permits` asks
only the named owner. Client-supplied `claimedRole` and `claimedPermissions`
fields exist solely as inert evidence in `CommandRequest` and are never read.

## Generation and drift commands

From the repository root, after `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test-compile`:

```sh
CP="apps/api/target/classes:$(mvn -q -f apps/api/pom.xml dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" com.aurevia.api.authorization.CommandClientGenerator contracts/openapi/generated
java -cp "$CP" com.aurevia.api.authorization.CommandClientGenerator --check contracts/openapi/generated
python3 -I contracts/openapi/check.py
python3 -I -m unittest discover -s contracts/openapi -p 'test_*.py' -q
node_modules/.bin/prettier --check contracts/openapi/generated/client.ts
```

`--check` compares all three artifacts with a fresh generation. `check.py`
independently recomputes both X-01 canonical SHA-256 values, checks command
parity between manifest, contract and client, and rejects client-side authority
tokens. Its authority scan is case-insensitive and identifier-suffix aware, so
`actor`, `userRole`, `grantedScopes` and `X-Actor` are rejected; path-parameter
encoding and the four required function arguments are verified **per command**,
not globally. `test_check.py` covers each of these rules with synthetic fixture
drift. Neither command needs a database. The client is generated in
Prettier-compliant TypeScript and is covered by the repository format gate.

`CommandClientGeneratorTest.committedArtifactsMatchAFreshGeneration` runs the
same drift check inside the Java unit tier, so a catalog change that is not
regenerated fails `mvn test` and the build-failing gate rather than being
noticed only by a manual command.

## Source-of-truth honesty and limits

The Java catalog, not a hand-authored OpenAPI file, is the source of truth; the
OpenAPI document and the TypeScript client are emitted together from it. This
catalog-generated `generated/openapi.yaml` is distinct from the staff-API
`contracts/openapi.yaml` (X-01), which `apps/staff-web/scripts/gen-api-types.sh`
consumes; neither file is edited by hand. The
`--check` gate, the `check.py` gate and the Java-tier drift test together prove
client ↔ catalog ↔ manifest consistency. They do **not** prove that every
running `@PostMapping` in `apps/api` calls this gate, nor that the documented
paths match every deployed route: the 80+ command routes are owned by their
feature stories and cannot be rewired inside this scoped slice. Wiring the gate
into each owning service, and a POST-mapping ↔ catalog parity test, are
follow-on cross-boundary work with a named owner.

PRD-06 clinical decisions remain human acts in the owning services. This gate
does not derive a diagnosis, treatment or release decision, does not bind a
revision checksum, and does not replace the clinical attestation in
`authz.specialty` or the release and critical-result workflows. The existing
`ReleaseActionPolicy` names `tenant_admin` among release deciders; that
delegation is unchanged here and needs clinical-owner sign-off before live
enablement. The in-memory decision sink is a synthetic reference; durable
OPS-02 audit belongs to the owning service's transactional audit appender.

This slice is delivered at the canonical, business-domain paths named by the
tracker entry: `contracts/openapi` and `com.aurevia.api.authorization`.
The earlier story-ID paths are not retained, so no duplicate command catalog or
authorization gate exists. Reserved migration V57
is unused; the remaining story-ID contract paths were renamed to business-domain
folders (`contracts/contract`, `contracts/events`, `contracts/documents`) by
REFACTOR-PACKAGES-02.

## Entry point to test trace

| Entry point | Unit test |
|---|---|
| `CommandDefinition` token and POST validation | `CommandAuthorizationTest.definitionRejectsAbsentUnsafeAndNonPostCommands` |
| `CommandCatalog.synthetic`, duplicate and unknown ids | `CommandAuthorizationTest.catalogIsImmutableUniqueAndUnknownCommandsFailClosed` |
| `CommandAuthorization.authorize` allow path | `CommandAuthorizationTest.allowedDecisionCarriesOps02FieldsAndSkipsDisallowedRoles`, `everyCatalogCommandHasAtLeastOneAuthorizedRole` |
| Forged client claims ignored | `CommandAuthorizationTest.clientClaimedAuthorityNeverGrantsAPermission` |
| Fresh membership and role changes | `CommandAuthorizationTest.membershipAndRolesAreReResolvedOnEveryCall` |
| Missing identity, malformed and unknown commands | `CommandAuthorizationTest.unauthenticatedIdentitiesAreDeniedAndRecorded`, `malformedAndUnknownCommandsAreRecordedDenials` |
| Unavailable membership, unregistered policy, action collision | `CommandAuthorizationTest.unavailableMembershipAndUnregisteredOwnerFailClosed`, `aCollidingActionInAnotherPolicyCannotWidenACommand` |
| Inert claims, defensive copies, decision ledger | `CommandAuthorizationTest.inertClaimsAndDecisionValuesAreDefensivelyCopied`, `decisionLedgerCopiesClearsAndRejectsNull` |
| Contract, client and manifest generation | `CommandClientGeneratorTest.everyCommandAppearsInContractAndClient`, `manifestCarriesDeterministicChecksumsAndEveryCommand`, `emptyCatalogStillGeneratesValidArtifacts` |
| Drift detection and CLI | `CommandClientGeneratorTest.writeAndCheckDetectEveryKindOfDrift`, `runWritesChecksAndRejectsMalformedArguments`, `mainUsesTheSyntheticCatalog` |
| Build-failing artifact drift | `CommandClientGeneratorTest.committedArtifactsMatchAFreshGeneration` |
| 500 fail-closed outcome in the contract | `CommandClientGeneratorTest.contractDocumentsTheFailClosedAuditOutcome` |
| Collaborator failures record and fail closed | `CommandAuthorizationTest.collaboratorFailuresAreRecordedAndReturnedAsMachineReadableErrors` |
| Bounded untrusted audit identifiers | `CommandAuthorizationTest.untrustedCommandIdentifiersAreBoundedBeforeTheyAreAudited` |
| Generator-safe tokens and unique routes | `CommandAuthorizationTest.definitionsRejectTokensThatCannotBeGeneratedSafely`, `catalogRejectsDuplicateRoutes` |
| Offline gate authority and per-function checks | `contracts/openapi/test_check.py` (7 synthetic cases) |

Evidence is tool-generated from synthetic fixtures and simulated specialist
review. No human, clinical or patient-participant evidence is claimed.
