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
