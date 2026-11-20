# SmartClinic working rules

**Read the relevant docs before writing code.** List the repository's Markdown files and read the ones that govern your task (working rules, README/setup, CODING_STYLE.md, docs/WORKFLOW.md, architecture decisions, the maintainer guide and API conventions). Follow the code style and testing guidelines there; `opencode.json` and `.github/opencode.ci.json` also load these files as `instructions` for every agent, including swarm workers.

Read WORKSPACE.md and TODO.md first. TODO.md is the single task file: `[ ]` open, `[-]` taken by an agent, `[x]` done. Read MEMORY.md, BUGS.md and SMARTCLINIC_V1_SPEC.md by relevant IDs. Current code and executed checks are implementation evidence. Preserve unrelated changes. Never include patient data or credentials in agent context, tests, logs or trackers.

Use the smartclinic-delivery skill. The primary agent owns edits and history; specialists return bounded findings and patches in their reports. Route each task to its domain specialist. Give specialists only story IDs, paths, acceptance criteria and relevant evidence. Prefer one bounded review per stage; combine independent reviews when useful. Rehydrate from tracking files after context resets. Report unavailable delegation accurately.

Run one story at a time. Take it in TODO.md before you start (change `[ ]` to `[-]` and add `- agent: <name>`), record each stage's evidence on its entry, and mark it `[x]` with `evidence:` and `pr:` only when every gate passes. Put it back to `[ ]` if you stop. Swarm workers never edit TODO.md: the orchestrator marks their task `[-]` and the integrator marks it `[x]`. Update MEMORY.md with any durable decision. Update WORKSPACE.md when branch, commands or environment changes. Record defects in BUGS.md. Keep unrelated notes out of the active checkpoint.

Start with failing acceptance/regression tests, implement the smallest change, then refactor and verify. Apply CODING_STYLE.md. No uncovered production code is permitted by default. Require all applicable gates and observed evidence before calling a commit releasable. Use synthetic fixtures and defensive tests. Follow real-patient approval gates in the specification.

When the active execution request authorizes GitHub delivery, commit scoped passing changes and push to the agreed delivery branch. Wait for the required checks on the exact SHA and repair failures through a new tested commit. Do not force-push or bypass protections. Tag/publish releases only under the release policy and with release authority. Keep production deployment separately authorized.

Read project.json for the current display name and suggested repository slug. Use stable technical identifiers for agent routing and the GitHub remote for publication.

Use docs/WORKFLOW.md to route routine, cross-boundary and safety-critical changes. Document changed public contracts, build scripts/docs.sh, and include a short entry-point-to-test trace in review evidence.

## Shared knowledge loop

Every user instruction or choice is durable input. Route it to the owning tracker in the same turn. After any completed task, write the outcome to the owning tracker so the next agent resumes without re-asking.

| Input or outcome                                         | Owner file                              |
| -------------------------------------------------------- | --------------------------------------- |
| Durable directive, policy decision or approved choice    | MEMORY.md (decision, rationale, date)   |
| Task state, executed commands, reviewer or test evidence | TODO.md (marks and `evidence:` on the task entry) |
| Branch, delivery branch, commands or environment change  | WORKSPACE.md                            |
| Implementation decision with requirement IDs             | PROJECT.md                              |
| New story, acceptance change or improvement idea         | TODO.md                                 |
| Observed, reproducible defect                            | BUGS.md                                 |

Keep notes short and cite exact requirement IDs, paths and evidence. Record whether evidence came from a tool, reviewer, simulated user or human participant. Never store credentials, patient data or raw logs in any note.

## Engineering quality bar

Every change must meet all of the following before it is called done:

- **Notes go only to the file your prompt names**, `.agent/pr-notes/<task-id>-<timestamp>.md` (the path in your prompt wins over any older `<task-id>.md` wording in a task), one new file per run. Never create, edit or commit the shared `.agent/pr-notes.md` or any other file directly under `.agent/`: parallel workers overwrite it and every pull request then conflicts.
- **100% coverage** of production lines, branches, methods and classes from the first production file, with build-failing gates and no fabricated or pending-as-covered results.
- **Java coverage is enforced at 100% by JaCoCo in Delivery** (instruction, line, branch, method, class; one missed branch fails the whole run, as in run 518 and 520). The worker gate does not run JaCoCo, so you must prevent it yourself:
  - Every `if`, `?:`, `&&`, `||`, `switch` and `catch` you write needs a test that takes each side. A guard that can never be true is dead code: delete it (for example a check made impossible by an earlier `canTransitionTo` check) instead of leaving an uncoverable branch.
  - A `switch` statement on an enum with no `default` counts a hidden default branch that never runs. Use an `if / else if / else` chain, or a `switch` expression that returns a value.
  - A compare-and-set such as `return jdbc.update(...) == 1;` has two sides. Cover the false side deterministically with a unit test that mocks `NamedParameterJdbcTemplate` to return `0` (set `TenantContext` first, as `FacilityServiceTest` does). Never rely on a race between two threads for coverage.
  - An `INSERT ... ON CONFLICT DO NOTHING ... RETURNING` method has an "empty result" side. Cover it with a unit test whose mocked `jdbc.query(...)` returns an empty list.
  - Javadoc `{@link}` and `@see` targets are fully qualified (`java.time.Instant`).
  - Before you finish a Java change, list each new or changed branch and name the test that covers it in `.agent/pr-notes/<task-id>.md`.
- **Static and dependency security testing** on every change, including OWASP dependency analysis and language-appropriate static analysis. Record tool, version, command, finding count and disposition in the checkpoint.
- **Clean code and SOLID**: one responsibility per unit, small explicit interfaces, dependency inversion at boundaries, no duplicated rules across layers, readable names and guard clauses, and a traceable entry point to a test for each behavior.
- **Self-improvement**: when a defect, review finding or tool gap is observed, update the owning skill or agent instruction in the same change so the next run avoids it. Regenerate `opencode.json` after any agent or skill edit and commit the generated file.

## Coverage rules (100%, no exceptions)

Delivery fails the whole run on a single uncovered line or branch, in Java (JaCoCo), the staff web (Vitest) and the patient app (Jest). These rules are hard limits; a pull request that breaks one is not mergeable.

1. Every new or changed production line, branch, method and class is covered by a test you wrote in the same pull request. Write the failing test first.
2. Never lower, remove or bypass a threshold or exclusion: no edits to the JaCoCo rules in `apps/api/pom.xml`, to `coverage` or `thresholds` in `vitest.config.ts`, or to `coverageThreshold` in `apps/patient-mobile/jest.config.ts`; no new `exclude` entries, `@Generated`, `/* istanbul ignore */`, `/* c8 ignore */` or `// NOSONAR` comments; no test left `@Disabled`, `.skip` or `xit`.
3. If code cannot be covered because it is unreachable, delete the code. Do not keep a guard that no input can trigger, and do not write a test that only exists to touch a line.
4. Cover failure paths as well as success paths: every `if/else`, ternary, `catch`, early return, empty-result and stale-version path needs its own assertion on behavior, not just a call.
5. A test must assert an outcome (a value, a status code, a thrown error, a call that happened). A test with no assertion does not count.
6. Before you finish, list every new or changed branch and the test that covers it in your notes file. If you cannot name a test for a branch, the branch is not done.
7. Never weaken, delete or rewrite an existing test to make a change pass. If an existing test must change because behavior changed on purpose, say so in your notes.
8. **Do not mark a task complete until coverage is proven 100%.** For Java run `mvn -f apps/api/pom.xml verify` (or the repo's `apps/api/check.sh`) and read the JaCoCo report; for the staff web run Vitest with coverage; for the patient app run `apps/patient-mobile/check.sh`. Open the report for every file you touched and confirm no missed line or branch. Put the exact command and the coverage result in your notes file. If you cannot run the coverage command, or any touched file is below 100%, add tests until it is; if you still cannot, say so in your notes and leave the task unfinished instead of claiming it is done.
9. **If you cannot finish, say why.** Add a section "Why this is not finished" to your notes file: the failing command and error, the most likely cause, what you tried, and the exact next step. A task that fails without a written reason cannot be fixed efficiently.

## Frontend standards

- In patient-mobile tests write `import { jest } from '@jest/globals';` before using `jest.fn()` or `jest.mock()`; the bare `jest` namespace fails the type check (Delivery run 531). Run `bash apps/patient-mobile/check.sh` before you finish.
- TypeScript only: .ts/.tsx, never .js/.jsx. tsconfig must have "strict": true, "noUncheckedIndexedAccess": true; no `any`, no `@ts-ignore`.
- React: function components and hooks only, typed props, small single-purpose components, no business logic in JSX, data fetching via one typed API client.
- Before finishing any task run `tsc --noEmit`, `eslint . --max-warnings=0`, `prettier --check .` and the tests, and fix every failure. Never disable a rule to pass.
- Convert any existing .js/.jsx file you touch to TypeScript.

Apply these rules in every specialist routing brief and in acceptance evidence.
