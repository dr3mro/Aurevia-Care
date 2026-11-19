# S-G3-08 — Publication, dispatch, first view and revision-bound acknowledgment (MSG-05)

## Requirement IDs and acceptance mapping

Primary: **MSG-05**. Supporting: MSG-01 (connection-scoped access), MSG-04 (instruction
revisions/handoff), ARC-03 (idempotency/version preconditions), ARC-07 (commit facts +
audit + queue + notification outbox together), OPS-02 (audit purpose/scope/correlation),
PAT-01/02 (generic notification, retrieve full content only after authentication), UX-01
(RTL, instant comparison).

| MSG-05 clause | Implementation | Evidence (executed test) |
|---|---|---|
| Publication as a separate fact | `messaging/acknowledgment/InstructionFacts.Publication`, `InstructionFactService.publish`, `InstructionFactStore.putPublication`, `POST /api/v1/instructions/publish` | `InstructionFactServiceTest.publishRecordsOnePublicationFactWithOpaqueContentReference`, `publicationIsIdempotentAndConflictsOnClientIdReuse` |
| Notification dispatch as a separate fact, generic only | `InstructionFacts.Dispatch` (generic key `NEW_INSTRUCTION`, correlation id, no clinical text), `retryDispatch`, `POST .../dispatch/retry`; mobile `notification.buildGenericNotification` | `InstructionFactServiceTest.notificationDispatchIsAGenericSeparateFact`, `retryDispatchAddsASecondGenericFactAndIsIdempotentByClientId`; mobile `notification.test.ts` (no patient/unit/identifier/clinical detail) |
| First in-app view as a separate fact, recorded once, never acknowledges | `InstructionFacts.FirstView`, `putFirstViewIfAbsent`, `markFirstView`; mobile `facts.recordFirstViewOnce`, `client.markFirstView`, screen `useEffect` once per `id|revision` | `InstructionFactServiceTest.firstViewIsRecordedOnceAndNeverAcknowledges`, `firstViewDoesNotClearAnOverdueFollowUp`; mobile `facts.test.ts` + `InstructionAcknowledgmentScreen.test.tsx` |
| Revision-bound explicit acknowledgment (receipt only) | `InstructionFacts.Acknowledgment` + `AcknowledgmentScope.RECEIPT`, `acknowledge`, `POST .../acknowledge`; stale revision, not-requested and reused-key rejected; per-account receipt on shared chart (IAM-05) | `InstructionFactServiceTest.acknowledgmentBindsToTheExactPublishedRevision`, `acknowledgmentIsAttributedToTheAuthenticatedAccountOnASharedChart`, `aBusinessDenialDoesNotBurnTheIdempotencyKey`; mobile `facts.acknowledgeRevisionBound` tests (withdrawn / no request / superseded / revision mismatch / already acknowledged) |
| Clinical consent, task completion and critical-result acknowledgment stay in their own workflows | Scope is `RECEIPT` only; no consent/task/critical fields on any fact | `InstructionFactsTest.acknowledgmentScopeIsReceiptOnly`, `neverMutatesAnUnrelatedClinicalWorkflowObject` |
| Overdue requested acknowledgments routed to owning unit for follow-up | `InstructionFactService.overdueFollowUps`/`resolveFollowUp`, `FollowUp`/`FollowUpState`, `GET .../overdue-follow-ups`, `POST .../follow-up/resolve`; mobile `overdueInstructions`, `owningUnitForOverdue` | `InstructionFactServiceTest.overdueRequestedAcknowledgmentRoutesToTheOwningUnit`, `overdueScanExcludesAcknowledgedFutureNotRequestedAndResolvedItems`; mobile `facts.test.ts` overdue/owning-unit cases |
| Full content retrieved only after authentication; connections/tenants rechecked | Mobile `createMessageFactsClient.requireAuth` on every protected command; tenant + verified chart + active connection checks on the service; two tenants with identical local IDs | `client.test.ts` (missing/empty credential, cross-tenant concealment), `InstructionFactServiceTest.identicalLocalIdentifiersStayIsolatedAcrossTenants`, `patientActionsRequireAVerifiedChartAndActiveConnection` |
| Facts + audit committed together, append-only, non-sensitive | `AuditFact` purpose `INSTRUCTION_FACT` / scope `PATIENT_MESSAGE` / correlation id appended in the same guarded call | `InstructionFactServiceTest.missingContextFailsClosedBeforeAnyAudit`, `InstructionFactsTest.auditFactCarriesPurposeScopeAndCorrelation`, `InMemoryInstructionFactStoreTest.auditAndFactProjectionAreScopedToTheirTenant` |

## Scope and files

- Backend (business-domain package, per authoritative `TODO.md` and the trusted naming rule):
  `apps/api/src/main/java/com/aurevia/smartclinic/messaging/acknowledgment/` —
  `InstructionFacts`, `InstructionFactActions`, `InstructionFactInputs`,
  `InstructionFactStore`, `InMemoryInstructionFactStore`, `InstructionFactService`,
  `InstructionFactController`.
- Mobile: `apps/patient-mobile/src/features/s_g3_08/` — `types.ts`, `facts.ts`,
  `client.ts`, `notification.ts`, `InstructionAcknowledgmentScreen.tsx` and their tests.
- Java unit tests live in the mirrored domain package
  `apps/api/src/test/java/com/aurevia/smartclinic/messaging/acknowledgment/`.
- No migration file was added. The store is a synthetic in-memory adapter, consistent
  with the sibling messaging stories (S-G3-06/07/10 have no V34/V35/V38 migration either).
  Reserved V36 is therefore unused; a production adapter must add tenant RLS uniqueness
  constraints (documented on `InstructionFactStore`).

## Exact commands run and results (evidence source: executed tool commands)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0. Surefire totals:
  1642 tests, 0 failures, 0 errors, 0 skipped. Acknowledgment package: 75 tests
  (12 store + 7 controller + 45 service + 11 domain), all green.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast verify` → JaCoCo `check` goal failed
  as expected **for the fast profile only**: integration-tagged classes (repositories,
  controllers of other stories) are excluded and therefore uncovered. The CSV shows
  **0 missed instructions and 0 missed branches for every class in the
  `messaging.acknowledgment` package**, i.e. 100% coverage of this story's production
  code from unit tests. The integrator runs the full `scripts/check.sh` with PostgreSQL,
  which covers the integration suites.
- Patient mobile (`cd apps/patient-mobile`): `npm ci --no-audit --no-fund`,
  `npm run typecheck` (exit 0), `npm run lint` (exit 0),
  `npm run test:coverage -- --ci` → 26 suites, 274 tests passed; `s_g3_08` files at
  100% statements/branches/functions/lines.
- `python3 -I scripts/check-docs.py` → exit 0. `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q`
  → the list-bearing-record check passes; one failure is **out of scope** (see Defects).

## Defects found and decisions

- **Fixed (in scope):** `InstructionFacts.java` from a prior attempt contained corrupted
  Javadoc — three enum constructors had empty `/** ... @param wire ... */` blocks and the
  five command records had unindented `@return this record` tags. Replaced with proper
  Javadoc (descriptions plus `@param`/`@return`). No behavior changed; the full API unit
  suite was re-run green afterwards.
- **Out-of-scope blocker (not fixed):** `scripts/tests/test_java_conventions.py::
  test_javadoc_links_are_fully_qualified` fails on
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/trend/Qualifier.java` —
  `{@link #from(String)}` has no qualifier. Both the test and that file arrived in the
  same merge `4f9bbeb` (S-G2-07 / PR #113); this is pre-existing on HEAD and belongs to
  `exchange/trend`, outside S-G3-08's scope. It will fail `scripts/check.sh` for any PR
  until S-G2-07 (or a repair) fixes it. Owned by the integrator/orchestrator.
- **Environment artifact (not a code defect):** `opencode.json` carries the `skip-worktree`
  flag (`git ls-files -v opencode.json` → `S`) and its worktree hash differs from
  `git show HEAD:opencode.json`. Per MEMORY.md this is the CI profile copied by
  `agent-run.sh`; `check-pack.py` staleness and the related tooling test failures seen
  locally are the documented cloud-worker false positive, not HEAD truth. I did not
  edit `opencode.json`.
- **Decision — package path:** the untrusted story text names the backend scope
  `messaging/s_g3_08`, but authoritative `TODO.md` names `messaging/acknowledgment` and
  the trusted instruction requires business-domain naming, never story/task IDs. I used
  `messaging/acknowledgment` (a story-ID Java package would violate AGENTS.md and
  CODING_STYLE.md). Mobile feature folders remain `s_g3_08`, matching every sibling
  mobile story's convention.
- **Decision — no V36 migration:** the slice uses a synthetic in-memory store, mirroring
  sibling messaging stories. The production-store contract and its RLS/unique
  constraints are documented on `InstructionFactStore` for a future DB adapter.

## Evidence limitations

Doxygen is not installed in this worker, so `scripts/docs.sh` could not be run locally
(it exits with 'Install Doxygen...'). The integrator's `integrator-gate.sh` runs both
`check.sh` and `docs.sh`. All delivery evidence above is tool-generated and synthetic;
there is no human or clinical participant evidence. No real patient data or credentials
are present in any fixture, test or note.
