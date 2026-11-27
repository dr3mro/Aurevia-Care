- [x] RED-REPAIR-SELECTION · PO and SM select investigated red-state repairs before backlog work
  - scope: .github/workflows/swarm.yml, scripts/tests/test_swarm.py, docs/SWARM.md, tasks/01340000-RED-REPAIR-SELECTION.md
  - requirements: OPS-06
  - details:
    The swarm already detects a red `main`, creates `FIX-<run>` tasks from bounded failure logs, attaches PR failure evidence and gives repairs priority in the trusted planner (`scripts/swarm.py add-repair-task`, `plan_workers`, `fix-pr-gate.yml`). The PO and SM agents that choose the wave's candidates never see that evidence, so a red repair can be omitted from `feature=`/`tasks=` and ordinary backlog work competes with it. Wire the existing evidence into the two prompts: PO reads open repair tasks and their failure evidence and lists an eligible repair first; SM allocates repair candidates first within `WORKER_COUNT` without claiming them or relaxing exclusions. The trusted repair step, watchdog and planner keep ownership of creating and claiming tasks; the agent jobs stay read-only.
    Acceptance scenarios:
    1. Given a red `main` with an open, eligible `FIX-<run>` repair whose task file carries failure evidence, when PO selects, then that repair id is the first id in `tasks=` and the PO prompt says not to edit or claim trackers.
    2. Given a red PR with attached head SHA, run link, tool and bounded identifiers, when PO selects, then a matching repair candidate is preferred over unrelated backlog work.
    3. Given no open repair and a green `main`, when PO selects, then normal feature/task selection continues and no repair is invented.
    4. Given repair ids in `CANDIDATES`, when SM allocates, then eligible repairs come first inside the `WORKER_COUNT` cap and `[-]`, `[!]`, unmet dependencies and overlapping scopes are still excluded.
    5. Given any candidate touches protected files or gates, when the wave is dispatched, then the existing guard and repair gate still apply with no new write token, bypass or threshold change.
    Exclusions: no new script or CLI, no agent write token, no change to `main_is_red`, repair creation, planner priority, gates or concurrency, no live wave dispatch verification in this story.
    Related open tracker entries: 01248000-OPS-RED-MAIN-FIRST, 01249000-OPS-PR-REPAIR-WAVES, 01250000-OPS-RED-MAIN-DETECTION, 01251000-OPS-SWARM-PRIORITY-CAPACITY. They are not hard dependencies because the underlying implementation is already on `main`; this story only changes the PO/SM decision input.
  - evidence:
    Skills loaded by matched clause (tool): aurevia-delivery (delivery loop), aurevia-github-workflow (swarm.yml change), aurevia-senior-python (script tests), aurevia-product-owner (PO selection), aurevia-scrum-master (SM allocation), aurevia-context-optimizer (bounded discovery).

    Red first (tool): three tests added to scripts/tests/test_swarm.py::RepairEvidenceSelectionTests; targeted `PATH=/opt/homebrew/bin:$PATH python3 -I -m unittest discover -s scripts/tests -p test_swarm.py -k RepairEvidenceSelectionTests -q` -> `FAILED (failures=3)` before any prompt or guide text changed.

    Implementation (tool): PO prompt in .github/workflows/swarm.yml reads open FIX-<run>/DLV- repair evidence (failing job, head SHA, run link, log tail), lists an eligible repair first, emits the FIX prefix so the allocate job is reachable, and forbids tracker edits or invented ids; SM prompt allocates repair candidates first within WORKER_COUNT and keeps the existing exclusions; docs/SWARM.md records the contract. No script, token, gate, threshold, concurrency or planner logic changed.

    Critical defect found by code-reviewer, devsecops and github-workflow (simulated read-only reviewers, tool-confirmed by a runtime probe): backticks inside the double-quoted prompt were live command substitutions under `bash -lc`, stripping FIX-<run>/DLV- and breaking parse. All backticks were removed from both prompt assignments; a new test evaluates the extracted prompt through `bash -c` with synthetic env values and asserts the literal tokens and numeric cap survive.

    Green (tool): `PATH=/opt/homebrew/bin:$PATH python3 -I -m unittest discover -s scripts/tests -p test_swarm.py -q` -> `Ran 410 tests in 32.738s ... OK`; `git diff --check`, check-pack.py, check-names.py, check-wiring.py, check-docs.py, check-secrets.py, check-constraints.py --base origin/main --require-base and gen-opencode.py --check all exit 0. actionlint reports only the pre-existing `queue: max` key at swarm.yml:289, reproduced on the HEAD blob. Entry point to test: the PO "Select features with OpenCode" and SM "Allocate non-overlapping tasks with OpenCode" steps in .github/workflows/swarm.yml -> RepairEvidenceSelectionTests in scripts/tests/test_swarm.py.

  - review verdicts (read-only subagents, agent file IDs):
    - code-reviewer: GO (post-fix; Critical backtick defect and Optional eligibility/prefix findings closed).
    - coverage-auditor: GO (all five scenarios have named assertions; no production line or branch changed; no suppression or threshold change).
    - devsecops: GO (read-only agent jobs and output guards unchanged; no new token, PHI or bypass).
    - github-workflow: GO (YAML and prompt expansion valid; repair creation and claims stay in the trusted plan job; only pre-existing actionlint `queue: max`).

    Final gates (tool, on stable tree `732e4fa72a8d37b50fc34507627e571db534964c` after concurrent merges settled): `PATH=/opt/homebrew/bin:$PATH python3 -I -m unittest discover -s scripts/tests -q` -> `Ran 2425 tests in 318.872s ... OK`; `PATH=/opt/homebrew/bin:$PATH bash scripts/python-coverage.sh` -> `Ran 2425 tests in 255.802s ... OK`, `python coverage: every changed script line is covered`, both exit 0. An intermediate run at `d44b4fe7` was red only because another session merged `fix/ownership-script-resolution` mid-run and left a transient syntax error in `scripts/check-ownership.py`; the settled tree compiles and its 67 tests pass.

    Unverified: no live two-repair wave was dispatched; PO/SM behavior is guided by prompt text and locked by structural and shell-expansion tests. Evidence source: tool commands and read-only reviewers; no simulated-user or authorized-human feedback.
  - pr: direct commit `aa8f1716d1800f3c766e933fc6d0702cff24378c` on `main`; no pull request was opened for this story.
