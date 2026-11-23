# FIX-INSTANT-OPENAPI — Document the instant-messaging API in the OpenAPI contract

## Summary

Story FIX-INSTANT-OPENAPI (HIGH PRIORITY, reported as Delivery failing on PR #1497 / SMSG-03). On this checkout (`swarm/FIX-INSTANT-OPENAPI-1116`, main `e8f25f27`) **every acceptance gate is already green** and **no code change was made**. Commit `b4f8bacf` (FIX-OPENAPI-03, PR #1094) already published all nine `/api/v1/staff/instant/*` operations with tags, `Idempotency-Key` on every write, and named `*List` array response schemas explicitly documented as "not a cursor-paginated page". The three list GET responses are `$ref`s to those named array schemas, so the pagination rule correctly treats them as non-paginated collections and ignores them. The worker brief's central premise — that `OpenApiPaginationRulesTest` is red with 11 list GETs vs an expected 8 — was verified **false** by running the tests before touching any file. Implementing the brief's proposed wrapper (items + nextCursor) would be a breaking wire-format change premised on a false red-test claim; it was deliberately not done.

## Affected requirements

MSG-02, ARC-07, WEB-05 (story requirements). No requirement was changed; the existing implementation already satisfies the story acceptance.

## Risk class

Routine (verification only — no production, contract, client or test file changed). The underlying story is cross-boundary in nature, but this run made no changes.

## Review owner

smartclinic-senior-backend (contract/controller consistency); read-only reviews by smartclinic-plan-reviewer, smartclinic-code-reviewer, smartclinic-coverage-auditor and smartclinic-devsecops. smartclinic-test-engineer and smartclinic-senior-backend were consulted on the test-first and wrapper questions.

## Data changes

None. No migration, RLS change, backfill, lock or volume impact.

## Authorization boundaries and audit events

None changed. Existing instant routes retain their 401/403 responses; writes retain their single `Idempotency-Key` declaration (via `RetryKey` or `IdempotencyKey`); organization and actor remain in the request context. No audit event changed.

## Migration and rollout considerations

None. No schema or wire change.

## Test evidence

Commands were run on `swarm/FIX-INSTANT-OPENAPI-1116` at `e8f25f27`, before and after verification (no files were changed). Final lines are quoted exactly.

1. `mvn -B -f apps/api/pom.xml -Pno-security -Dtest=OpenApiContractRulesTest,OpenApiPaginationRulesTest test`
   `[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`
   `[INFO] BUILD SUCCESS` — exit 0.
   (OpenApiPaginationRulesTest 8/8; OpenApiContractRulesTest 20/20. The two pagination tests named in the brief are GREEN, not red.)

2. `mvn -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=OpenApiRouteParityTest test`
   `[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
   `[INFO] BUILD SUCCESS` — exit 0. (`-Pfast` deliberately NOT used; it sets `excludedGroups=integration`.)

3. `mvn -B -f apps/api/pom.xml -Pno-security -Dtest=InstantMessagingControllerTest,InstantMessagingServiceTest,InstantMessagingIntegrationTest test`
   `[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0`
   `[INFO] BUILD SUCCESS` — exit 0. Includes the retried-send-once and per-person-unread acceptance tests (InstantMessagingIntegrationTest.java:186-191).

4. `npm --prefix apps/staff-web run gen:api:check`
   `✨ openapi-typescript 7.4.2`
   `gen:api: generated client matches the contract` — exit 0. (Dependencies were absent; `npm --prefix apps/staff-web ci` was run first and changed no tracked file.)

5. `npm --prefix apps/staff-web run typecheck`
   `> tsc --noEmit -p tsconfig.json` (no output on success) — exit 0.

6. `bash apps/staff-web/check.sh`
   `Test Files  213 passed (213)`
   `Tests  2613 passed (2613)`
   `No files with missing coverage.` / `213 files fully covered.`
   `Statements   : 100% ( 4070/4070 )` / `Branches     : 100% ( 2080/2080 )` / `Functions    : 100% ( 1351/1351 )` / `Lines        : 100% ( 3885/3885 )` — exit 0.

7. `bash scripts/worker-check.sh`
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — exit 1. **Known cloud-worker false positive (OPS-CW-003-01), not a defect of this change:** `git ls-files -v opencode.json` = `S ... opencode.json` (skip-worktree); the worktree holds the CI runtime profile (`shell`/`gh` resources). I verified with `git show HEAD:opencode.json` compared to `gen.render(ROOT)`: `COMMITTED opencode.json MATCHES generated local profile: True`. This worker changed no script and no `opencode.json`; the integrator owns the full gate.

8. `python3 -I -m unittest discover -s scripts/tests`
   `Ran 1304 tests` / `FAILED (failures=6)` — 1298/1304 pass. All six failures are in `test_check_pack` and are the same skip-worktree `opencode.json` drift false positive (e.g. `test_committed_opencode_json_is_generated`: worktree `shell`/`gh` resources vs generated `subagent` profile; `test_cli_entry_point`: 1 != 0). Independent verification above shows the committed file is generated correctly. No `scripts/*` or `opencode.json` file was touched by this run.

After `git merge --no-edit origin/main` (reported `Already up to date`, no conflict markers), the affected suites were re-run: `mvn -B -f apps/api/pom.xml -Pno-security -Dtest=OpenApiContractRulesTest,OpenApiPaginationRulesTest,InstantMessagingControllerTest,InstantMessagingServiceTest,InstantMessagingIntegrationTest test` → `[INFO] Tests run: 77, Failures: 0, Errors: 0, Skipped: 0` / `[INFO] BUILD SUCCESS`; and `mvn -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=OpenApiRouteParityTest test` → `[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` / `[INFO] BUILD SUCCESS`.

`bash scripts/quality-gate.sh e8f25f27e2ec7f7942a8c8bbbb79fe84ea8a381b .agent/pr-notes/FIX-INSTANT-OPENAPI-20261123-070153.md` → `verdicts: only documentation changed; nothing to review`, exit 0 (constraint check and verdict check both pass).

Entry-point-to-test trace (unchanged behavior, still reachable): `GET /api/v1/staff/instant/{conversationId}/messages` → `InstantMessagingController.messages` → `InstantMessagingService.messages`, asserted by `InstantMessagingControllerTest.readsTheThread` (`jsonPath("$[0].sequence")`) and by `InstantMessagingIntegrationTest` on real PostgreSQL. `GET /unread` → `listsUnreadCounts`; `GET /conversations` → `listsConversations`; write path `POST /{conversationId}/messages` → `sendsWithTheIdempotencyHeader` plus the retried-send-once integration test.

## Coverage

No production file was changed, so there is **no new or changed branch to map** and no new test is required. Coverage evidence for the touched story area:

- Java (contract + instant slices): targeted suites above, 28 + 1 + 49 tests, 0 failures. Full JaCoCo (instruction/line/branch/method/class) is owned by the integrator's `mvn verify`; this worker did not run it and changed no Java file.
- Staff web: `bash apps/staff-web/check.sh` → 100% statements (4070/4070), branches (2080/2080), functions (1351/1351), lines (3885/3885); 213 files fully covered.
- New branches: none (no production change). No threshold, exclusion, skip, suppression or generated-artifact edit was introduced.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

## Findings

- **The brief is wrong and was not followed where it conflicts with observed evidence.** It asserted the two pagination tests are red (11 list GETs vs expected 8) and instructed reshaping `InstantMessageList`/`InstantUnreadList`/`InstantConversationList` into objects with `items` + `nextCursor` and changing the three controller GET methods. All of those tests are green. `OpenApiPaginationRulesTest.isListResponse()` (OpenApiPaginationRulesTest.java:225-234) inspects the raw response schema and returns true only for inline `type: array` or a `$ref` whose name ends in `Page`; it never resolves `$ref`. A response that is `$ref: '#/components/schemas/InstantMessageList'` is therefore correctly *not* a detected list, and the three schemas state "not a cursor-paginated page". The proposed wrapper would break the wire format, the three `jsonPath("$[0]...")` assertions, the generated client and 100% coverage, for no gate benefit.
- **Story already fixed on main.** Commit `b4f8bacf` (FIX-OPENAPI-03, PR #1094) already added the tags, write headers and named array schemas for all nine instant routes. No instant route appears in `contracts/openapi/missing-routes.txt`. Per the test-engineer, the brief's four RED failures were real when written (`.agent/pr-notes/FIX-OPENAPI-01-fix-20261122-080329.md:174-180`, Actions run `44970013021`) but were corrected before this checkout; the named-array-schema convention is documented and reviewer-signed-off in `.agent/pr-notes/SMSG-07-fix-20261122-035236.md:201-202, 219-221`. The brief is stale, not the code.
- **No code change.** No file under the task scope was modified. This notes file is the only new file, and it is committed with the (empty) scoped change. The next worker/integrator should treat this task as already satisfied; if the story is still considered open, the correct action is to close it as already-fixed rather than apply the brief's breaking wrapper.
- **No gate test, allow list or threshold was tampered with.** `git blame` on `OpenApiPaginationRulesTest.java:225-234` shows `isListResponse()` unchanged since its original commit `14454ebf` (START-08, 2026-11-21). `git diff HEAD --stat` over the contract-rule test directory, `contracts/openapi.yaml` and the generated client is empty. The green result comes from the contract fix in `b4f8bacf`, not from editing a test, exemption (ALLOWED is still only `listCatalogFamily`), baseline or threshold. A code reviewer raised a "false green" concern; the merged SMSG-07 note's signed-off convention and this blame evidence resolve it.

- **Worker-environment false positives recorded once.** `worker-check.sh` and six `test_check_pack` failures come solely from the skip-worktree CI-profile `opencode.json` (OPS-CW-003-01), confirmed via `git ls-files -v` and a `git show HEAD:opencode.json` vs `gen.render(ROOT)` comparison. Do not re-diagnose or regenerate that file.
- **staff-web dependencies were absent.** `npm --prefix apps/staff-web ci` was needed to run `gen:api:check` and `check.sh`; it changed no tracked file.
- **Optional (non-blocking) nit from devsecops.** `contracts/openapi.yaml:1624` declares `IdempotencyKey` as `required: true` for the send operation, while `InstantMessagingController.java:106` binds `@RequestHeader(value = "Idempotency-Key", required = false)`. Deduplication is not weakened: `InstantMessagingService.requireKey` rejects a missing/blank key with 422, so no gate is affected. Pre-existing and outside this story's change; recorded for a future documentation-consistency story, not fixed here.
- **Reviewer conflict resolved.** smartclinic-code-reviewer initially returned NO-GO with a Critical "false green" finding. On escalation with the SMSG-07 signed-off convention, the `git blame` proof that `isListResponse()` is unchanged since START-08, and the proof that its proposed `*Page` fix would break the exact-set assertion (out of scope), it withdrew the finding and returned GO. No suppression or weakening was introduced; the verdict rests on authoritative project evidence.
- **Optional (non-blocking) notes from code-reviewer.** (a) `contracts/openapi.yaml:2322-2347` marks every property of `InstantMessage`, `InstantUnread` and `InstantConversation` optional (generated client uses `?`) while the Java views are non-null records; adding `required` is a future tightening. (b) Same `Idempotency-Key` required-vs-optional nit already recorded above. Both pre-existing, outside this story's change, not fixed here.
- **Optional handoff notes from senior-backend.** (a) `OpenApiPaginationRulesTest.isListResponse()` is a heuristic: a `$ref` to an array schema whose name does not end in `Page` is silently treated as not-a-list. Hardening it (resolve `$ref`, inspect `type`) belongs in a separate, covered test-quality story — not a wire-format change here, and the rule/test files are out of this task's scope. (b) ARC-03 calls for cursor pagination on families that include conversations/messages; the "not a cursor-paginated page" design is a deliberate, already-published choice. If that tension matters, route it to the Product Owner as a new story rather than resurrecting this stale brief. (c) The task entry in `tasks/00002000-FIX-INSTANT-OPENAPI.md` remains `[-]` while its acceptance tests are green — tracker drift; the integrator owns reconciliation, and swarm workers must not edit `tasks/`.

## Lessons for workers

- Run the named "red" tests first. This checkout already had them green from commit `b4f8bacf`; a brief asserting a red test can be stale.
- When a brief conflicts with observed tool evidence, record the conflict in the notes and follow the story and AGENTS.md — do not make a breaking change on a false premise.
- The pagination gate does not resolve `$ref`. Naming a non-paginated collection schema `*List` (not `*Page`) is the intended way to express "ordered but not cursor-paginated".
- In cloud workers, `git ls-files -v opencode.json` = `S` explains `check-pack`/`test_check_pack` "stale" failures; verify with `git show HEAD:opencode.json` and never regenerate or commit that file.
- Staff-web done means `bash apps/staff-web/check.sh`; `tsc --noEmit` prints nothing on success, so capture its final line separately.
