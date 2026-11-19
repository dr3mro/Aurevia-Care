# S-G3-08 — Publication, dispatch, first view and revision-bound acknowledgment (MSG-05)

## Requirement IDs and acceptance mapping

Primary: **MSG-05**. Supporting: **MSG-04** (revision/withdrawal context), **MSG-01** (server-side patient/destination/context validation), **ARC-01, ARC-03, ARC-04, ARC-05, ARC-07, OPS-02, UX-01, PAT-01/02**. Specification: `SMARTCLINIC_V1_SPEC.md` §6.1 MSG-05, §7 ARC-01..07, §8 OPS-02, §1 pilot metrics.

| Acceptance behaviour | Implementation | Synthetic test evidence |
|---|---|---|
| Publication, notification dispatch, first in-app view and explicit acknowledgment are four separate append-only facts | `InstructionFacts.FactType` (5 kinds incl. follow-up), `Publication`/`Dispatch`/`FirstView`/`Acknowledgment` records; `InstructionFactService.publish/retryDispatch/markFirstView/acknowledge` | `InstructionFactsTest`, `InstructionFactServiceTest.timelineShowsTheFourFactsAsSeparateEntries`, `facts.test.ts` (four independent slots) |
| Acknowledgment binds to one exact revision and is receipt-only | `AcknowledgmentScope.RECEIPT`; stale revision → 409 `STALE_REVISION`; `acknowledgeRevisionBound` returns `REVISION_MISMATCH`/`SUPERSEDED` | `InstructionFactServiceTest.acknowledgmentBindsToTheExactPublishedRevision`, `facts.test.ts` revision-bound cases |
| Generic push notification carries no clinical detail; content only after authentication | `GENERIC_NOTIFICATION_KEY=NEW_INSTRUCTION`, `Dispatch` has no contentRef; `buildGenericNotification()` fixed text; client re-reads credential for every protected command | `InstructionFactServiceTest.notificationDispatchIsAGenericSeparateFact`, `notification.test.ts`, `client.test.ts` auth tests |
| Track overdue requested acknowledgments and route to owning unit | `overdueCandidates`, `overdueFollowUps`, `FollowUp` (ROUTED/RESOLVED), `overdueInstructions`/`owningUnitForOverdue` on mobile | `InstructionFactServiceTest.overdueRequestedAcknowledgmentRoutesToTheOwningUnit`, `facts.test.ts` overdue routing |
| Immutable revision; idempotency; tenant isolation; fail-closed authorization/audit | `putInstruction` now `putIfAbsent` + `REVISION_CONFLICT`; monotonic revision; atomic key reservation; org-prefixed keys; per-request membership/role/connection re-check; audit on success and denial | `InstructionFactServiceTest` (11 new cases), `InMemoryInstructionFactStoreTest`, `InstructionFactsTest` |

Entry-point-to-test trace: `POST /api/v1/instructions/publish` → `InstructionFactController.publish` → `InstructionFactService.publish` → `recordedClientDigest` → membership/role/unit/patient-context/monotonic validation → `reserveClientDigest` → `putInstruction` (immutable) → `putPublication` (+ optional generic `Dispatch`) → `appendAudit` → 201. `POST .../acknowledge` → `acknowledge` → published-instruction + verified-chart + active-connection + receipt-requested + current-revision checks → atomic key reserve → `putAcknowledgmentIfAbsent` → audit. `GET .../facts` → `facts` → tenant scoped timeline. `GET .../overdue-follow-ups` → scan + follow-up routing. Verified by `InstructionFactControllerTest` (unit), `InstructionFactServiceTest` (domain) and the mobile `client.test.ts`/`facts.test.ts`; the real-PostgreSQL isolation suite is the integrator's gate.

## Commands run and results (evidence source: executed tool commands)

- `cd apps/api && mvn -q -B -Pno-security,fast -Dtest='com.aurevia.smartclinic.messaging.s_g3_08.*Test' test` → **EXIT 0**; Surefire: **73 tests, 0 failures, 0 errors** (Service 43, Store 12, Facts 11, Controller 7).
- `cd apps/api && mvn -q -B -Pno-security,fast test` → **EXIT 0**; full fast tier **1476 tests, 0 failures, 0 errors, 0 skipped**.
- `cd apps/api && mvn -q -B jacoco:report` → EXIT 0; per-class report: `InstructionFactService` INSTRUCTION 1274/1274, BRANCH 76/76, LINE 198/198, METHOD 33/33, CLASS 1/1; `InMemoryInstructionFactStore` 869/869 instructions, 80/80 branches, 55/55 methods; every other class in the package 100% (interfaces have no code). No fabricated coverage; full-project JaCoCo gate belongs to the integrator's `mvn verify`.
- `cd apps/patient-mobile && npx jest src/features/s_g3_08 --runInBand --coverage --collectCoverageFrom='src/features/s_g3_08/**/*.{ts,tsx}' --coverageThreshold='{"global":{"branches":100,"functions":100,"lines":100,"statements":100}}'` → **EXIT 0**; **42 tests, 4 suites**; 100% statements/branches/functions/lines for every logic file (`types.ts` is type-only).
- `cd apps/patient-mobile && npm run typecheck` → EXIT 0; `npm run lint` → EXIT 0 after fixing one `Array<T>` warning; `npm test -- --runInBand` → EXIT 0, **241 tests, 25 suites**.
- `bash scripts/frontend-check.sh` → **EXIT 0** (`frontend standards: policy and tool commands passed`, includes both modules + root typecheck/lint/prettier/tests).
- `npx prettier --check apps/patient-mobile/src/features/s_g3_08/*.ts *.tsx` → EXIT 0.
- RED evidence before implementation: Java `InstructionFactServiceTest` 2 failures (immutable revision, stale revision), then 7 failures + 1 error with the second batch (due date, patient unit, digest, key burn, replay 500, orphan key, two overdue cases). Mobile Jest 11 failures across 3 suites (per-revision first view, re-acknowledgment, status, instant compare, replay, screen revision/revision-mismatch/RTL-adjacent behavior, auth scoping, tenant filtering). All were caused by the missing behavior described above, then turned green by the scoped changes.

I did **not** run `db/check.sh`, `mvn verify`, the real-PostgreSQL isolation suite or any `gh`/push/tag command: those belong to the integrator/branch policy for this worker.

## Defects found and fixed (in scope)

1. **Published revision was mutable** — `putInstruction` overwrote an existing `(org,id,revision)`; now `putIfAbsent` and a `REVISION_CONFLICT` guard so an acknowledgment can never bind to silently changed content (ARC-01/04, MSG-05).
2. **Stale revision could be published after a newer one** — rejected with 409 `STALE_REVISION`.
3. **Idempotency key was burned by a later business denial** and a replay of a denied command threw `NoSuchElementException` (HTTP 500) — keys are now read-then-atomically-reserved after validation; committed-fact lookups fail closed with 409 `CONFLICT`.
4. **Non-atomic check-then-act on keys** allowed duplicate dispatch under concurrency — reservation is one atomic `putIfAbsent`; a lost race fails closed with 409 `CONFLICT` (tested with a synthetic racing store).
5. **Publish digest omitted patient/unit** — different payloads under the same key returned the original success; digest now includes both (ARC-03).
6. **Patient↔unit relationship was not checked on publish** — added active-connection validation (MSG-01 defense in depth).
7. **Acknowledgment requested with no due instant silently disabled overdue routing** — now 422 `ACKNOWLEDGMENT_DUE_DATE_REQUIRED`.
8. **Superseded revisions generated un-clearable overdue follow-ups** — excluded from detection and from the open queue.
9. **Mobile first view was per instruction, not per revision** — a corrected revision could neither record a first view nor be displayed correctly; now keyed by `(instructionId, revision)` end to end, with a new-revision screen test.
10. **Mobile receipt slot blocked a new requested revision** and status treated any old receipt as terminal — both are revision-aware now.
11. **Mobile `overdueInstructions`/`owningUnitFor` skipped auth and returned cross-tenant rows** — both now require the credential and filter by active organization and verified chart.
12. **Mobile screen offered an acknowledge button the domain always rejected** when `request.revision !== revision` — button is now withheld.
13. **Mobile deadline comparison was lexicographic** — uses `Date.parse` so an offset timestamp cannot hide an overdue request; `reduceFact` no longer lets a replay overwrite an earlier first view or receipt.

No patient data, credentials or raw logs are recorded here; all identifiers in code/tests are synthetic.

## Deferred findings (not fixed here — decision/owner needed)

These came from four read-only specialist reviews (simulated, not human clinical evidence) and are outside the declared scope or need a separate story:

- **Withdrawal state is owned by S-G3-07/MSG-04.** This slice's synthetic `Instruction` has no state field, so a direct API call cannot observe a MSG-04 withdrawal; only the mobile projection checks `withdrawn`. A cross-store/cross-module state check needs Architect + clinical-safety sign-off before G5 launch. Flagged as **launch-relevant** by the physician reviewer.
- **Durable storage / outbox / worker.** The in-memory adapter, local mobile fact client and lazy follow-up scan follow the pattern of sibling messaging slices (S-G3-01…07). V36 was reserved but **not created**: scope contains only the two feature paths, no `db/migrations`, and every sibling messaging story uses the synthetic adapter. A separate cross-boundary story must add tenant-keyed tables, RLS/uniqueness, atomic outbox, a scheduled overdue worker and real client API wiring. Architect/PO decision requested at integration; no durable evidence is claimed here.
- Follow-up state is stored as one overwritten row rather than an append-only fold; timeline reads are audited as `FOLLOW_UP_LIST` and a unit-unassigned tenant admin gets 404; ARC-03/04 headers (`Idempotency-Key`, `If-Match`, 412 for stale) are not implemented on these commands. Each needs its own bounded story.
- Arabic strings/localization remain with S-G3-11/S-G5-10 (UX-01); RTL direction is implemented and tested here.

## Evidence classification and directives

- Tool evidence: all command outputs and counts above, executed locally on this worker branch.
- Reviewer evidence: bounded findings from `smartclinic-senior-backend`, `smartclinic-senior-mobile`, `smartclinic-software-architect` and `smartclinic-physician-reviewer` (simulated reviewers, no edits by them).
- Simulated-user/human evidence: none. No clinician or patient participated; real-patient approval remains a G5 gate.
- Branch `swarm/S-G3-08-98`; no push, tag, `gh`, tracker or docs edit was performed by this worker. Reserved migration V36 was not used.
