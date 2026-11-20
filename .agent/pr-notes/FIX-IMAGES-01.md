# FIX-IMAGES-01 — Prepared-image readiness must not depend on workflow run history

Story: **FIX-IMAGES-01 · Prepared-image readiness must not depend on workflow run history**.
Requirement: **OPS-06**. Scope: `.github/workflows`, `scripts`.
This file is the pull-request description for this worker's change.

## Bottom line

The `plan` job's `images` step decided readiness from the presence of a *successful
latest* `swarm-images.yml` run. When Actions run history is empty (cleaned, expired or
deleted runs) there is no latest run, so `ready` was false on every wave and the swarm
skipped forever even though the slot images were present in GHCR. As of 2026-11-20 the
repository had no `agent-image.yml` or `swarm-images.yml` runs at all.

The decision now lives in a pure, tested Python function. A wave is blocked **only**
when a build is queued/in progress or the newest completed build did not succeed.
With no run history at all, the presence of `ghcr.io/<owner>/<repo>-agent-<slot>:latest`
for every slot decides; if the images are missing, one `agent-image.yml` build is
dispatched and the wave is skipped with an explicit reason.

## Scope and requirements

- Requirements: **OPS-06** (migration-change safety and delivery-pipeline integrity;
  this repair restores the swarm's prepared-image readiness gate).
- Allowed scope only: `.github/workflows/swarm.yml`, `scripts/swarm.py`,
  `scripts/tests/test_swarm.py`, and this notes file.
- Explicitly NOT touched: `apps/api`, `db/`, `scripts/check.sh`, `scripts/quick-check.sh`,
  `.opencode/`, `opencode.json`, any tracker (TODO.md, MEMORY.md, WORKSPACE.md, BUGS.md,
  PROJECT.md), or `docs/swarm`. No tracker was edited because the orchestrator already
  marked the task `[-]` and the integrator marks it `[x]` after merge.

## Acceptance mapping

| Acceptance criterion | Implementation | Evidence |
|---|---|---|
| Block only when a build is queued or in progress | `images_ready()` checks `BUILD_PENDING` statuses first (`queued`, `in_progress`, `waiting`, `pending`, `requested`) and returns `ready=False` with no dispatch | `test_build_in_progress_blocks_without_dispatch`, `test_pending_build_blocks_even_after_a_prior_success` |
| Block when the newest completed run failed | `images_ready()` takes the newest completed run (caller passes runs newest-first) and blocks on a `failure` conclusion; no dispatch for a genuine failure | `test_latest_completed_failure_blocks`, `test_failed_build_blocks_without_dispatch_even_without_images` |
| Inconclusive run states must not stall | `cancelled`/`skipped`/`stale`/`None` fall back to the registry, as empty history does | `test_inconclusive_conclusions_fall_back_to_the_registry` |
| The GitHub-reading adapter is correct | `image_runs()` queries both workflows with `--limit 1`, merges newest-first by `createdAt`, and drops malformed records | `test_image_runs_merges_both_workflows_newest_first`, `test_image_runs_with_no_history_is_empty`, `test_image_runs_drops_malformed_records` |
| No runs + images present → start the wave | `images_ready([], True) -> ready` | `test_no_runs_and_images_present_is_ready`, `test_cli_starts_the_wave_when_history_is_empty_but_images_exist` |
| No runs + images missing → dispatch agent-image.yml once and skip with a clear message | `images_ready([], False) -> dispatch`; `images_ready_command()` calls `gh workflow run agent-image.yml --ref main`, emits `ready=false`, and the plan step prints the reason | `test_no_runs_and_images_missing_blocks_and_dispatches`, `test_cli_dispatches_and_emits_when_images_are_missing` |
| Decide by whether the slot images exist | `slot_manifests_exist()` queries the packages API for each slot in `.github/swarm/slots.json`, checking for the `latest` tag | `test_manifest_check_covers_every_slot`, `test_manifest_check_fails_when_one_slot_is_missing`, `test_manifest_check_fails_without_a_repo_slug` |
| Decision in a small tested function, not shell | `images_ready()` is pure; the workflow `images` step is now one line calling the CLI | `test_workflow_delegates_the_decision_to_python`, `test_workflows_only_call_real_subcommands` |
| Add `packages: read` if missing | Plan job permissions gained `packages: read` (manifest checks) and `actions: write` (build dispatch) | `test_workflow_delegates_the_decision_to_python` |

Entry point → test trace: `swarm.yml` `plan/images` step → `python3 -I scripts/swarm.py
images-ready` → `images_ready_command()` → `image_runs()` (`gh run list` for both
workflows) + `slot_manifests_exist()` (`gh api` packages) → pure `images_ready()` →
`gh workflow run agent-image.yml` when needed → `emit(ready|reason|dispatch)` →
`GITHUB_OUTPUT` → `plan` step guard → wave matrix or skipped wave. The five required
scenarios are exercised from the pure decision entry point and the CLI wrapper in
`ImageReadinessTests`.

## Exact commands run and results (evidence source: executed tool commands)

From the repository root (Python 3.11 locally; CI uses the repo's Python):

- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -v` → all **17
  new `ImageReadinessTests` pass**; the only errors are **6 pre-existing** tests that
  do `import yaml` (PyYAML is not installed in this worker image; CI has it). No new
  failure is attributable to this change.
- Direct demonstration of `images_ready()` (tool output):
  - no runs, images present → `(True, 'slot images are present in the registry', False)`
  - no runs, images missing → `(False, 'slot images are missing from the registry', True)`
  - build in progress → `(False, 'a prepared-image build is queued or in progress', False)`
  - latest build failed → `(False, 'the newest prepared-image build did not succeed', False)`
  - latest build succeeded → `(True, 'the newest prepared-image build succeeded', False)`
  - cancelled + images present → `(True, 'slot images are present in the registry', False)`
  - failed + images missing → `(False, 'the newest prepared-image build did not succeed', False)`
- `python3 -I scripts/check-docs.py` → exit 0.
- `python3 -I scripts/check-secrets.py` → exit 0.
- `python3 -I scripts/check-migrations.py --require-baseline` → exit 0.
- `python3 -I scripts/project-name.py repositorySlug` → `aurevia-care`.
- `python3 -I scripts/swarm.py images-ready --help` → exit 0, subcommand registered.
- `bash -n scripts/quick-check.sh scripts/check.sh` → exit 0.
- `git diff --stat` → only the three scoped source files above are modified.

Not run here (integrator-owned, per worker contract): the database and integration
tests and the full `scripts/check.sh` / `integrator-gate.sh`. The fast unit tests are
the worker's gate; the JSON/YAML workflow files are additionally parsed by
`test_workflow_concurrency.py` and `test_swarm.py` in CI, where PyYAML is available.

## Defects found and fixed

1. **Root cause (the reported defect):** empty `gh run list` history produced
   `slot_conclusion=""`, and the old shell check treated any non-`success` conclusion
   as not ready → permanent wave skip. Fixed by making no-history fall back to GHCR
   image presence.
2. **Missing permission:** the plan job lacked `packages: read`, so the packages API
   manifest check would be denied. Added; also raised `actions: read` → `actions:
   write` because `gh workflow run agent-image.yml` requires write on Actions.
3. No other in-scope defect found. The environment's missing PyYAML and stale
   `opencode.json` CI profile cause unrelated pre-existing local test failures and
   were left unchanged.

## Decisions

- The decision is a pure function `images_ready(runs, manifests_exist)` so all branches
  are testable without GitHub or the network; the CLI wrapper only gathers inputs,
  dispatches a build and emits outputs.
- `runs` is a flat newest-first list of the newest run from each of the two image
  workflows; `gh run list` with `--limit 1` per workflow supplies them. Empty list is
  the explicit "no history" signal.
- Manifest presence uses the packages API (`packages: read`) rather than
  `docker manifest inspect`, so the plan job needs no Docker login and no credential
  is written to the repo. A 404 or any non-`true` answer counts as "missing".
- Dispatch happens only in the no-history + images-missing case. A genuinely failed
  build blocks without dispatch (it needs a person, not an hourly retry); a cancelled
  or other inconclusive run falls back to the registry. Because a dispatch creates a
  queued run, subsequent plans see a pending build and do not dispatch again —
  effectively once, as required.
- `image_runs()` filters non-dict records so a malformed `gh run list` item cannot
  crash the plan step and queue a false repair task.
- `slot_manifests_exist()` calls `gh api` with `check=True` so the shared `gh()` helper
  retries transient 502/503/504 responses; a real 404 (package never published) is
  caught as "image missing".
- `actions: write` is granted to the plan job solely to permit the build dispatch;
  all other jobs keep their existing least-privilege permissions.
- Accepted residual risks (reviewer-noted, low impact): (a) the tiny window between a
  base-image success and the chained slot-image build starting is treated as ready
  because the acceptance criterion says "latest build succeeded → ready"; (b) a
  successful build's manifest check is skipped (a build reports success only after it
  pushes the images); (c) two serialized plans could both dispatch before a queued
  run appears in `gh run list`, but `agent-image`'s `queue: max` concurrency serializes
  the builds, so no image is corrupted.

## Bounded specialist review

Two read-only reviews were run (GitHub Workflow, session `ses_e089fc9a6ffeO5lWKauJorJEDV`;
Senior Python, session `ses_e089fc9a6ffdXut7Bgb3jdyzWM`). Both confirmed the five
mandated scenarios, the permission grants (`packages: read`, `actions: write`), the
packages API path/jq for user and org repos, and the workflow chain that re-plans a
skipped wave. They found no blocker. Their actionable findings were resolved in this
change:

- `image_runs()` had no direct test and was not `None`-safe → added three tests
  (both-workflow merge/order, empty history, malformed-record filtering) and a dict
  filter in the adapter.
- Manifest tests only checked substrings → now assert the full endpoint
  `repos/Owner/Repo/packages/container/repo-agent-1/versions` and the exact jq tag
  expression.
- Inconclusive conclusions (`cancelled`/`skipped`/`stale`/`None`) blocked the wave with
  no recovery → they now fall back to the registry (liveness, same principle as the
  empty-history fix), with tests.
- Transient API errors with `check=False` bypassed `gh()` retries and read as "missing"
  → switched to `check=True` plus a caught `CalledProcessError`.
- Dispatch on a failed build could churn hourly → removed; dispatch is now exclusive
  to the no-history + images-missing case, matching the story wording.
- Weak `assertIn("succeed", ...)` → tightened to `"did not succeed"`.

Reviewers had no `execute` tool; their findings are static review (simulated reviewer
evidence), and the fixes above were verified by the executed tool commands listed
earlier. The reviewers did not re-run checks.

## Evidence provenance

- Tool / executed commands: all gate results, the five-scenario demonstration, and
  `git diff` scope verification above.
- Simulated specialist review: bounded read-only reviews recorded in the previous
  section; reviewers do not re-execute checks.
- No human, clinical or simulated-user acceptance was available or claimed for this
  pipeline repair.
