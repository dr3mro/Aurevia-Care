# FIX-IMAGES-01 — Prepared-image readiness must not depend on workflow run history

## Requirement IDs and acceptance mapping

- **OPS-06** (delivery/gate automation). No application, patient-data or migration behaviour is touched.
- Story scope only: `.github/workflows`, `scripts`. No trackers, `apps/api` or `db/` were edited.

| Acceptance item | Where implemented | Evidence |
|---|---|---|
| Block a wave only when a build is queued or in progress, or when the newest completed run failed | `scripts/swarm.py::images_ready` (`IMAGE_BUILD_ACTIVE`, `failure` verdict) | `ImageReadinessTests.test_build_in_progress_blocks_even_when_images_exist`, `test_queued_build_blocks`, `test_latest_completed_failure_blocks` |
| No run at all + images present → start wave | `images_ready([], True)` → `True`; `prepare_images` emits `ready=true`, no dispatch | `test_no_runs_and_images_present_is_ready`, `test_no_history_images_present_starts_the_wave_without_dispatch` |
| No run at all + images missing → dispatch `agent-image.yml` once, skip wave | `prepare_images` → `gh workflow run agent-image.yml --ref main` + `emit dispatch=true` | `test_no_runs_and_images_missing_is_not_ready`, `test_no_history_images_missing_dispatches_agent_image_once` |
| Check every slot image `ghcr.io/<owner>/<repo>-agent-<slot>:latest` from `.github/swarm/slots.json` | `slot_images_exist()` via GitHub packages API (fails closed); slots loaded by `load_slots` | `test_slot_images_exist_checks_every_slot_and_fails_closed`, `test_slot_images_exist_true_when_every_manifest_has_latest` |
| Decision is a small tested Python function instead of workflow shell | `images-ready` subcommand replaces the `gh run list`/bash block in `swarm.yml` | `test_workflow_delegates_to_the_tested_python_decision`, `test_main_registers_the_images_ready_command` |
| Latest completed build succeeded → ready | `images_ready` returns `True` | `test_latest_completed_success_is_ready_even_without_registry_proof` |
| Cancelled/skipped-only history falls back to registry and can still dispatch a rebuild | `has_usable_image_history` shared by `images_ready` fallback and `prepare_images` registry/dispatch decision | `test_only_cancelled_runs_fall_back_to_the_registry`, `test_skipped_runs_fall_back_to_the_registry_too`, `test_usable_history_helper`, `test_cancelled_only_history_still_checks_the_registry`, `test_successful_history_starts_the_wave_without_a_registry_call` |

## Root cause

The `images` step of the `plan` job read only the latest `agent-image.yml` / `swarm-images.yml` runs and set `ready=false` whenever `slot_conclusion != success`. When Actions run history is empty (cleaned, expired or deleted), `gh run list` returns `[]`, so `slot_conclusion` was empty, `!= success` was true, and every wave was skipped forever with "Prepared images are rebuilding or not green" even though the slot images existed in GHCR. As of 2026-11-20 this repository has no `agent-image.yml` or `swarm-images.yml` runs.

## Change

- `scripts/swarm.py`
  - Added constants `IMAGE_BUILD_WORKFLOWS`, `IMAGE_BUILD_ACTIVE`.
  - Added pure function `images_ready(runs, manifests_exist)`: active build blocks; completed `failure` verdict blocks; cancelled/skipped-only or empty history falls back to the registry; a green verdict is authoritative.
  - Added `image_build_runs()` (latest run per image workflow, empty when history is gone).
  - Added `slot_images_exist(slots)` using `gh api repos/{owner}/{repo}/packages/container/{package}/versions --jq 'any(.[]; (.metadata.container.tags // []) | index("latest"))'` with `check=False` so a missing/unreadable package fails closed. No registry login needed.
  - Added `prepare_images()` subcommand: gathers runs, checks GHCR only when history is empty, emits `ready`, prints a clear reason, and dispatches `agent-image.yml` once only in the "no history + no images" case. `agent-image.yml` chains into `swarm-images.yml` on success.
  - Registered `images-ready --slots` in `main()`.
- `.github/workflows/swarm.yml`
  - Replaced the shell `latest()`/`gh run list` block with `python3 -I scripts/swarm.py images-ready`.
  - Plan job permissions: `actions: read` → `actions: write` (workflow dispatch) and added `packages: read` (GHCR versions API).
  - Clarified the skip message in the `plan` step.

## Entry-point-to-test trace

`.github/workflows/swarm.yml` plan job → step `images` → `python3 -I scripts/swarm.py images-ready` → `swarm.main()` (`elif a.cmd == "images-ready"`) → `prepare_images()` → `image_build_runs()` + `slot_images_exist(load_slots(...))` → `images_ready()` → `emit ready`. Tests drive `images_ready` directly and `prepare_images`/`slot_images_exist`/`main` with `unittest.mock` fakes for `gh`, `gh_json`, `load_slots` and `emit`; no network or credentials are used.

## Commands run and results (evidence source: executed tool commands)

Run from repo root on branch `swarm/FIX-IMAGES-01-230`, HEAD `43e915b451c3dd498ba54702771adf0afb99aeff` before my edits.

- Baseline before changes:
  - `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 234 tests`, `FAILED (failures=8, errors=10)`.
  - All 18 baseline failures/errors were pre-existing local-environment issues: 12 yaml-dependent tests plus `test_fast_tier`/`test_workflow_concurrency` loader errors (PyYAML is not installed locally and there is no pip/ensurepip/network); 3 `test_check_pack` failures (the worktree `opencode.json` is the CI profile copied under `skip-worktree`, a known measurement artifact documented in the delivery skill and BUGS OPS-CW-003-01); 3 `test_agent_run` failures/errors from local temp-repo git state. None touch this change.
- After writing the tests, before implementation (red):
  - `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k ImageReadiness -q` → `Ran 15 tests`, `FAILED (failures=1, errors=14)` — `AttributeError: module 'swarm' has no attribute 'images_ready'` and the workflow still contained the `gh run list` shell block.
- After implementation (green):
  - `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k ImageReadiness -q` → `Ran 17 tests`, `OK`.
  - `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 249 tests`, `FAILED (failures=8, errors=10)` — the exact same 18 pre-existing environment failures as baseline, with 15 net-new passing tests. No new failure.
  - `python3 -I -m py_compile scripts/swarm.py scripts/tests/test_swarm.py` → exit 0.
  - `python3 -I scripts/swarm.py images-ready --help` → exit 0, shows `--slots`.
  - `bash -n scripts/quick-check.sh` → exit 0.
- Line coverage of new production code (stdlib `trace --count`, because `coverage.py` is unavailable): a runner exercising every branch of the four new functions and the `main` dispatch showed a non-zero execution count on **every** executable line of `images_ready`, `image_build_runs`, `slot_images_exist`, `prepare_images` and the `images-ready` CLI branch in `scripts/swarm.py` (0 unexecuted lines). Evidence file: `/tmp/opencode/cov-fix-images-01/swarm.cover` (local scratch, not committed).
- Simulated user execution: none. Human clinical evidence: none. Two specialist reviews were requested (GitHub Workflow and Senior Python); their findings and disposition are recorded under "Review" below when received.

## Defects found

- **Original defect (fixed):** empty Actions run history permanently disabled every swarm wave even with healthy GHCR images.
- No new defect found in the changed scope during implementation or targeted review, except the intentionally unverified item below.

## Decisions and residual risks

- The registry check uses the GitHub packages API rather than `docker manifest inspect` so the plan job needs no registry login; `packages: read` was added to the plan job. `--jq` checks specifically for the `latest` tag, not merely any version.
- `conclusion: cancelled`/`skipped`/`null` runs are treated as "no usable verdict" and fall back to the registry, so a cancelled newest run cannot strand the swarm the way an empty history did. An explicit `failure` verdict always blocks (the repair/watchdog pipeline handles failed image builds).
- Dispatch is limited to the single "no runs + no images" case; a running or failed build is never re-dispatched by this step. A brief race between dispatch and the run appearing in history could dispatch more than once across overlapping hourly plan jobs; the `agent-image` concurrency group with `queue: max` serializes the resulting builds, so this is accepted.
- `docs/SWARM.md` still describes the "while `agent-image` or `swarm-images` is running, or the last slot build is not green, it starts no workers" rule and does not mention the registry fallback. It is outside this story's declared scope (`.github/workflows`, `scripts`), so it is left for a follow-up documentation task and is flagged here rather than silently changed.
- Not verified locally: live `gh api` packages response shape and a real workflow dispatch (no network/gh/credentials available in this worker, and the task forbids calling `gh`). The jq expression and command construction are covered by mocked unit tests; the integrator/CI will exercise the workflow in GitHub Actions.
- Delivery-path note for the integrator: this repair PR changes `.github/workflows/swarm.yml`. `scripts/swarm.py::publish()` refuses to push workflow-file changes unless `SWARM_WORKFLOW_PUSH=true` (i.e. a `SWARM_TOKEN` with workflow scope is configured). This is an existing documented swarm limitation (docs/SWARM.md, "Not verified yet"), not something this story changes. If the publish job reports `blocked=workflow-files`, the orchestrator must supply the workflow-scoped token or merge the branch manually; no source change is needed for image readiness itself.

## Review

Two bounded specialist reviews were requested. Evidence source for findings: **simulated specialist review** (read-only static inspection; the Python reviewer had no shell tool, so it could not execute tests — the findings were independently reproduced and fixed by the primary agent with executed tool commands below).

- **HIGH (both reviewers, same finding): cancelled/skipped-only history fail-open/strand.** `prepare_images` originally consulted the registry only when `runs` was empty and gated dispatch on `not runs`. With a cancelled newest run, `runs` is non-empty, so `manifests_exist` was hard-coded `True` → `images_ready` fell back to `True` → the wave started against possibly missing images; a naive "real registry check" fix would instead skip forever with no dispatch. **Fixed** by adding the pure helper `has_usable_image_history(runs)` and using it for both the registry lookup and the single dispatch decision. Added command-level tests for cancelled with images present/missing and for skipped. Re-ran `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k ImageReadiness -q` → `Ran 22 tests, OK`.
- **MEDIUM (Python reviewer): test gap.** Command-level tests did not cover cancelled/skipped history. **Fixed** by the new tests above plus a pure `skipped` case and a helper test.
- **Endpoint correction (primary agent, tool evidence):** the GitHub reviewer's note assumed a repo-scoped `repos/{owner}/{repo}/packages/container/{package}/versions` endpoint. I verified against the GitHub REST docs (webfetch, 2026-11-20): the versions endpoint is **owner-scoped only** — `GET /orgs/{org}/packages/{package_type}/{package_name}/versions` and `GET /users/{username}/packages/{package_type}/{package_name}/versions` both exist; no repo-scoped versions endpoint is documented. `slot_images_exist` therefore tries both owner scopes and fails closed; the response schema confirms `metadata.container.tags` is the correct field for the `latest` tag check. The repo owner (`dr3mro`) is a user account, so the `users` scope is the one that resolves.
- **LOW (GitHub reviewer): possible duplicate dispatch across overlapping plan triggers.** A queued run may appear in `gh run list` after a plan reads empty history, so two overlapping plans could both dispatch. Accepted: `agent-image.yml`/`swarm-images.yml` use `concurrency: queue: max`, so builds serialize rather than race; the extra build costs minutes but cannot corrupt the images. Re-checking history immediately before dispatch was considered but adds API calls and a new race of its own; left as a documented residual risk.
- **LOW (Python reviewer): robustness of `r.get()` / `repo_slug().split()`.** Inputs are guaranteed by `image_build_runs()` and a valid `GITHUB_REPOSITORY`; no realistic path to a non-mapping run or a slug without `/`. No change.
- Positive review confirmations: plan permissions (`packages: read`, `actions: write`) are correct; the `agent-image.yml → swarm-images.yml → swarm.yml` chain has no cycle; package naming and the `latest`-tag jq filter are correct; `emit ready` maps to `IMAGES_READY`; no YAML syntax defect observed.

Post-fix verification (executed tool evidence): `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 256 tests`, `FAILED (failures=8, errors=10)` — the identical pre-existing environment failure set as the 234-test baseline, with 22 net-new passing tests; `python3 -m trace --count --coverdir /tmp/opencode/cov-fix-images-02 /tmp/opencode/cov_runner_fix_images.py` → exit 0 with zero unexecuted lines in all five new functions and the CLI branch.
