---
name: aurevia-delivery
description: "Runs the persistent Aurevia story, TDD, UAT, CI and release loop with restartable checkpoints. Use when working any story or swarm task, from claiming it to the notes file and the pull request."
---

# Delivery loop

**Swarm worker mode.** When your prompt says you are one of several parallel workers, or names a notes file under `.agents/notes/`, the swarm runs the outer loop for you, so skip the tracker and publishing parts of the steps below:

- **One task only.** Work the task in your prompt, then stop; never pick up another story, and ignore step 8 below.
- The task is already claimed. Do not edit `tasks/`, MEMORY.md, WORKSPACE.md, BUGS.md, PROJECT.md, `docs/swarm`, `AGENTS.md`, `.opencode/`, `scripts/` or workflows.
- Start from the earlier findings in your prompt when there are any, and from the newest `.agents/reports/<YYYY-MM-DD>.md` lessons. Do not redo what they record as tried.
- Your notes file is your checkpoint, your pull request description and the hand-off to the next worker. Use the headings in AGENTS.md, "Swarm hand-off". Write `## Why this is not finished` the moment you know you will stop early. Bind the notes to the revision you actually tested: write exactly one `Tested revision: <40-hex>` line from `git rev-parse HEAD` when the checks ran, before the final publish commits that may change HEAD. The swarm gate proves that revision is in the pull request head's history; a note without it, with an ambiguous or malformed SHA, or naming a revision outside that history is rejected. Any ancestor, including the base commit, passes, so a note can be replayed in a later head containing it: a passing binding is metadata consistency, not proof that checks ran. Never guess the final head SHA.
- Finish only with tests green and coverage proven at 100% for every file you touched (AGENTS.md, Coverage rules); the coverage gate after you finish sends failures back to you.
- **Frontend:** Jest/Vitest green is not done. Run `bash apps/patient-mobile/check.sh` or `bash apps/staff-web/check.sh` (both include `tsc --noEmit`) and fix every `error TS…`. The worker also re-runs `scripts/frontend-typecheck-check.sh` and sends typecheck failures back to you up to twice — treat that as a hard stop, not a soft warning.
- Read-only modes (review, audit, triage, notes reconciliation) write only the files their prompt names.

1. Resume from WORKSPACE.md and the `tasks/` directory (the `[-]` task file you own, otherwise the first `[ ]` task whose dependencies are `[x]`; mark it `[-]` with your agent name in its file before you start; a swarm worker never edits `tasks/`). Reconcile branch, dirty files, SHA, running CI and active story. **In a cloud worker, the worktree `opencode.json` is the CI profile that `agent-run.sh` copied under `skip-worktree`; `scripts/check.sh`, `gen-opencode.py --check` and `check-pack.py` then judge that CI profile, not the committed local profile.** Use `git show HEAD:opencode.json` to inspect the committed file, or state which profile was measured in the evidence. Mixing the two profiles up has produced false findings before. Ask Context Optimizer for a compact file and requirement map. Save discovery.
2. Product Manager turns user and unit feedback into outcomes. Product Owner ranks backlog and defines one story with acceptance scenarios. Physician reviews risks when clinically relevant. Architect defines boundaries and data changes. Scrum Master sets WIP one and assigns reviewers. Save the story, plan and routing.
3. Senior Software Engineer coordinates implementation. Use the development stack and specialist routing table in README-SETUP.md. Route each changed language, framework or runtime boundary to its named senior specialist. Ask for one shared review when multiple specialties overlap. Tester specifies executable checks. Write and run failing tests first. Confirm each meaningful failure is caused by missing behavior. Save red commands and results.
4. Implement, run green, then refactor and rerun. Use Debugger on failures. Run repository checks, unit, server/database, interface and end-to-end tests as applicable. Record actual commands, coverage, skipped tests and blockers. DevSecOps checks defensive changes and supply chain. System Designer reviews deployment, load balancing, capacity and recovery changes.
5. QA independently reviews evidence, risks and acceptance. Tester exercises normal entry points. UX reviews relevant surfaces. Clinician and the affected unit users exercise synthetic scenarios and report bugs or ideas. The patient reviewer exercises patient-facing slices. Supply execution evidence or mark simulations and unverified claims. Feed actual authorized human feedback into the same backlog. Route defects to BUGS.md and improvements to Product Manager and Product Owner. Hold a story with blocking findings and repeat red/green.
6. Update checkpoint and durable memory. Product Owner accepts against evidence. Stage only scoped changes. Run scripts/check.sh and release rehearsal as documented before commit/push. Make a small local commit. Push authorized branch changes and observe required CI for the exact SHA. Record SHA and checks in the next checkpoint without creating a status-only infinite commit loop. Fix a failed push check with a small tested corrective commit.
7. For an authorized release, require green default-branch CI, verify the version tag targets that SHA, observe image smoke/publish and the GitHub release, and record version plus digest. Deployment is a separate action.
8. Repeat for the next highest-priority eligible story. Continue while eligible work and authorization remain. On exhausted backlog, unmet prerequisites or human policy decisions, save the checkpoint and resume on new feedback or the next session. Feature complete means the specified V1 gates pass and the Product Owner accepts the evidence. Subsequent verified feedback reopens the backlog.

Classify each story using docs/WORKFLOW.md. Run targeted checks during implementation. At acceptance, require the applicable code, reference, and CI gates. Include an entry-point-to-test trace in the checkpoint.

## Definition of done

A standing bar for every story, separate from its acceptance scenarios. A story is done only when its acceptance scenarios are met and every item below is met or listed as not done with a reason.

- **Proven by tests.** The red failure was seen first; every new line and branch has a named test; 100% coverage is shown with the command for each touched area.
- **Reachable and durable.** A request reaches it: migration with RLS and FORCE RLS, a `Jdbc*` store, an injected service behind a thin controller, an HTTP level integration test and the OpenAPI entry. Data survives a restart.
- **Typed and linted.** Frontends pass their `check.sh` including `tsc --noEmit`; no `any` or `@ts-ignore` added.
- **Secure.** Dependency and static analysis ran with the tool, version, command and finding count recorded; no PHI or secrets in code, logs, tests or notes.
- **Documented and commented.** Every new or changed public type, function, script and migration has a doc comment (purpose, inputs, outputs, errors, invariant) and the non-obvious code has a comment that explains why; no commented-out code. Public contracts changed in `contracts/openapi.yaml`, `scripts/docs.sh` and `scripts/check-docs.py` pass, and the notes file follows the AGENTS.md hand-off headings.
- **Reviewed.** Before the plan is coded, `aurevia-plan-reviewer` has no Required gap. After the code, `aurevia-code-reviewer`, `aurevia-coverage-auditor` and `aurevia-devsecops` each ended with `VERDICT: GO`, and the notes file lists them under `## Review verdicts` as `- <reviewer>: GO`. `scripts/quality-gate.sh` (constraint check and verdict check) rejects a worker task that lacks them or that added a suppression, exclusion, skipped test or lowered threshold.
- **Contained.** Only files in the task scope changed; no tracker, workflow, script or `.opencode` file was edited by a worker.

## Common rationalizations

| Rationalization | Reality |
|---|---|
| Tests pass, I can stop. | Done means the definition of done below, including reachability and 100% coverage evidence. |
| I will record the notes at the end. | Write `## Why this is not finished` the moment you know you will stop early. |
| I will update the task file myself. | Swarm workers never edit trackers; the notes file is the hand-off. |

## Red flags

- A claim of done with no command output in the notes.
- A tracker, workflow, script or `.opencode` file edited by a worker.
- Work outside the task scope.

## Verification

- [ ] Every item of the definition of done is met or listed as not done with a reason.
- [ ] The notes file follows the AGENTS.md hand-off headings.
- [ ] Only scoped changes are committed.
