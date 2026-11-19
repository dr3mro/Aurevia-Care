# S-G0-08 — Emergency access and platform support access controls (IAM-06)

## Scope honoured

Only the declared scope was available for edits:
`apps/api/src/main/java/com/aurevia/smartclinic/g0/s_g0_08`,
`apps/api/src/test/java/com/aurevia/smartclinic/it/s_g0_08`, and reserved migration `V11`.
No shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`,
`docs/swarm`) and no file outside that scope was edited. No push, tag or `gh` call
was made. Synthetic fixtures only.

## Requirement IDs and acceptance mapping

- **IAM-06** (primary): emergency access is time-limited (1–60 min), scoped to one
  tenant-local facility, reasoned, attributed to a qualified clinician, audited on the
  V3 append-only chain, independently reviewable by privacy auditor / tenant admin
  (grantee cannot review own grant), and revocable early. Platform support access is
  **off by default** and only effective inside an approved, time-bounded (1–240 min)
  incident window; checks return an explicit fail-closed `AccessDecision`.
- **ARC-02 / OPS-02** (supporting): V11 tables carry `(organization_id, id)` keys and
  forced row-level security; purpose, role, facility, actor and timestamp are recorded
  and committed with the audit event.
- Delivered surfaces already present in scope (unchanged by this fix attempt):
  `AccessController` (8 endpoints under the IAM route family), `EmergencyAccessService`,
  `SupportAccessService`, `AccessRepository`, `AccessRules`, `AccessContracts`,
  `V11__emergency_and_support_access.sql`, `AccessUnitTest` (41 tests),
  `AccessIntegrationTest` (8 DB tests, integrator-run).

## Reported failure — investigation and disposition

The integrator's "requested change" reported a `scripts/check.sh` failure whose only
evidence was this stack trace:

```
Error: useTheme must be used inside ThemeProvider
    at useTheme (.../apps/staff-web/src/theme/ThemeProvider.tsx:39:11)
    at Consumer (.../apps/staff-web/tests/theme/ThemeProvider.test.tsx:6:32)
```

Findings:

1. **Out of scope.** The error is in `apps/staff-web`, which is outside this task's
   declared scope (`apps/api/.../s_g0_08` + V11). It cannot be fixed from this branch
   without violating the task rules, and no in-scope file can cause or cure it — the
   staff-web tests use local synthetic fixtures and make no API calls.
2. **Not an actual test failure.** The identical stack trace was reproduced locally.
   It is the expected stderr output of the intentional negative-path test
   `throws when used outside its provider` (`ThemeProvider.test.tsx:31-35`), which
   asserts `expect(() => render(<Consumer />)).toThrow(/useTheme/)`. React 18 dev mode
   logs the guarded-callback rethrow through jsdom's `reportException`; the test still
   catches it and passes. `npx vitest run tests/theme/ThemeProvider.test.tsx` → 3/3
   passed; the full `npm test` suite → **103/103 passed, exit 0**. The pasted text is a
   benign log line, not a build failure.
3. **Merge not reproducible locally.** The checkout is a grafted single-commit shallow
   clone (`git log --all` shows only `bb355f1`; `.git/shallow` present; no local `main`
   ref), so "the merge of main and this branch" cannot be reconstructed here. This is
   an evidence gap, not a code defect.

No in-scope defect was found; no in-scope change was needed for a fix attempt.

## Exact commands run and results

| Command | Result |
|---|---|
| `git status --short` / `git branch --show-current` / `git log -1` | clean tree; branch `swarm/S-G0-08-67`; HEAD `bb355f1` |
| `git show --stat HEAD` / `find` scope dirs / `ls db/migrations` | full S-G0-08 implementation in scope; V11 present |
| `bash scripts/quick-check.sh` | exit 1 **only** at `check-pack: opencode.json is stale` — environment artifact (see below); all preceding policy checks passed |
| `git ls-files -v opencode.json` | `S opencode.json` (skip-worktree); worktree file is CI profile (`wt == generated CI == True`, `committed == generated LOCAL == True`) — documented cloud-worker CI-profile trap, not a branch defect |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**; 231 tests, 0 failures, 0 errors; `AccessUnitTest` 41/41 |
| `cd apps/staff-web && npm ci && npx vitest run tests/theme/ThemeProvider.test.tsx` | **exit 0**; 3/3 passed (error text reproduced as benign stderr) |
| `cd apps/staff-web && npm test` | **exit 0**; 23 files, 103/103 passed |

Environment note: `check-pack.py` in this worker reads the skip-worktree CI-profile
`opencode.json`, so it reports it stale; the committed `opencode.json` matches the
generated local profile and is correct. Java/Database integration tests are left to
the integrator as directed.

## Defects found

- None in the declared scope.
- The reported frontend "failure" is a misread of expected negative-test stderr; the
  staff-web suite is green. It remains out of scope for this worker.

## Decision / stop notice

Per the task rule "if a decision or evidence is missing, stop and say so in
`.agent/pr-notes.md`": the merge-gate failure cannot be reproduced from this shallow
clone, the only supplied evidence is a benign log line from a passing out-of-scope
test, and there is no in-scope defect to fix. I therefore made **no code change** and
stop, committing only these notes. If the integrator still observes a non-zero
`scripts/check.sh` on the merge, the failing step and its exit code must be supplied;
if it is a genuine staff-web regression it belongs to a staff-web-scoped worker, not
this IAM-06 backend slice.
