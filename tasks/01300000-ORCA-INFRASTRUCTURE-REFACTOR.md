- [-] ORCA-INFRASTRUCTURE-REFACTOR · Coordinated ORCA infrastructure audit, refactoring and verification
  - agent: orca
  - requirements: ORCA-001..ORCA-150, AG-01..AG-35, WF-01..WF-35 (source: `orca-refactor.md`)
  - scope: .opencode, .github, scripts, tasks/01300000-ORCA-INFRASTRUCTURE-REFACTOR.md, docs, .opencode/config
  - depends: none
  - details: Incremental, independently verifiable stages A–N from `orca-refactor.md` section 19. Do not attempt all requirements in one change. Preserve useful functionality, Git safety, branch protection, quality gates and clinical safety. Every disposition must cite tool/reviewer/simulated-user/human evidence and its source type. This task is the single authoritative checkpoint for the refactor; the 150 capability requirements are tracked as a disposition table inside this file rather than 150 duplicate tasks.

## Loaded skills (matched by advertised description)
- aurevia-delivery — mandatory for every story; persistent restartable loop.
- aurevia-context-optimizer — task is large and unclear; needs bounded authoritative context first.
- aurevia-task-advisor — multi-layer change needs one bounded worker brief per stage.
- aurevia-scrum-master — a 150-requirement queue needs WIP-one ordering and checkpoints.
- aurevia-product-owner — 150 requirements need prioritised, scenario-based stories and acceptance.
- aurevia-memory-manager — 2026-11-24 trackers must be reconciled with the new directive.
- aurevia-github-workflow — WF-01..WF-35 touch Actions, gates, tokens and concurrency.
- aurevia-devsecops — Git safety, permissions, free-model policy and reviewer integrity.
- aurevia-qa-reviewer — independent verification and BUGS-ready findings are mandatory.
- No additional skill matched for the read-only discovery stage (stage A); implementation stages will load the owning senior specialist skill before editing.

## Durable conflicts and owner rulings
- Model policy — RESOLVED: owner chose to keep direct `SWARM_MODEL` (`.opencode/config/MEMORY.md`, 2026-11-24 ruling). ORCA-032/033/069/141 are not applicable with that justification; no router, force flag, pricing registry or failover will be introduced.
- Execution isolation — RESOLVED: owner chose to stay on `main` on this PC with no worktrees. Strict worktree clauses are not applicable; disposable copies are reserved for destructive and fault-injection tests only.
- Git and GitHub operations — AUTHORISED BY OWNER BUT BLOCKED BY RUNTIME: the owner granted Git/GitHub execution, but this session's runtime authority denies `git`, `gh` and GitHub API calls. No commit, push, PR or live gate evidence can be produced here; hand off the tested source state to a trusted integrator.
- Skill naming — OPEN: ORCA-081 requires removing the `aurevia-` prefix from every skill ID and directory. The advertised routing contract in this session uses the current `aurevia-*` IDs, and agent/config regeneration plus atomic reference updates would be required. Status: staged, not started; must be done atomically with a regenerated config and a discovery check.

## Stage status (section 19 execution order)
- [x] Skills loaded and matched clauses recorded.
- [x] Trackers read: WORKSPACE.md, MEMORY.md, `tasks/_head.md`, newest task files.
- [x] Stage A read-only discovery and inventory (ORCA-001..003, AG/WF baseline) — four parallel read-only specialist passes returned; inventories and deliverable index written under `docs/orca-refactor/`.
- [ ] Stage B critical safety corrections — instruction paths, instruction consistency and AG-19 review-gate bypass are green; reviewer-provenance (AG-22) remains blocked on a trusted runner (tasks 01301000 and 01301200).
- [ ] Stage C baseline benchmarking.
- [ ] Stage D script ownership foundation.
- [ ] Stage E instructions and skills.
- [ ] Stage F task intelligence.
- [ ] Stage G supervisor refactor.
- [ ] Stage H model and memory (model subparts blocked).
- [ ] Stage I concurrent collaboration.
- [ ] Stage J reliability and recovery.
- [ ] Stage K engineering quality.
- [ ] Stage L workflow integration (live parts blocked).
- [ ] Stage M migration and simplification.
- [ ] Stage N final verification and 50 deliverables.

## Requirement disposition table
Template: `ID | Severity | Status | Evidence (source type) | Correction | Regression test | Confidence | Risk`.
Populate from the read-only specialist reports before any implementation stage. Statuses: confirmed / disproven / resolved / not applicable / blocked.

### Owner rulings applied to requirement dispositions (source: human participant, 2026-11-24)
- ORCA-032, ORCA-033, ORCA-069, ORCA-141: NOT APPLICABLE — the owner chose to keep direct `SWARM_MODEL` with no router or force/fallback (`.opencode/config/MEMORY.md` ruling row). Implemented state verified by tool: `scripts/agent-run.sh:351-357`, `scripts/tests/test_swarm.py:4046-4065`, no `model` key in either generated config. Confidence high.
- Worktree-isolation clauses in ORCA-035/037/047/108/119/146: NOT APPLICABLE as worktree mandates — the owner chose `main` on this PC. Disposable copies remain available for destructive and fault-injection tests only. Confidence high.
- ORCA-021/038/040 live gate, atomic merge and branch-protection evidence: BLOCKED — the owner granted Git/GitHub execution but this session's runtime denies `git`, `gh` and GitHub API calls. Missing evidence: exact-SHA check runs, ruleset state, merge-queue event, confirmed merge record. Confidence high.

### Stage A — security and permissions (source: read-only `devsecops` reviewer; file:line evidence; no Git/gh run)
- AG-05: PARTIAL — `docs/SWARM.md:190`, `.opencode/config/README-SETUP.md:33` still describe pushing to main; active worker prompt forbids it (`scripts/agent-run.sh:455`). Correction: docs-only, no gate change. Confidence high.
- AG-06: CONFIRMED — `.opencode/opencode.json:19-21`, `scripts/gen-opencode.py:81` grant `orca` wildcard shell allow. Correction: replace with explicit allow list; keep hard denies. Regression: `test_gen_opencode*` permission assertions. Confidence high.
- AG-07: PARTIAL — literal deny globs (`scripts/gen-opencode.py:51-60`) miss equivalent spellings; no canonicalisation test found. Confidence medium.
- AG-08: PARTIAL — prose differs but generated config grants no execution permission to specialists (`scripts/gen-opencode.py:91-98`). Mostly disproven at runtime.
- AG-09: DISPROVEN — memory-manager is read-only and proposes deltas (`.opencode/agents/memory-manager.md:6`, skill line 8).
- AG-10: DISPROVEN — one generator builds both profiles (`scripts/gen-opencode.py:76-110`).
- AG-14: CONFIRMED — docs claim every other shell command including push asks first (`.opencode/config/README-SETUP.md:57`) while the profile allows all shell (`gen-opencode.py:81`) and MEMORY records "always allow" (line 22). Correction: authoritative policy in one place. Confidence high.
- AG-16: CONFIRMED — `scripts/agent-run.sh:289-310,324-328` swaps tracked config to the CI profile and sets/clears `skip-worktree`; known false-positive OPS-CW-003-01 (`MEMORY.md:3,38`). Correction belongs to owner policy (directive conflict: no Git/index flags). Confidence high.
- AG-17: CONFIRMED — environment-dependent tests (`scripts/tests/test_swarm.py:614,821`, `test_check_pack.py:18-19`, `docs/reference/testing-guide.md:24`). Correction: hermetic fixtures, no worktree byte comparison. Confidence high.
- AG-18: RESOLVED — the worker/integrator coverage split is now stated in one authoritative sentence at `.opencode/setup/AGENTS.md:98`; regression `scripts/tests/test_instruction_consistency.py`. Confidence high (source-verified; no live gate run).
- AG-19: RESOLVED — `SWARM_REVIEW_GATE=off` removed from `scripts/quality-gate.sh` and `docs/WORKFLOW.md:11`; regression `scripts/tests/test_quality_gate.py` (2/2 green), shellcheck and policy checks green. No workflow set the switch. Confidence high (source-verified; no live gate run).
- AG-21: PARTIAL — interactive and swarm lifecycle mixed (`.opencode/setup/AGENTS.md:9,27`, `docs/SWARM.md:195`). Confidence high.
- AG-22: CONFIRMED — `scripts/check-verdicts.py:3-5,39-41,74-82` accepts reviewer lines authored by the worker in its own notes file; no provenance binding. This is a reviewer-independence defect; correction needs reviewer-run evidence. Confidence high.
- AG-29: CONFIRMED — runtime profile differs from committed profile by design (`gen-opencode.py:112-115`, `agent-run.sh:288-310`); drift detectable but real. Confidence high.
- AG-30: PARTIAL — nested subagent recursion blocked in agent permissions (`gen-opencode.py:85-98`), but shell deny list has no recursive `opencode run` rule. Confidence medium.
- AG-33: DISPROVEN for active code — no force/fallback/router implementation remains; `SWARM_MODEL` only (`agent-run.sh:351-357`, `test_swarm.py:4046-4065`, `MEMORY.md:33`). The new directive's force/fallback requirement therefore conflicts with current owner policy.
- AG-34: PARTIAL — issue/PR/notes text is marked untrusted and masked for tokens (`agent-run.sh:461-474`, `swarm.py:398-406`), but warnings are not instruction stripping. Confidence medium.

### Stage A — workflow audit (source: read-only `github-workflow` reviewer; file:line evidence)
- Delivery gate chain confirmed: `policy` → `script-tests` → `frontend` → `java-verify` → `docs` → `verify` → `release` → `gate`; `gate` needs all seven with `if: always()` and calls `scripts/check-delivery-gate.py` (`delivery.yml:267-286`). WF-11 CONFIRMED at source; live SHA verdict UNKNOWN (no GitHub access).
- Queueing gates use `queue: max` with `cancel-in-progress: false` on `delivery.yml:21-24`, `swarm.yml:60-63`, `swarm-integrator.yml:30-33`; no workflow sets `cancel-in-progress: true`. WF-27 CONFIRMED at source; live validation UNKNOWN.
- WF-13 CONFIRMED (optimistic CAS claim, `swarm.py:2924-2969`); WF-15 CONFIRMED (draft promotion with fresh head, `fix-pr-gate.yml:130-132`); WF-18 CONFIRMED (done only via merge record, `swarm.py:5955-5967`); WF-20 CONFIRMED (clean-worktree guard + per-slot mutex, `agent-run.sh:78-89`, `swarm-worker.yml:51-53`); WF-25/26/28/30/33/34 CONFIRMED at source with cited paths.
- WF-22 and WF-23 DISPROVEN: no fallback model and no `SWARM_FORCE_MODEL` exist, by owner decision (`MEMORY.md:33`, `test_swarm.py:4030`). This directly contradicts ORCA-032/033/069/141 in the new directive.
- WF-02/03/04/05/06/08/09/10/12/14/16/17/19/29/31/32/35 PARTIAL — implementation exists but needs live Git/GitHub, full-suite or race evidence. WF-01/07/11/13/15/18/20/25/26/27/28/30/33/34 CONFIRMED at source. No WF item is disproven except 22/23.
- Write tokens are separated from untrusted agent output in every path read (worker/debugger/fix-pr-gate publish jobs use fresh checkouts of main scripts); no untrusted-output-in-write-token-step found.
- Script ownership today: `.github/script/` and `.opencode/scripts/` do not exist; all orchestration is flat in `scripts/`. Integration lives in `scripts/swarm.py` (`integrate-validate`/`integrate-publish`), not in `integrate-*.py`. `.github/actions/` holds three composite actions. This is the baseline for ORCA-051..063; no migration has been performed.

### Stage A — script ownership and dependencies (source: read-only `senior-python` reviewer)
- 20 Python scripts and 17 Bash scripts in flat `scripts/`; tests only in `scripts/tests/` (~75 modules). No tests under `.github/` or `.opencode/`.
- Dependency-direction problems (ORCA-053): `scripts/swarm.py`, `scripts/swarm_gate.py`, `scripts/monitor.py` own GitHub/Git operations; `scripts/gen-opencode.py`, `scripts/route-task.py`, `scripts/check-pack.py` own OpenCode configuration; project gates live in the same flat directory. No circular imports found.
- Duplication (ORCA-079/084/115): `parse_tasks` in `scripts/monitor.py:287` and `scripts/swarm.py:2212`; Git wrappers in `swarm.py:256` and `check-constraints.py:84`; overlapping failure classifiers in `failure_classes.py:52`, `swarm.py:607,752`. `failure_classes.py` has no production caller.
- Working-directory independence (ORCA-057) is mostly satisfied: 13 Bash scripts resolve the repo root from `$0`, 10 Python scripts use `Path(__file__).resolve().parents[1]`; remaining `git rev-parse` assumptions are in `verify.sh:10`, `agent-run.sh:56,503,708,879,882,909,914`, `build-and-test.sh:174,175,239`.

## Evidence log
- (tool) Read `.opencode/config/WORKSPACE.md`, `.opencode/config/MEMORY.md`, `tasks/_head.md`, `tasks/01279000-WORKFLOW-AUDIT.md`. Branch/delivery state and prior audit history recorded there; no Git command was run in this session.
- (tool) `find`/`ls` counts: 39 agent files, 40 skill directories, 21 workflow files, 119 root script files, 1289 task files.
- (tool) Four read-only specialist discovery passes launched in parallel: `context-optimizer` (instructions/skills), `github-workflow` (WF-01..35), `devsecops` (AG security/permissions/model), `senior-python` (script ownership/dependencies).
- (tool) `opencode --version` → `opencode v2.4.0`; `.github/agent-image/Dockerfile` line 10 pins CLI `2.4.1`. Local host tool differs from the pinned runner image; the runner pin remains authoritative, so this is an observation, not a defect.
- (tool) `python3 -I scripts/gen-opencode.py --check` → exit 0 (generated config consistent). `python3 -I -m unittest discover -s scripts/tests -p 'test_gen_opencode*.py' -q` → `Ran 23 tests in 0.483s`, `OK`. `shellcheck --version` → `version: 0.11.0`.
- (tool) Both generated configs contain exactly 4 global instructions (`.opencode/setup/AGENTS.md`, `.opencode/setup/CODING_STYLE.md`, `.opencode/config/README-SETUP.md`, `docs/WORKFLOW.md`) and 39 agents; no `model` key at any top level. `.opencode/config/MEMORY.md` line 28 still claims 12 instructions: stale durable-memory record, cross-reference ORCA-106/AG-29.
- (tool) Baseline context proxy (characters, not tokens): globally loaded instruction docs total 183,008 bytes / 901 lines; agent Markdown 14,331 bytes; skill Markdown 96,998 bytes. No tokeniser is available in this session, so ORCA-002 token metrics must be reported as unavailable, not estimated.
- (tool) Stage A deliverables: `docs/orca-refactor/README.md` (50-item deliverable index) and `docs/orca-refactor/01-inventory.md` (instruction, agent, skill, script and workflow inventories, current and draft target architecture diagrams, baseline). `python3 -I scripts/check-docs.py` exit 0 after both files were added.
- (tool) First correction (AG-01/02/03, ORCA-007): red `python3 -I -m unittest discover -s scripts/tests -p 'test_instruction_paths.py' -v` → `FAILED (failures=18)` before the path edits; green after → `Ran 2 tests`, `OK`. Fixed stale root-level instruction references in `README.md`, `.opencode/setup/AGENTS.md`, `tasks/_head.md`, `.opencode/config/WORKSPACE.md`; defect ORCA-INSTRUCTION-PATHS recorded in BUGS.md.
- (tool) Post-fix policy checks: `python3 -I scripts/check-docs.py`, `python3 -I scripts/gen-opencode.py --check`, `python3 -I scripts/check-constraints.py`, `python3 -I scripts/check-names.py`, `python3 -I scripts/check-secrets.py` — each exit 0. Targeted tests: `test_check_docs*.py` 37/37 OK, `test_notes_files.py` 5/5 OK, `test_opencode_profile.py` 18/18 OK, `test_instruction_paths.py` 2/2 OK. No production Python line or branch changed; the new file is a test only, so no JaCoCo/Vitest/Jest coverage applies.
- (tool) `bash scripts/python-coverage.sh` exit 1, blocked by the local environment: header line `python coverage: WARNING coverage is not installed`, then `Ran 1657 tests in 120.686s`, `FAILED (failures=57, errors=35)`. Sampled failures in `test_hermetic_env.py` are fake-`mkdir` fixtures using `${name^^}`, which macOS bash 3.2 rejects (`bad substitution`); these are the confirmed AG-17 environment-dependent failures, not regressions of this change. The gate's changed-line output was `{"violations": []}`; the other printed `tracker` violations are fixture output from worker-gate tests. No full-suite green claim is made.
- (tool) `python3 -I scripts/check-wiring.py --check` exit 0 after the coverage run; `scripts/wiring-baseline.json` mtime remained 2026-11-23, so the "wrote wiring-baseline.json" line in the log was mocked fixture output, not a real protected-file write.
- (tool) Second correction (AG-14/18/21, ORCA-006): red `python3 -I -m unittest discover -s scripts/tests -p 'test_instruction_consistency.py' -v` → `Ran 2 tests`, `FAILED (failures=6)`; green after the three prose edits → `Ran 2 tests`, `OK`. `python3 -I scripts/check-docs.py` exit 0. The modified prose now matches `scripts/gen-opencode.py` hard denies and the worker/integrator coverage split.
- (human) Owner answered the three conflict questions on 2026-11-24: keep direct `SWARM_MODEL`; stay on `main` on this PC; Git/GitHub execution granted. The last answer is recorded but cannot take effect while this session's runtime denies Git-backed commands, so live evidence stays BLOCKED.

## Review verdicts (read-only reviewers, copied from their final lines)
- aurevia-code-reviewer: NO-GO on first pass — Required: inventory doc cited broken-reference locations already repaired in the same change. Corrected by labelling them a pre-repair baseline and naming the regression guard. Re-review: GO.
- aurevia-coverage-auditor: GO — red 18 subtest failures before the fix, 2/2 green after; both assertion sides covered; no production Python line or branch changed. Optional note: record the coverage-gate final line (done above).
- aurevia-devsecops: GO — no credential or PHI, no weakened gate, instruction precedence unchanged, new MEMORY rows do not authorise paid models, force-push or bypasses. Optional notes addressed (inventory baseline label; MEMORY line 28 marked superseded).

### Second change — instruction consistency (AG-14/18/21, ORCA-006)
- aurevia-code-reviewer: GO — required phrases present, retired contradictions absent, gates preserved; optional wording notes addressed (remote branch deletion, root/home recursive deletion, swarm-task ownership caveat).
- aurevia-devsecops: GO — no secret or PHI, no gate weakening, permission paragraph matches `scripts/gen-opencode.py`, owner-ruling rows consistent with the no-Git runtime.
- (tool) Final targeted run: `python3 -I -m unittest discover -s scripts/tests -p 'test_instruction_*.py' -q` → `Ran 4 tests`, `OK`. `check-docs.py`, `gen-opencode.py --check`, `check-constraints.py`, `check-names.py` all exit 0.

## Why this is not finished
Stage A is complete; Stage B is partial (instruction paths and instruction consistency are corrected and green; reviewer-provenance, wildcard shell permission and runtime config replacement remain open). Stages C to N are not started: no benchmark suite, no script ownership migration, no instruction/skill registry, no task contract or context compiler, no concurrency implementation, no fault-injection or final audit. Of the 50 deliverables, 4 are available, 12 partial and the rest pending (see `docs/orca-refactor/README.md`). Live Git/GitHub evidence is impossible in this session even though the owner granted it: the runtime still denies `git`, `gh` and GitHub API calls. The full Python coverage run is red from macOS bash 3.2 `${var^^}` fixtures and cannot prove the coverage gate here. No completion claim is made while mandatory requirements remain unresolved.

## Next bounded action
AG-22 is blocked until a trusted runner can produce review evidence outside the worker's write authority (task 01301200). The next unblocked work is Stage C baseline benchmarking and Stage D script-ownership foundation: add a hermetic benchmark harness under `scripts/tests` with byte/line proxies where tokenisation is unavailable, then classify and migrate scripts into the three ownership roots with caller and test updates. Publication and exact-SHA gate verification remain a trusted-integrator hand-off.
